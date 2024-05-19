/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.connector;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.opensearch.action.LatchedActionListener;
import org.opensearch.action.get.GetRequest;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.PlainActionFuture;
import org.opensearch.client.Client;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.index.get.GetResult;
import org.opensearch.ml.common.connector.HttpConnector;
import org.opensearch.ml.common.transport.connector.MLConnectorGetRequest;
import org.opensearch.ml.common.transport.connector.MLConnectorGetResponse;
import org.opensearch.ml.helper.ConnectorAccessControlHelper;
import org.opensearch.ml.sdkclient.LocalClusterIndicesClient;
import org.opensearch.sdk.SdkClient;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope;

@ThreadLeakScope(ThreadLeakScope.Scope.NONE) // TODO: implement thread pool executors and remove this
public class GetConnectorTransportActionTests extends OpenSearchTestCase {
    private static final String CONNECTOR_ID = "connector_id";

    @Mock
    ThreadPool threadPool;

    @Mock
    Client client;

    SdkClient sdkClient;

    @Mock
    NamedXContentRegistry xContentRegistry;

    @Mock
    TransportService transportService;

    @Mock
    ActionFilters actionFilters;

    @Mock
    ActionListener<MLConnectorGetResponse> actionListener;

    @Mock
    GetResponse getResponse;

    @Mock
    private ConnectorAccessControlHelper connectorAccessControlHelper;

    GetConnectorTransportAction getConnectorTransportAction;
    MLConnectorGetRequest mlConnectorGetRequest;
    ThreadContext threadContext;

    @Before
    public void setup() throws IOException {
        MockitoAnnotations.openMocks(this);

        sdkClient = new LocalClusterIndicesClient(client, xContentRegistry);
        mlConnectorGetRequest = MLConnectorGetRequest.builder().connectorId(CONNECTOR_ID).build();
        when(getResponse.getId()).thenReturn(CONNECTOR_ID);
        when(getResponse.getSourceAsString()).thenReturn("{}");
        Settings settings = Settings.builder().build();

        getConnectorTransportAction = spy(
            new GetConnectorTransportAction(transportService, actionFilters, client, sdkClient, connectorAccessControlHelper)
        );

        doAnswer(invocation -> {
            ActionListener<Boolean> listener = invocation.getArgument(3);
            listener.onResponse(true);
            return null;
        }).when(connectorAccessControlHelper).validateConnectorAccess(any(), any(), any());

        threadContext = new ThreadContext(settings);
        when(client.threadPool()).thenReturn(threadPool);
        when(threadPool.getThreadContext()).thenReturn(threadContext);
    }

    public void testGetConnector_UserHasNodeAccess() throws IOException, InterruptedException {
        doAnswer(invocation -> {
            ActionListener<Boolean> listener = invocation.getArgument(3);
            listener.onResponse(false);
            return null;
        }).when(connectorAccessControlHelper).validateConnectorAccess(any(), any(), any());

        GetResponse getResponse = prepareConnector();
        PlainActionFuture<GetResponse> future = PlainActionFuture.newFuture();
        future.onResponse(getResponse);
        when(client.get(any(GetRequest.class))).thenReturn(future);

        CountDownLatch latch = new CountDownLatch(1);
        LatchedActionListener<MLConnectorGetResponse> latchedActionListener = new LatchedActionListener<>(actionListener, latch);
        getConnectorTransportAction.doExecute(null, mlConnectorGetRequest, latchedActionListener);
        latch.await();

        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals("You don't have permission to access this connector", argumentCaptor.getValue().getMessage());
    }

    public void testGetConnector_ValidateAccessFailed() throws IOException, InterruptedException {
        doAnswer(invocation -> {
            ActionListener<Boolean> listener = invocation.getArgument(3);
            listener.onFailure(new Exception("Failed to validate access"));
            return null;
        }).when(connectorAccessControlHelper).validateConnectorAccess(any(), any(), any());

        GetResponse getResponse = prepareConnector();
        PlainActionFuture<GetResponse> future = PlainActionFuture.newFuture();
        future.onResponse(getResponse);
        when(client.get(any(GetRequest.class))).thenReturn(future);

        CountDownLatch latch = new CountDownLatch(1);
        LatchedActionListener<MLConnectorGetResponse> latchedActionListener = new LatchedActionListener<>(actionListener, latch);
        getConnectorTransportAction.doExecute(null, mlConnectorGetRequest, latchedActionListener);
        latch.await();

        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals("You don't have permission to access this connector", argumentCaptor.getValue().getMessage());
    }

    public void testGetConnector_NullResponse() throws InterruptedException {
        PlainActionFuture<GetResponse> future = PlainActionFuture.newFuture();
        future.onResponse(null);
        when(client.get(any(GetRequest.class))).thenReturn(future);

        CountDownLatch latch = new CountDownLatch(1);
        LatchedActionListener<MLConnectorGetResponse> latchedActionListener = new LatchedActionListener<>(actionListener, latch);
        getConnectorTransportAction.doExecute(null, mlConnectorGetRequest, latchedActionListener);
        latch.await();

        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals("Failed to find connector with the provided connector id: connector_id", argumentCaptor.getValue().getMessage());
    }

    public void testGetConnector_IndexNotFoundException() throws InterruptedException {
        PlainActionFuture<GetResponse> future = PlainActionFuture.newFuture();
        future.onFailure(new IndexNotFoundException("Fail to find model"));
        when(client.get(any(GetRequest.class))).thenReturn(future);

        CountDownLatch latch = new CountDownLatch(1);
        LatchedActionListener<MLConnectorGetResponse> latchedActionListener = new LatchedActionListener<>(actionListener, latch);
        getConnectorTransportAction.doExecute(null, mlConnectorGetRequest, latchedActionListener);
        latch.await();

        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals("Failed to find connector", argumentCaptor.getValue().getMessage());
    }

    public void testGetConnector_RuntimeException() throws InterruptedException {
        PlainActionFuture<GetResponse> future = PlainActionFuture.newFuture();
        future.onFailure(new RuntimeException("errorMessage"));
        when(client.get(any(GetRequest.class))).thenReturn(future);

        CountDownLatch latch = new CountDownLatch(1);
        LatchedActionListener<MLConnectorGetResponse> latchedActionListener = new LatchedActionListener<>(actionListener, latch);
        getConnectorTransportAction.doExecute(null, mlConnectorGetRequest, latchedActionListener);
        latch.await();

        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        // TODO: Fix this nesting
        // [OpenSearchException[java.lang.RuntimeException: errorMessage]; nested: RuntimeException[errorMessage];
        assertEquals("errorMessage", argumentCaptor.getValue().getCause().getCause().getMessage());
    }

    public GetResponse prepareConnector() throws IOException {
        HttpConnector httpConnector = HttpConnector.builder().name("test_connector").protocol("http").build();

        XContentBuilder content = httpConnector.toXContent(XContentFactory.jsonBuilder(), ToXContent.EMPTY_PARAMS);
        BytesReference bytesReference = BytesReference.bytes(content);
        GetResult getResult = new GetResult("indexName", "111", 111l, 111l, 111l, true, bytesReference, null, null);
        GetResponse getResponse = new GetResponse(getResult);
        return getResponse;
    }
}
