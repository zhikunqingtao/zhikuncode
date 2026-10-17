package com.aicodeassistant.tool.search;

import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.impl.WebSearchTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSearchProtocolRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;
    private DeepSeekSearchBackend backend;
    private WebSearchTool tool;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        backend = new DeepSeekSearchBackend("dummy-key", server.url("/").toString());
        tool = toolFor(backend);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static WebSearchTool toolFor(WebSearchBackend backend) {
        WebSearchBackendFactory factory = mock(WebSearchBackendFactory.class);
        when(factory.createBackend()).thenReturn(backend);
        return new WebSearchTool(factory);
    }

    private ToolResult call(String query) {
        return tool.call(ToolInput.from(Map.of("query", query)),
                ToolUseContext.of("/tmp", "web-search-protocol-test"));
    }

    private void enqueue(String body) {
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json").setBody(body));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"error\":{\"message\":\"bad\"}}", "{\"results\":{}}", "", " ",
            "{\"results\":[", "[]", "{\"results\":null}", "{\"results\":[null]}", "{\"results\":[{}]}",
            "{\"results\":[{\"url\":\" \"}]}", "{\"results\":[{\"url\":123}]}"})
    void invalidSuccessBodyIsFailedNetworkResultAndNotRetried(String body) {
        enqueue(body);
        ToolResult result = call("query");
        assertThat(result.executionStatus()).isEqualTo(ToolResult.ExecutionStatus.FAILED);
        assertThat(result.failureType()).isEqualTo(ToolResult.ToolFailureType.NETWORK);
        assertThat(result.failureCode()).isEqualTo("WEB_SEARCH_FAILED");
        assertThat(result.retryability()).isEqualTo(ToolResult.Retryability.SAFE_READ_ONLY);
        assertThat(result.content()).doesNotContain("returned no results");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void mixedResultsKeepUsableItemsAndDoNotFailTheWholeSearch() throws Exception {
        enqueue("""
                {"results":[null,"bad",123,{},
                  {"url":" "},{"url":123},
                  {"title":"First","url":"https://a.com","summary":"one"},
                  {"title":"","url":"https://b.com","summary":"","snippet":"two","content":"body"}]}
                """);
        List<SearchResult> results = backend.search("query",
                new SearchOptions(null, null, 8, null, null, null, null, true));
        assertThat(results).hasSize(2);
        assertThat(results).extracting(SearchResult::url)
                .containsExactly("https://a.com", "https://b.com");
        assertThat(results.get(0).snippet()).isEqualTo("one");
        assertThat(results.get(1).title()).isEmpty();
        assertThat(results.get(1).snippet()).isEqualTo("two");
        assertThat(results.get(1).content()).isEqualTo("body");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void keepsEmptyTitleUrlDisplayFallbackAndSnippetFallback() {
        enqueue("{\"results\":[{\"title\":\"\",\"url\":\"https://a.com\",\"summary\":\"\",\"snippet\":\"fallback snippet\"}]}");
        ToolResult result = call("query");
        assertThat(result.executionStatus()).isEqualTo(ToolResult.ExecutionStatus.SUCCEEDED);
        assertThat(result.content()).contains("**https://a.com**", "fallback snippet");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }
}
