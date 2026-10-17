package com.aicodeassistant.tool.search;

import com.aicodeassistant.mcp.McpClientManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * WebSearchBackendFactory 单元测试 — 后端选择与优先级。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebSearchBackendFactory 单元测试")
class WebSearchBackendFactoryTest {

    @Mock
    private McpClientManager mcpClientManager;

    @Test
    @DisplayName("backend=deepseek 且 key 非空 — 返回 DeepSeekSearchBackend")
    void testCreateBackend_deepseekWithKey_returnsDeepSeekBackend() {
        WebSearchBackend backend = factory("deepseek", "test-key").createBackend();

        assertThat(backend).isInstanceOf(DeepSeekSearchBackend.class);
        assertThat(backend.isAvailable()).isTrue();
        assertThat(backend.name()).isEqualTo("deepseek-search");
    }

    @Test
    @DisplayName("backend=deepseek 但 key 为空 — 返回 DisabledSearchBackend")
    void testCreateBackend_deepseekWithoutKey_returnsDisabled() {
        WebSearchBackend backend = factory("deepseek", "").createBackend();

        assertThat(backend).isInstanceOf(DisabledSearchBackend.class);
    }

    @Test
    @DisplayName("backend=auto 且 MCP 不可用 — 即使 DeepSeek key 已配置仍返回 Disabled")
    void testCreateBackend_autoDoesNotSelectDeepSeek() {
        when(mcpClientManager.getConnection(anyString())).thenReturn(Optional.empty());

        WebSearchBackend backend = factory("auto", "test-key").createBackend();

        assertThat(backend).isInstanceOf(DisabledSearchBackend.class);
    }

    private WebSearchBackendFactory factory(String backendConfig, String deepseekApiKey) {
        WebSearchBackendFactory factory = new WebSearchBackendFactory(mcpClientManager);
        ReflectionTestUtils.setField(factory, "backendConfig", backendConfig);
        ReflectionTestUtils.setField(factory, "searchApiKey", "");
        ReflectionTestUtils.setField(factory, "searxngUrl", "");
        ReflectionTestUtils.setField(factory, "deepseekApiKey", deepseekApiKey);
        ReflectionTestUtils.setField(factory, "deepseekBaseUrl", "https://api.deepseek.com");
        return factory;
    }
}
