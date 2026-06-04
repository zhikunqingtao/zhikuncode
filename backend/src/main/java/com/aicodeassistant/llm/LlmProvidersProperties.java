package com.aicodeassistant.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;

/**
 * 多 Provider 配置属性 — 支持同时接入多个 LLM 服务商。
 * <p>
 * 配置示例 (application.yml):
 * <pre>
 * llm:
 *   providers:
 *     dashscope:
 *       api-key: ${LLM_PROVIDER_DASHSCOPE_API_KEY:}
 *       base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
 *       default-model: qwen3.7-max
 *       models: qwen3.7-max,qwen3.7-plus
 *     deepseek:
 *       api-key: ${LLM_PROVIDER_DEEPSEEK_API_KEY:}
 *       base-url: https://api.deepseek.com/v1
 *       default-model: deepseek-v4-pro
 *       models: deepseek-v4-pro,deepseek-v4-flash
 * </pre>
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProvidersProperties(
        @DefaultValue Map<String, ProviderConfig> providers
) {
    /**
     * 单个 Provider 的配置。
     */
    public record ProviderConfig(
            String apiKey,
            String baseUrl,
            String defaultModel,
            @DefaultValue List<String> models
    ) {
        /** 检查配置是否有效（至少有 API Key 和 Base URL） */
        public boolean isValid() {
            return apiKey != null && !apiKey.isBlank()
                    && baseUrl != null && !baseUrl.isBlank()
                    && models != null && !models.isEmpty();
        }
    }
}
