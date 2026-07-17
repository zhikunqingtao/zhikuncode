package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 多 Provider 自动配置 — 根据配置创建 LLM Provider Bean。
 * <p>
 * 优先读取 {@code llm.providers.*}（多 Provider 模式），
 * 若未配置则回退到 {@code llm.openai.*}（单 Provider 向后兼容模式）。
 * <p>
 * 每个 Provider 实例持有独立的 API Key、Base URL 和 ApiKeyRotationManager。
 */
@Configuration
public class MultiProviderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MultiProviderConfiguration.class);

    /**
     * 创建所有 OpenAI 兼容 Provider 实例。
     * <p>
     * Spring 会将返回的 List 中的元素注册为独立 Bean，
     * 由 LlmProviderRegistry 通过构造器注入 List&lt;LlmProvider&gt; 自动发现。
     */
    @Bean
    public List<LlmProvider> openAiCompatibleProviders(
            LlmProvidersProperties properties,
            LlmHttpProperties httpProperties,
            ObjectMapper objectMapper,
            @Value("${llm.openai.api-key:}") String legacyApiKey,
            @Value("${llm.openai.base-url:https://api.openai.com/v1}") String legacyBaseUrl,
            @Value("${llm.openai.default-model:gpt-4o}") String legacyDefaultModel,
            @Value("${llm.openai.models:gpt-4o,gpt-4o-mini,gpt-4-turbo}") List<String> legacyModels,
            FinalProviderPayloadGuard payloadGuard) {

        List<LlmProvider> providers = new ArrayList<>();

        Map<String, LlmProvidersProperties.ProviderConfig> providerConfigs = properties.providers();

        if (providerConfigs != null && !providerConfigs.isEmpty()) {
            // ===== 新模式: llm.providers.* =====
            for (var entry : providerConfigs.entrySet()) {
                String name = entry.getKey();
                LlmProvidersProperties.ProviderConfig config = entry.getValue();

                if (!config.isValid()) {
                    log.warn("Skipping invalid provider config '{}': apiKey={}, baseUrl={}, models={}",
                            name,
                            config.apiKey() != null ? "set" : "missing",
                            config.baseUrl() != null ? config.baseUrl() : "missing",
                            config.models());
                    continue;
                }

                ApiKeyRotationManager keyManager = new ApiKeyRotationManager(config.apiKey());
                OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                        name, objectMapper, httpProperties, keyManager,
                        config.apiKey(), config.baseUrl(),
                        config.defaultModel(), config.models(), payloadGuard);
                providers.add(provider);
                log.info("Created multi-provider '{}': baseUrl={}, models={}",
                        name, config.baseUrl(), config.models());
            }
        }

        if (providers.isEmpty()) {
            // ===== 向后兼容: llm.openai.* =====
            if (legacyApiKey != null && !legacyApiKey.isBlank()) {
                ApiKeyRotationManager keyManager = new ApiKeyRotationManager(legacyApiKey);
                OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                        "openai-compatible", objectMapper, httpProperties, keyManager,
                        legacyApiKey, legacyBaseUrl,
                        legacyDefaultModel, legacyModels, payloadGuard);
                providers.add(provider);
                log.info("Created legacy single-provider: baseUrl={}, models={}",
                        legacyBaseUrl, legacyModels);
            } else {
                log.warn("No LLM provider configured! Set llm.providers.* or llm.openai.* in application.yml");
            }
        }

        log.info("MultiProviderConfiguration: {} provider(s) created", providers.size());
        return providers;
    }
}
