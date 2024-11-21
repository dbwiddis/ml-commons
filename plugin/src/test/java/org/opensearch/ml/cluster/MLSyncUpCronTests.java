/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.cluster;

import static java.util.Collections.emptyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.opensearch.ml.common.CommonValue.CREATE_TIME_FIELD;
import static org.opensearch.ml.common.CommonValue.MASTER_KEY;
import static org.opensearch.ml.common.CommonValue.ML_MODEL_INDEX;
import static org.opensearch.ml.plugin.MachineLearningPlugin.GENERAL_THREAD_POOL;
import static org.opensearch.ml.plugin.MachineLearningPlugin.ML_THREAD_POOL_PREFIX;
import static org.opensearch.ml.utils.TestHelper.ML_ROLE;
import static org.opensearch.ml.utils.TestHelper.setupTestClusterState;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.lucene.search.TotalHits;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.opensearch.Version;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.search.SearchResponseSections;
import org.opensearch.action.search.ShardSearchFailure;
import org.opensearch.action.support.PlainActionFuture;
import org.opensearch.action.update.UpdateRequest;
import org.opensearch.client.Client;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodeRole;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.concurrent.OpenSearchExecutors;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.commons.ConfigConstants;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.common.transport.TransportAddress;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.ml.action.connector.TransportCreateConnectorActionTests;
import org.opensearch.ml.common.CommonValue;
import org.opensearch.ml.common.FunctionName;
import org.opensearch.ml.common.MLModel;
import org.opensearch.ml.common.model.MLModelState;
import org.opensearch.ml.common.transport.sync.MLSyncUpAction;
import org.opensearch.ml.common.transport.sync.MLSyncUpNodeResponse;
import org.opensearch.ml.common.transport.sync.MLSyncUpNodesResponse;
import org.opensearch.ml.engine.encryptor.Encryptor;
import org.opensearch.ml.engine.encryptor.EncryptorImpl;
import org.opensearch.ml.engine.indices.MLIndicesHandler;
import org.opensearch.ml.sdkclient.SdkClientFactory;
import org.opensearch.ml.settings.MLFeatureEnabledSetting;
import org.opensearch.ml.utils.TestHelper;
import org.opensearch.sdk.SdkClient;
import org.opensearch.search.SearchHit;
import org.opensearch.search.SearchHits;
import org.opensearch.search.aggregations.InternalAggregations;
import org.opensearch.search.internal.InternalSearchResponse;
import org.opensearch.search.profile.SearchProfileShardResults;
import org.opensearch.search.suggest.Suggest;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ScalingExecutorBuilder;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

public class MLSyncUpCronTests extends OpenSearchTestCase {

    private static TestThreadPool testThreadPool = new TestThreadPool(
        TransportCreateConnectorActionTests.class.getName(),
        new ScalingExecutorBuilder(
            GENERAL_THREAD_POOL,
            1,
            Math.max(1, OpenSearchExecutors.allocatedProcessors(Settings.EMPTY) - 1),
            TimeValue.timeValueMinutes(1),
            ML_THREAD_POOL_PREFIX + GENERAL_THREAD_POOL
        )
    );

    @Mock
    private Client client;
    private SdkClient sdkClient;
    @Mock
    NamedXContentRegistry xContentRegistry;
    @Mock
    private ClusterService clusterService;
    @Mock
    private DiscoveryNodeHelper nodeHelper;
    @Mock
    private MLIndicesHandler mlIndicesHandler;

    @Mock
    private MLFeatureEnabledSetting mlFeatureEnabledSetting;

    private DiscoveryNode mlNode1;
    private DiscoveryNode mlNode2;
    private MLSyncUpCron syncUpCron;

    private final String mlNode1Id = "mlNode1";
    private final String mlNode2Id = "mlNode2";

    private ClusterState testState;
    private Encryptor encryptor;

    @Mock
    ThreadPool threadPool;
    ThreadContext threadContext;
    final String USER_STRING = "myuser|role1,role2|myTenant";

    @Before
    public void setup() throws IOException {
        MockitoAnnotations.openMocks(this);
        mlNode1 = new DiscoveryNode(mlNode1Id, buildNewFakeTransportAddress(), emptyMap(), ImmutableSet.of(ML_ROLE), Version.CURRENT);
        mlNode2 = new DiscoveryNode(mlNode2Id, buildNewFakeTransportAddress(), emptyMap(), ImmutableSet.of(ML_ROLE), Version.CURRENT);
        encryptor = spy(new EncryptorImpl(null, "m+dWmfmnNRiNlOdej/QelEkvMTyH//frS2TBeS2BP4w="));

        testState = setupTestClusterState();
        when(clusterService.state()).thenReturn(testState);

        doAnswer(invocation -> {
            ActionListener<Boolean> actionListener = invocation.getArgument(0);
            actionListener.onResponse(true);
            return null;
        }).when(mlIndicesHandler).initMLConfigIndex(any());

        Settings settings = Settings.builder().build();
        sdkClient = Mockito.spy(SdkClientFactory.createSdkClient(client, xContentRegistry, settings));
        threadContext = new ThreadContext(settings);
        threadContext.putTransient(ConfigConstants.OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT, USER_STRING);
        when(client.threadPool()).thenReturn(threadPool);
        when(threadPool.getThreadContext()).thenReturn(threadContext);
        when(threadPool.executor(anyString())).thenReturn(testThreadPool.executor(GENERAL_THREAD_POOL));
        syncUpCron = new MLSyncUpCron(client, sdkClient, clusterService, nodeHelper, mlIndicesHandler, encryptor, mlFeatureEnabledSetting);
    }

    @AfterClass
    public static void cleanup() {
        ThreadPool.terminate(testThreadPool, 500, TimeUnit.MILLISECONDS);
    }

    public void testInitMlConfig_MasterKeyNotExist() {
        doAnswer(invocation -> {
            ActionListener<GetResponse> listener = invocation.getArgument(1);
            GetResponse response = mock(GetResponse.class);
            when(response.isExists()).thenReturn(false);
            listener.onResponse(response);
            return null;
        }).when(client).get(any(), any());

        doAnswer(invocation -> {
            ActionListener<IndexResponse> listener = invocation.getArgument(1);
            IndexResponse indexResponse = mock(IndexResponse.class);
            listener.onResponse(indexResponse);
            return null;
        }).when(client).index(any(), any());

        syncUpCron.initMLConfig();
        Assert.assertNotNull(encryptor.encrypt("test", null));
        syncUpCron.initMLConfig();
        verify(encryptor, times(1)).setMasterKey(any(), any());
    }

    public void testInitMlConfig_MasterKeyExists() {
        doAnswer(invocation -> {
            ActionListener<GetResponse> listener = invocation.getArgument(1);
            GetResponse response = mock(GetResponse.class);
            when(response.isExists()).thenReturn(true);
            String masterKey = encryptor.generateMasterKey();
            when(response.getSourceAsMap())
                .thenReturn(ImmutableMap.of(MASTER_KEY, masterKey, CREATE_TIME_FIELD, Instant.now().toEpochMilli()));
            listener.onResponse(response);
            return null;
        }).when(client).get(any(), any());

        syncUpCron.initMLConfig();
        Assert.assertNotNull(encryptor.encrypt("test", null));
        syncUpCron.initMLConfig();
        verify(encryptor, times(1)).setMasterKey(any(), any());
    }

    public void testRun_NoMLModelIndex() {
        Metadata metadata = new Metadata.Builder().indices(Map.of()).build();
        DiscoveryNode node = new DiscoveryNode(
            "node",
            new TransportAddress(TransportAddress.META_ADDRESS, new AtomicInteger().incrementAndGet()),
            new HashMap<>(),
            ImmutableSet.of(DiscoveryNodeRole.DATA_ROLE),
            Version.CURRENT
        );
        ClusterState state = new ClusterState(
            new ClusterName("test cluster"),
            123l,
            "111111",
            metadata,
            null,
            DiscoveryNodes.builder().add(node).build(),
            null,
            Map.of(),
            0,
            false
        );
        ;
        when(clusterService.state()).thenReturn(state);

        syncUpCron.run();
        verify(client, never()).execute(eq(MLSyncUpAction.INSTANCE), any(), any());
    }

    public void testRun() {
        DiscoveryNode[] allNodes = new DiscoveryNode[] {};
        when(nodeHelper.getAllNodes()).thenReturn(allNodes);
        mockSyncUp_GatherRunningTasks();

        syncUpCron.run();
        verify(client, times(2)).execute(eq(MLSyncUpAction.INSTANCE), any(), any());
    }

    public void testRun_NoDeployedModel() {
        DiscoveryNode[] allNodes = new DiscoveryNode[] {};
        when(nodeHelper.getAllNodes()).thenReturn(allNodes);
        mockSyncUp_GatherRunningTasks();

        syncUpCron.run();
        verify(client, times(2)).execute(eq(MLSyncUpAction.INSTANCE), any(), any());
    }

    public void testRun_Failure() {
        DiscoveryNode[] allNodes = new DiscoveryNode[] {};
        when(nodeHelper.getAllNodes()).thenReturn(allNodes);
        mockSyncUp_GatherRunningTasks_Failure();

        syncUpCron.run();
        verify(client, times(1)).execute(eq(MLSyncUpAction.INSTANCE), any(), any());
    }

    public void testRefreshModelState_NoSemaphore() throws InterruptedException {
        syncUpCron.updateModelStateSemaphore.acquire();
        syncUpCron.refreshModelState(null, null);
        verify(client, Mockito.after(1000).never()).search(any());
        syncUpCron.updateModelStateSemaphore.release();
    }

    public void testRefreshModelState_SearchException() throws Exception {
        PlainActionFuture<SearchResponse> future = PlainActionFuture.newFuture();
        future.onFailure(new RuntimeException("test exception"));
        when(client.search(any(SearchRequest.class))).thenReturn(future);
        syncUpCron.refreshModelState(null, null);
        // Need a small delay due to multithreading
        verify(client, timeout(1000).times(1)).search(any(SearchRequest.class));
        assertBusy(() -> { assertTrue(syncUpCron.updateModelStateSemaphore.tryAcquire()); }, 5, TimeUnit.SECONDS);
        syncUpCron.updateModelStateSemaphore.release();
    }

    public void testRefreshModelState_SearchFailed() throws Exception {
        PlainActionFuture<SearchResponse> future = PlainActionFuture.newFuture();
        future.onFailure(new RuntimeException("search error"));
        when(client.search(any(SearchRequest.class))).thenReturn(future);
        syncUpCron.refreshModelState(null, null);
        // Need a small delay due to multithreading
        verify(client, timeout(1000).times(1)).search(any(SearchRequest.class));
        assertBusy(() -> { assertTrue(syncUpCron.updateModelStateSemaphore.tryAcquire()); }, 5, TimeUnit.SECONDS);
        syncUpCron.updateModelStateSemaphore.release();
    }

    public void testRefreshModelState_EmptySearchResponse() {
        SearchHits hits = new SearchHits(new SearchHit[0], null, Float.NaN);
        SearchResponseSections searchSections = new SearchResponseSections(hits, InternalAggregations.EMPTY, null, true, false, null, 1);
        SearchResponse searchResponse = new SearchResponse(
            searchSections,
            null,
            1,
            1,
            0,
            11,
            ShardSearchFailure.EMPTY_ARRAY,
            SearchResponse.Clusters.EMPTY
        );
        PlainActionFuture<SearchResponse> future = PlainActionFuture.newFuture();
        future.onResponse(searchResponse);
        when(client.search(any(SearchRequest.class))).thenReturn(future);
        syncUpCron.refreshModelState(new HashMap<>(), new HashMap<>());
        // Need a small delay due to multithreading
        verify(client, timeout(1000).times(1)).search(any(SearchRequest.class));
        verify(client, Mockito.after(1000).never()).bulk(any());
        assertTrue(syncUpCron.updateModelStateSemaphore.tryAcquire());
        syncUpCron.updateModelStateSemaphore.release();
    }

    public void testRefreshModelState_ResetAsDeployFailed() throws IOException {
        Map<String, Set<String>> modelWorkerNodes = new HashMap<>();
        Map<String, Set<String>> deployingModels = new HashMap<>();
        PlainActionFuture<SearchResponse> future = PlainActionFuture.newFuture();
        future.onResponse(createSearchModelResponse("modelId", "tenantId", MLModelState.DEPLOYED, 2, null, Instant.now().toEpochMilli()));
        when(client.search(any(SearchRequest.class))).thenReturn(future);

        syncUpCron.refreshModelState(modelWorkerNodes, deployingModels);
        // Need a small delay due to multithreading
        verify(client, timeout(1000).times(1)).search(any(SearchRequest.class));
        ArgumentCaptor<BulkRequest> bulkRequestCaptor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client, timeout(1000).times(1)).bulk(bulkRequestCaptor.capture());
        BulkRequest bulkRequest = bulkRequestCaptor.getValue();
        assertEquals(1, bulkRequest.numberOfActions());
        assertEquals(1, bulkRequest.requests().size());
        UpdateRequest updateRequest = (UpdateRequest) bulkRequest.requests().get(0);
        String updateContent = updateRequest.toString();
        assertTrue(updateContent.contains("\"model_state\":\"DEPLOY_FAILED\""));
        assertTrue(updateContent.contains("\"current_worker_node_count\":0"));
        assertEquals(ML_MODEL_INDEX, updateRequest.index());
    }

    public void testRefreshModelState_ResetAsPartiallyDeployed() throws IOException {
        Map<String, Set<String>> modelWorkerNodes = new HashMap<>();
        modelWorkerNodes.put("modelId", ImmutableSet.of("node1"));
        Map<String, Set<String>> deployingModels = new HashMap<>();
        PlainActionFuture<SearchResponse> future = PlainActionFuture.newFuture();
        future.onResponse(createSearchModelResponse("modelId", "tenantId", MLModelState.DEPLOYED, 2, 0, Instant.now().toEpochMilli()));
        when(client.search(any(SearchRequest.class))).thenReturn(future);
        syncUpCron.refreshModelState(modelWorkerNodes, deployingModels);
        // Need a small delay due to multithreading
        verify(client, timeout(1000).times(1)).search(any(SearchRequest.class));
        ArgumentCaptor<BulkRequest> bulkRequestCaptor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client, timeout(1000).times(1)).bulk(bulkRequestCaptor.capture());
        BulkRequest bulkRequest = bulkRequestCaptor.getValue();
        assertEquals(1, bulkRequest.numberOfActions());
        assertEquals(1, bulkRequest.requests().size());
        UpdateRequest updateRequest = (UpdateRequest) bulkRequest.requests().get(0);
        String updateContent = updateRequest.toString();
        assertTrue(updateContent.contains("\"model_state\":\"PARTIALLY_DEPLOYED\""));
        assertTrue(updateContent.contains("\"current_worker_node_count\":1"));
        assertEquals(ML_MODEL_INDEX, updateRequest.index());
    }

    public void testRefreshModelState_ResetCurrentWorkerNodeCountForPartiallyDeployed() throws IOException {
        Map<String, Set<String>> modelWorkerNodes = new HashMap<>();
        modelWorkerNodes.put("modelId", ImmutableSet.of("node1"));
        Map<String, Set<String>> deployingModels = new HashMap<>();
        PlainActionFuture<SearchResponse> future = PlainActionFuture.newFuture();
        future
            .onResponse(
                createSearchModelResponse("modelId", "tenantId", MLModelState.PARTIALLY_DEPLOYED, 3, 2, Instant.now().toEpochMilli())
            );
        when(client.search(any(SearchRequest.class))).thenReturn(future);
        syncUpCron.refreshModelState(modelWorkerNodes, deployingModels);
        // Need a small delay due to multithreading
        verify(client, timeout(1000).times(1)).search(any(SearchRequest.class));
        ArgumentCaptor<BulkRequest> bulkRequestCaptor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client, timeout(1000).times(1)).bulk(bulkRequestCaptor.capture());
        BulkRequest bulkRequest = bulkRequestCaptor.getValue();
        assertEquals(1, bulkRequest.numberOfActions());
        assertEquals(1, bulkRequest.requests().size());
        UpdateRequest updateRequest = (UpdateRequest) bulkRequest.requests().get(0);
        String updateContent = updateRequest.toString();
        assertTrue(updateContent.contains("\"model_state\":\"PARTIALLY_DEPLOYED\""));
        assertTrue(updateContent.contains("\"current_worker_node_count\":1"));
        assertEquals(ML_MODEL_INDEX, updateRequest.index());
    }

    public void testRefreshModelState_ResetAsDeploying() throws IOException {
        Map<String, Set<String>> modelWorkerNodes = new HashMap<>();
        modelWorkerNodes.put("modelId", ImmutableSet.of("node1"));
        Map<String, Set<String>> deployingModels = new HashMap<>();
        deployingModels.put("modelId", ImmutableSet.of("node2"));
        PlainActionFuture<SearchResponse> future = PlainActionFuture.newFuture();
        future.onResponse(createSearchModelResponse("modelId", "tenantId", MLModelState.DEPLOY_FAILED, 2, 0, Instant.now().toEpochMilli()));
        when(client.search(any(SearchRequest.class))).thenReturn(future);
        syncUpCron.refreshModelState(modelWorkerNodes, deployingModels);
        // Need a small delay due to multithreading
        verify(client, timeout(1000).times(1)).search(any(SearchRequest.class));
        ArgumentCaptor<BulkRequest> bulkRequestCaptor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client, timeout(1000).times(1)).bulk(bulkRequestCaptor.capture());
        BulkRequest bulkRequest = bulkRequestCaptor.getValue();
        assertEquals(1, bulkRequest.numberOfActions());
        assertEquals(1, bulkRequest.requests().size());
        UpdateRequest updateRequest = (UpdateRequest) bulkRequest.requests().get(0);
        String updateContent = updateRequest.toString();
        assertTrue(updateContent.contains("\"model_state\":\"DEPLOYING\""));
        assertTrue(updateContent.contains("\"current_worker_node_count\":1"));
        assertEquals(ML_MODEL_INDEX, updateRequest.index());
    }

    public void testRefreshModelState_NotResetState_DeployingModelTaskRunning() throws IOException {
        Map<String, Set<String>> modelWorkerNodes = new HashMap<>();
        Map<String, Set<String>> deployingModels = new HashMap<>();
        deployingModels.put("modelId", ImmutableSet.of("node2"));
        PlainActionFuture<SearchResponse> future = PlainActionFuture.newFuture();
        future.onResponse(createSearchModelResponse("modelId", "tenantId", MLModelState.DEPLOYING, 2, null, Instant.now().toEpochMilli()));
        when(client.search(any(SearchRequest.class))).thenReturn(future);
        syncUpCron.refreshModelState(modelWorkerNodes, deployingModels);
        // Need a small delay due to multithreading
        verify(client, timeout(1000).times(1)).search(any(SearchRequest.class));
        verify(client, Mockito.after(1000).never()).bulk(any());
    }

    public void testRefreshModelState_NotResetState_DeployingInGraceTime() throws IOException {
        Map<String, Set<String>> modelWorkerNodes = new HashMap<>();
        Map<String, Set<String>> deployingModels = new HashMap<>();
        PlainActionFuture<SearchResponse> future = PlainActionFuture.newFuture();
        future.onResponse(createSearchModelResponse("modelId", "tenantId", MLModelState.DEPLOYING, 2, null, Instant.now().toEpochMilli()));
        when(client.search(any(SearchRequest.class))).thenReturn(future);
        syncUpCron.refreshModelState(modelWorkerNodes, deployingModels);
        // Need a small delay due to multithreading
        verify(client, timeout(1000).times(1)).search(any(SearchRequest.class));
        verify(client, Mockito.after(1000).never()).bulk(any());
    }

    private void mockSyncUp_GatherRunningTasks() {
        doAnswer(invocation -> {
            ActionListener<MLSyncUpNodesResponse> listener = invocation.getArgument(2);
            List<MLSyncUpNodeResponse> nodeResponses = new ArrayList<>();
            String[] deployedModelIds = new String[] { randomAlphaOfLength(10) };
            String[] runningDeployModelIds = new String[] { randomAlphaOfLength(10) };
            String[] runningDeployModelTaskIds = new String[] { randomAlphaOfLength(10) };
            String[] expiredModelIds = new String[] { randomAlphaOfLength(10) };
            nodeResponses
                .add(
                    new MLSyncUpNodeResponse(
                        mlNode1,
                        "ok",
                        deployedModelIds,
                        runningDeployModelIds,
                        runningDeployModelTaskIds,
                        expiredModelIds
                    )
                );
            MLSyncUpNodesResponse response = new MLSyncUpNodesResponse(ClusterName.DEFAULT, nodeResponses, Arrays.asList());
            listener.onResponse(response);
            return null;
        }).when(client).execute(eq(MLSyncUpAction.INSTANCE), any(), any());
    }

    private void mockSyncUp_GatherRunningTasks_Failure() {
        doAnswer(invocation -> {
            ActionListener<MLSyncUpNodesResponse> listener = invocation.getArgument(2);
            listener.onFailure(new RuntimeException("failed to get running tasks"));
            return null;
        }).when(client).execute(eq(MLSyncUpAction.INSTANCE), any(), any());
    }

    private SearchResponse createSearchModelResponse(
        String modelId,
        String tenantId,
        MLModelState state,
        Integer planningWorkerNodeCount,
        Integer currentWorkerNodeCount,
        Long lastUpdateTime
    ) throws IOException {
        XContentBuilder content = TestHelper.builder();
        content.startObject();
        content.field(CommonValue.TENANT_ID, tenantId);
        content.field(MLModel.MODEL_STATE_FIELD, state);
        content.field(MLModel.ALGORITHM_FIELD, FunctionName.KMEANS);
        content.field(MLModel.PLANNING_WORKER_NODE_COUNT_FIELD, planningWorkerNodeCount);
        if (currentWorkerNodeCount != null) {
            content.field(MLModel.CURRENT_WORKER_NODE_COUNT_FIELD, currentWorkerNodeCount);
        }
        content.field(MLModel.LAST_UPDATED_TIME_FIELD, lastUpdateTime);
        content.endObject();

        SearchHit[] hits = new SearchHit[1];
        hits[0] = new SearchHit(0, modelId, null, null).sourceRef(BytesReference.bytes(content));

        return new SearchResponse(
            new InternalSearchResponse(
                new SearchHits(hits, new TotalHits(1, TotalHits.Relation.EQUAL_TO), 1.0f),
                InternalAggregations.EMPTY,
                new Suggest(Collections.emptyList()),
                new SearchProfileShardResults(Collections.emptyMap()),
                false,
                false,
                1
            ),
            "",
            5,
            5,
            0,
            100,
            ShardSearchFailure.EMPTY_ARRAY,
            SearchResponse.Clusters.EMPTY
        );
    }
}
