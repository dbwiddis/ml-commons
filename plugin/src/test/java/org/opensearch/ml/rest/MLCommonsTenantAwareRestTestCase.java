/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.rest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.opensearch.common.xcontent.XContentType.JSON;
import static org.opensearch.ml.common.input.Constants.TENANT_ID_HEADER;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.message.BasicHeader;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.client.Response;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.core.xcontent.DeprecationHandler;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.ml.common.input.Constants;
import org.opensearch.ml.utils.TestHelper;
import org.opensearch.rest.RestRequest;
import org.opensearch.test.rest.FakeRestRequest;

public abstract class MLCommonsTenantAwareRestTestCase extends MLCommonsRestTestCase {

    // Toggle to run DDB tests
    protected static final boolean DDB = true;

    protected static final String DOC_ID = "_id";

    // REST methods
    protected static final String POST = RestRequest.Method.POST.name();
    protected static final String GET = RestRequest.Method.GET.name();
    protected static final String PUT = RestRequest.Method.PUT.name();
    protected static final String DELETE = RestRequest.Method.DELETE.name();

    // REST paths; some subclasses need multiple of these
    protected static final String AGENTS_PATH = "/_plugins/_ml/agents/";
    protected static final String CONNECTORS_PATH = "/_plugins/_ml/connectors/";
    protected static final String MODELS_PATH = "/_plugins/_ml/models/";
    protected static final String MODEL_GROUPS_PATH = "/_plugins/_ml/model_groups/";

    // REST body
    protected static final String MATCH_ALL_QUERY = "{\"query\":{\"match_all\":{}}}";
    protected static final String EMPTY_CONTENT = "{}";

    // REST Response error reasons
    protected static final String MISSING_TENANT_REASON = "Tenant ID header is missing";
    protected static final String NO_PERMISSION_REASON = "You don't have permission to access this resource";

    // Common constants and fields used in subclasses
    protected static final String CONNECTOR_ID = "connector_id";

    protected String tenantId = randomAlphaOfLength(5);
    protected String otherTenantId = randomAlphaOfLength(6);

    protected final RestRequest tenantRequest = new FakeRestRequest.Builder(NamedXContentRegistry.EMPTY)
        .withHeaders(Map.of(TENANT_ID_HEADER, singletonList(tenantId)))
        .build();
    protected final RestRequest otherTenantRequest = new FakeRestRequest.Builder(NamedXContentRegistry.EMPTY)
        .withHeaders(Map.of(TENANT_ID_HEADER, singletonList(otherTenantId)))
        .build();
    protected final RestRequest nullTenantRequest = new FakeRestRequest.Builder(NamedXContentRegistry.EMPTY)
        .withHeaders(emptyMap())
        .build();

    protected final RestRequest tenantMatchAllRequest = getRestRequestWithHeadersAndContent(tenantId, MATCH_ALL_QUERY);
    protected final RestRequest otherTenantMatchAllRequest = getRestRequestWithHeadersAndContent(otherTenantId, MATCH_ALL_QUERY);
    protected final RestRequest nullTenantMatchAllRequest = getRestRequestWithHeadersAndContent(null, MATCH_ALL_QUERY);

    protected static boolean isMultiTenancyEnabled() throws IOException {
        // pass -Dtests.rest.tenantaware on gradle command line to enable
        boolean enabled = Boolean.parseBoolean(System.getProperty("tests.rest.tenantaware"));
        // TODO: remove this as a changeable setting and load from opensearch.yml
        if (enabled) {
            enableMultiTenancy();
        }
        return enabled;
    }

    protected static void enableMultiTenancy() throws IOException {
        Response response = TestHelper
            .makeRequest(
                client(),
                PUT,
                "_cluster/settings",
                null,
                "{\"persistent\":{\"plugins.ml_commons.multi_tenancy_enabled\":true}}",
                List.of(new BasicHeader(HttpHeaders.USER_AGENT, ""))
            );
        assertEquals(200, response.getStatusLine().getStatusCode());
    }

    protected static Response makeRequest(RestRequest request, String method, String path) throws IOException {
        return TestHelper
            .makeRequest(client(), method, path, request.params(), request.content().utf8ToString(), getHeadersFromRequest(request));
    }

    private static List<Header> getHeadersFromRequest(RestRequest request) {
        return request
            .getHeaders()
            .entrySet()
            .stream()
            .map(e -> new BasicHeader(e.getKey(), e.getValue().stream().collect(Collectors.joining(","))))
            .collect(Collectors.toList());
    }

    protected static RestRequest getRestRequestWithHeadersAndContent(String tenantId, String requestContent) {
        Map<String, List<String>> headers = new HashMap<>();
        if (tenantId != null) {
            headers.put(Constants.TENANT_ID_HEADER, singletonList(tenantId));
        }
        RestRequest request = new FakeRestRequest.Builder(NamedXContentRegistry.EMPTY)
            .withHeaders(headers)
            .withContent(new BytesArray(requestContent), JSON)
            .build();
        return request;
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> responseToMap(Response response) throws IOException {
        return parseResponseToMap(response);
    }

    @SuppressWarnings("unchecked")
    protected static String getErrorReasonFromResponseMap(Map<String, Object> map) {
        // Two possible cases:
        String type = ((Map<String, String>) map.get("error")).get("type");

        // {
        // "error": {
        // "root_cause": [
        // {
        // "type": "status_exception",
        // "reason": "You don't have permission to access this resource"
        // }
        // ],
        // "type": "status_exception",
        // "reason": "You don't have permission to access this resource"
        // },
        // "status": 403
        // }
        if ("status_exception".equals(type)) {
            return ((Map<String, String>) map.get("error")).get("reason");
        }

        // {
        // "error": {
        // "reason": "System Error",
        // "details": "You don't have permission to access this resource",
        // "type": "OpenSearchStatusException"
        // },
        // "status": 403
        // }
        return ((Map<String, String>) map.get("error")).get("details");
    }

    protected static SearchResponse searchResponseFromResponse(Response response) throws IOException {
        XContentParser parser = JsonXContent.jsonXContent
            .createParser(
                NamedXContentRegistry.EMPTY,
                DeprecationHandler.IGNORE_DEPRECATIONS,
                TestHelper.httpEntityToString(response.getEntity()).getBytes(UTF_8)
            );
        return SearchResponse.fromXContent(parser);
    }

    protected static void assertNotFound(Response response) {
        assertEquals(RestStatus.NOT_FOUND.getStatus(), response.getStatusLine().getStatusCode());
    }

    protected static void assertForbidden(Response response) {
        assertEquals(RestStatus.FORBIDDEN.getStatus(), response.getStatusLine().getStatusCode());
    }

    protected static void assertUnauthorized(Response response) {
        assertEquals(RestStatus.UNAUTHORIZED.getStatus(), response.getStatusLine().getStatusCode());
    }

    protected void refreshBeforeSearch(boolean extraDelay) {
        try {
            refreshAllIndices();
            Thread.sleep(extraDelay ? 60000L : 5000L);
        } catch (IOException | InterruptedException e) {
            // ignore
        }
    }

    protected static String registerRemoteModelContent(String description, String connectorId, String modelGroupId) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"name\": \"remote model for connector_id ").append(connectorId).append("\",\n");
        sb.append("  \"function_name\": \"remote\",\n");
        sb.append("  \"description\": \"").append(description).append("\",\n");
        if (modelGroupId != null) {
            sb.append("  \"model_group_id\": \"").append(modelGroupId).append("\",\n");
        }
        sb.append("  \"connector_id\": \"").append(connectorId).append("\"\n");
        sb.append("}");
        return sb.toString();
    }
}
