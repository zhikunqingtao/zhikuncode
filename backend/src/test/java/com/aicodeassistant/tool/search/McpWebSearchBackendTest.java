package com.aicodeassistant.tool.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * McpWebSearchBackend 回归测试 — 确保 site_name 不再被塞进 content 字段。
 * <p>
 * 直接通过反射调用私有 {@code parseResults}，避免依赖真实 MCP 连接。
 */
@DisplayName("McpWebSearchBackend 单元测试")
class McpWebSearchBackendTest {

    private static final String RAW_RESULT = """
            [{"title": "MCP 结果", "url": "https://a.com/1",
              "content": "MCP 摘要正文文本", "site_name": "SITE-NAME-SHOULD-NOT-LEAK",
              "icon": "https://a.com/favicon.ico"}]
            """;

    @Test
    @DisplayName("parseResults — content 为空串，snippet 保留原 content，site_name 不泄漏")
    @SuppressWarnings("unchecked")
    void testParseResults_siteNameNotMappedToContent() {
        // Given — parseResults 不使用 mcpClientManager，可传 null
        McpWebSearchBackend backend = new McpWebSearchBackend(null);

        // When
        List<SearchResult> results = ReflectionTestUtils.invokeMethod(
                backend, "parseResults", RAW_RESULT);

        // Then
        assertThat(results).hasSize(1);
        SearchResult result = results.getFirst();
        assertThat(result.title()).isEqualTo("MCP 结果");
        assertThat(result.url()).isEqualTo("https://a.com/1");
        assertThat(result.snippet()).isEqualTo("MCP 摘要正文文本");
        assertThat(result.content()).isEmpty();
        assertThat(result.content()).isNotEqualTo("SITE-NAME-SHOULD-NOT-LEAK");
    }
}
