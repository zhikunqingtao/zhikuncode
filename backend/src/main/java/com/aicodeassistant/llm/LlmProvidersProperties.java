package com.aicodeassistant.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
 *       default-model: qwen3.8-max-0902
 *       models: qwen3.8-max-0902
 *     dashscope-token-plan:
 *       api-key: ${LLM_PROVIDER_DASHSCOPE_TOKEN_PLAN_API_KEY:}
 *       base-url: https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
 *       default-model: deepseek-v4.1-flash
 *       models: deepseek-v4.1-flash,bailian/glm-5.3
 *     deepseek:
 *       api-key: ${LLM_PROVIDER_DEEPSEEK_API_KEY:}
 *       base-url: https://api.deepseek.com/v1
 *       default-model: deepseek-flash
 *       models: deepseek-flash
 * </pre>
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProvidersProperties(
        @DefaultValue Map<String, ProviderConfig> providers
) {
    private static final Set<String> REMOVED_MODELS = Set.of(
            "qwen3.8-max", "qwen3.8-flash", "kimi-for-coding", "glm-5.3-flash", "openai/gpt-5.6-sol");

    static boolean isRemovedModel(String id) {
        return id != null && REMOVED_MODELS.contains(id);
    }

    /**
     * 单个 Provider 的配置。
     */
    public record ProviderConfig(
            String apiKey,
            String baseUrl,
            String defaultModel,
            @DefaultValue List<String> models,
            @DefaultValue("ROUND_ROBIN") ApiKeyRotationManager.KeySelectionStrategy keySelectionStrategy
    ) {
        @ConstructorBinding
        public ProviderConfig {
            if (models != null) {
                models = models.stream().filter(id -> !isRemovedModel(id)).toList();
                if (isRemovedModel(defaultModel)) {
                    defaultModel = models.isEmpty() ? null : models.getFirst();
                }
            }
            if (keySelectionStrategy == null) {
                keySelectionStrategy = ApiKeyRotationManager.KeySelectionStrategy.ROUND_ROBIN;
            }
        }

        public ProviderConfig(String apiKey, String baseUrl, String defaultModel, List<String> models) {
            this(apiKey, baseUrl, defaultModel, models,
                    ApiKeyRotationManager.KeySelectionStrategy.ROUND_ROBIN);
        }

        /** 检查配置是否有效（至少有 API Key 和 Base URL） */
        public boolean isValid() {
            return apiKey != null && !apiKey.isBlank()
                    && baseUrl != null && !baseUrl.isBlank()
                    && models != null && !models.isEmpty();
        }
    }
}
