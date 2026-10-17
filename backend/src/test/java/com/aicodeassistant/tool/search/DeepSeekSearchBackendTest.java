package com.aicodeassistant.tool.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DeepSeekSearchBackend 单元测试 — 使用 MockWebServer，不访问真实 API。
 */
@DisplayName("DeepSeekSearchBackend 单元测试")
class DeepSeekSearchBackendTest {

    private static final String TRUNCATION_SUFFIX = "…(truncated)";

    private final ObjectMapper mapper = new ObjectMapper();

    private MockWebServer server;
    private DeepSeekSearchBackend backend;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        // baseUrl 带结尾斜杠，同时验证拼接不会出现双斜杠
        backend = new DeepSeekSearchBackend("test-key", server.url("/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    // ═══════════════ 1. 正常返回 + 请求断言 ═══════════════

    @Test
    @DisplayName("正常返回 2 条结果 — 路径/鉴权/请求体正确，snippet 映射 summary，content 为空")
    void testSearch_normal_returnsMappedResults() throws Exception {
        // Given
        server.enqueue(jsonResponse(200, """
                {"results": [
                  {"rerank_score": 2.8, "title": "Spring Boot 文档", "url": "https://a.com/1",
                   "snippet": "short snippet 1", "summary": "SUMMARY-ONE", "content": "",
                   "site_name": "A", "date": "2026-01-01", "authority": 3},
                  {"rerank_score": 1.9, "title": "Spring 指南", "url": "https://b.com/2",
                   "snippet": "short snippet 2", "summary": "SUMMARY-TWO", "content": "",
                   "site_name": "B", "date": "2026-01-02", "authority": 2}
                ]}
                """));

        // When
        List<SearchResult> results = backend.search("spring boot",
                new SearchOptions(List.of(), List.of(), 5, "zh-CN", null, null, null, false));

        // Then
        assertThat(results).hasSize(2);
        assertThat(results.get(0).title()).isEqualTo("Spring Boot 文档");
        assertThat(results.get(0).url()).isEqualTo("https://a.com/1");
        assertThat(results.get(0).snippet()).isEqualTo("SUMMARY-ONE");
        assertThat(results.get(0).content()).isEmpty();
        assertThat(results.get(1).snippet()).isEqualTo("SUMMARY-TWO");
        assertThat(results.get(1).content()).isEmpty();

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/v1/tools/search");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-key");
        assertThat(request.getHeader("Content-Type")).contains("application/json");

        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertThat(body.path("query").asText()).isEqualTo("spring boot");
        assertThat(body.path("count").asInt()).isEqualTo(5);
        assertThat(body.path("need_content").asBoolean()).isFalse();
    }

    // ═══════════════ 2. includeContent ═══════════════

    @Test
    @DisplayName("includeContent=true — need_content=true，正文非空且长度受限")
    void testSearch_includeContent_requestsAndTruncatesContent() throws Exception {
        // Given
        String longContent = "L".repeat(5000);
        server.enqueue(jsonResponse(200, """
                {"results": [
                  {"title": "T", "url": "https://a.com/1", "snippet": "s", "summary": "SUMMARY",
                   "content": "%s", "site_name": "A", "date": "2026-01-01", "authority": 3}
                ]}
                """.formatted(longContent)));

        // When
        List<SearchResult> results = backend.search("java records",
                new SearchOptions(null, null, 3, null, null, null, null, true));

        // Then
        assertThat(results).hasSize(1);
        SearchResult result = results.getFirst();
        assertThat(result.snippet()).isEqualTo("SUMMARY");
        assertThat(result.content()).isNotBlank();
        assertThat(result.content().length()).isLessThanOrEqualTo(3010);
        assertThat(result.content()).endsWith(TRUNCATION_SUFFIX);

        JsonNode body = mapper.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(body.path("need_content").asBoolean()).isTrue();
        assertThat(body.path("count").asInt()).isEqualTo(3);
    }

    // ═══════════════ 3. 域名过滤拼接进 query ═══════════════

    @Test
    @DisplayName("allowedDomains + blockedDomains — 拼进 query 的搜索语法")
    void testSearch_domainFilters_composedIntoQuery() throws Exception {
        // Given
        server.enqueue(jsonResponse(200, """
                {"results": [{"title": "T", "url": "https://a.com/1", "snippet": "s",
                 "summary": "SUM", "content": ""}]}
                """));

        // When
        backend.search("jdk 21",
                new SearchOptions(List.of("a.com", "b.com"), List.of("c.com"),
                        5, null, null, null, null, false));

        // Then
        JsonNode body = mapper.readTree(server.takeRequest().getBody().readUtf8());
        String sentQuery = body.path("query").asText();
        assertThat(sentQuery).startsWith("jdk 21");
        assertThat(sentQuery).contains("site:a.com OR site:b.com");
        assertThat(sentQuery).contains("-site:c.com");
    }

    // ═══════════════ 4. 429 重试 ═══════════════

    @Test
    @DisplayName("429 后重试成功 — 服务端收到 2 次请求")
    void testSearch_retriesOn429_thenSucceeds() throws Exception {
        // Given
        server.enqueue(jsonResponse(429, """
                {"error": {"message": "rate limit exceeded", "type": "rate_limit",
                 "param": null, "code": "rate_limit_exceeded"}}
                """));
        server.enqueue(jsonResponse(200, """
                {"results": [{"title": "T", "url": "https://a.com/1", "snippet": "s",
                 "summary": "SUM", "content": ""}]}
                """));

        // When
        List<SearchResult> results = backend.search("retry me",
                new SearchOptions(1));

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().url()).isEqualTo("https://a.com/1");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    // ═══════════════ 5. 4xx 抛 IOException ═══════════════

    @Test
    @DisplayName("400 + error.message — 抛 IOException 且消息带状态码与服务端 message")
    void testSearch_badRequest_throwsIOExceptionWithServerMessage() {
        // Given
        server.enqueue(jsonResponse(400, """
                {"error": {"message": "Invalid parameter: count must be <= 50",
                 "type": "invalid_request_error", "param": null, "code": "invalid_parameter"}}
                """));

        // When / Then
        assertThatThrownBy(() -> backend.search("bad request", SearchOptions.defaults()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("400")
                .hasMessageContaining("Invalid parameter: count must be <= 50");
    }

    // ═══════════════ 6. 空结果数组 ═══════════════

    @Test
    @DisplayName("results 为空数组 — 返回空列表且不抛异常")
    void testSearch_emptyResults_returnsEmptyList() throws Exception {
        // Given
        server.enqueue(jsonResponse(200, "{\"results\": []}"));

        // When
        List<SearchResult> results = backend.search("nothing here", SearchOptions.defaults());

        // Then
        assertThat(results).isEmpty();
    }

    // ═══════════════ 7. summary 截断 ═══════════════

    @Test
    @DisplayName("summary 长度 3000 — snippet 截断到 ≤1200 且以截断后缀结尾")
    void testSearch_longSummary_truncatedTo1200() throws Exception {
        // Given
        String longSummary = "S".repeat(3000);
        server.enqueue(jsonResponse(200, """
                {"results": [
                  {"title": "T", "url": "https://a.com/1", "snippet": "short",
                   "summary": "%s", "content": ""}
                ]}
                """.formatted(longSummary)));

        // When
        List<SearchResult> results = backend.search("long summary",
                new SearchOptions(null, null, 5, null, null, null, null, false));

        // Then
        assertThat(results).hasSize(1);
        String snippet = results.getFirst().snippet();
        assertThat(snippet.length()).isLessThanOrEqualTo(1200);
        assertThat(snippet).endsWith(TRUNCATION_SUFFIX);
    }

    // ═══════════════ 8. summary 为空回退 snippet 也要截断 ═══════════════

    @Test
    @DisplayName("summary 为空、snippet 2000 字符 — 回退后同样截断到 ≤1200")
    void testSearch_blankSummaryFallbackSnippet_truncatedTo1200() throws Exception {
        // Given
        String longSnippet = "F".repeat(2000);
        server.enqueue(jsonResponse(200, """
                {"results": [
                  {"title": "T", "url": "https://a.com/1", "snippet": "%s",
                   "summary": "", "content": ""}
                ]}
                """.formatted(longSnippet)));

        // When
        List<SearchResult> results = backend.search("fallback snippet",
                new SearchOptions(null, null, 5, null, null, null, null, false));

        // Then
        assertThat(results).hasSize(1);
        String snippet = results.getFirst().snippet();
        assertThat(snippet.length()).isLessThanOrEqualTo(1200);
        assertThat(snippet).endsWith(TRUNCATION_SUFFIX);
    }

    // ═══════════════ 9. allowedDomains 上限 5 ═══════════════

    @Test
    @DisplayName("allowedDomains 5 个 — query 中 5 个 site: 全部保留")
    void testSearch_allowedDomainsFive_allKept() throws Exception {
        // Given
        server.enqueue(jsonResponse(200, """
                {"results": [{"title": "T", "url": "https://a.com/1", "snippet": "s",
                 "summary": "SUM", "content": ""}]}
                """));

        // When
        backend.search("five domains", new SearchOptions(
                List.of("d1.com", "d2.com", "d3.com", "d4.com", "d5.com"), List.of(),
                5, null, null, null, null, false));

        // Then
        String sentQuery = mapper.readTree(server.takeRequest().getBody().readUtf8())
                .path("query").asText();
        for (int i = 1; i <= 5; i++) {
            assertThat(sentQuery).contains("site:d" + i + ".com");
        }
        assertThat(countOccurrences(sentQuery, "site:")).isEqualTo(5);
    }

    @Test
    @DisplayName("allowedDomains 6 个 — 只保留前 5 个")
    void testSearch_allowedDomainsSix_onlyFirstFiveKept() throws Exception {
        // Given
        server.enqueue(jsonResponse(200, """
                {"results": [{"title": "T", "url": "https://a.com/1", "snippet": "s",
                 "summary": "SUM", "content": ""}]}
                """));

        // When
        backend.search("six domains", new SearchOptions(
                List.of("d1.com", "d2.com", "d3.com", "d4.com", "d5.com", "d6.com"), List.of(),
                5, null, null, null, null, false));

        // Then
        String sentQuery = mapper.readTree(server.takeRequest().getBody().readUtf8())
                .path("query").asText();
        for (int i = 1; i <= 5; i++) {
            assertThat(sentQuery).contains("site:d" + i + ".com");
        }
        assertThat(sentQuery).doesNotContain("site:d6.com");
        assertThat(countOccurrences(sentQuery, "site:")).isEqualTo(5);
    }

    // ═══════════════ helpers ═══════════════

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private MockResponse jsonResponse(int statusCode, String body) {
        return new MockResponse()
                .setResponseCode(statusCode)
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
