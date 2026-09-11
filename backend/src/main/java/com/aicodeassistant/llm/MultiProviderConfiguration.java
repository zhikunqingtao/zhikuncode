package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
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

                // 逗号分隔多 Key 通用能力（如 ZenMux 订阅 key sk-ss-v1- 优先 + 按量 key 兜底）：
                // 拆分后传给 ApiKeyRotationManager，首个 key 作为构造 provider 的 fallback apiKey。
                // 单 key 配置零行为变化（单 key 时 getNextKey() 返回唯一 key）。
                List<String> apiKeys = splitApiKeys(config.apiKey());
                ApiKeyRotationManager keyManager = new ApiKeyRotationManager(apiKeys);
                OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                        name, objectMapper, httpProperties, keyManager,
                        apiKeys.isEmpty() ? null : apiKeys.getFirst(), config.baseUrl(),
                        config.defaultModel(), config.models(), payloadGuard);
                providers.add(provider);
                log.info("Created multi-provider '{}': baseUrl={}, models={}, apiKeys={}",
                        name, config.baseUrl(), config.models(), apiKeys.size());
            }
        }

        if (providers.isEmpty()) {
            // ===== 向后兼容: llm.openai.* =====
            if (legacyApiKey != null && !legacyApiKey.isBlank()) {
                List<String> legacyApiKeys = splitApiKeys(legacyApiKey);
                ApiKeyRotationManager keyManager = new ApiKeyRotationManager(legacyApiKeys);
                OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                        "openai-compatible", objectMapper, httpProperties, keyManager,
                        legacyApiKeys.isEmpty() ? null : legacyApiKeys.getFirst(), legacyBaseUrl,
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

    /**
     * 拆分逗号分隔的 API Key 列表（trim、去空、去重、保持首次出现顺序）。
     * <p>
     * 支持同一 Provider 配置多把 key（如 ZenMux 订阅 key sk-ss-v1- 优先 + 按量 key 兜底）；
     * 单 key 配置拆分后仍为单元素列表，与单 key 构造器语义完全一致；
     * 去重避免重复 key 使 getKeyCount 虚高误触发多 key 冷却分支。
     * <p>
     * 包可见性：同包测试（MultiApiKeySplitTest）直接验证拆分逻辑。
     */
    static List<String> splitApiKeys(String rawApiKeys) {
        if (rawApiKeys == null || rawApiKeys.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rawApiKeys.split(","))
                .map(String::trim)
                .filter(key -> !key.isEmpty())
                .distinct()
                .toList();
    }
}
