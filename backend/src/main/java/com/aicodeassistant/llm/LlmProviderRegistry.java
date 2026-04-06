package com.aicodeassistant.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * LLM Provider 注册表 — 管理多供应商实例。
 * <p>
 * 根据模型名称查找对应供应商，支持动态注册。
 *
 * @see <a href="SPEC §3.1.3">流式处理</a>
 */
@Service
public class LlmProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderRegistry.class);

    private final Map<String, LlmProvider> providers = new ConcurrentHashMap<>();

    /** 根据模型名称查找对应供应商 */
    public LlmProvider getProvider(String model) {
        return providers.values().stream()
                .filter(p -> p.getSupportedModels().contains(model))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No provider found for model: " + model));
    }

    /** 注册供应商 */
    public void register(LlmProvider provider) {
        providers.put(provider.getProviderName(), provider);
        log.info("Registered LLM provider: {} (models: {})",
                provider.getProviderName(), provider.getSupportedModels());
    }

    /** 列出所有可用模型 */
    public List<String> listAvailableModels() {
        return providers.values().stream()
                .flatMap(p -> p.getSupportedModels().stream())
                .toList();
    }

    /** 列出所有可用模型及其能力 */
    public List<ModelCapabilities> listModelCapabilities() {
        return providers.values().stream()
                .flatMap(p -> p.getSupportedModels().stream()
                        .map(p::getModelCapabilities))
                .toList();
    }

    /** 获取全局默认模型 */
    public String getDefaultModel() {
        return providers.values().stream()
                .findFirst()
                .map(LlmProvider::getDefaultModel)
                .orElse("gpt-4o");
    }

    /** 获取快速模型 — 用于分类器/摘要 */
    public String getFastModel() {
        return providers.values().stream()
                .map(LlmProvider::getFastModel)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(getDefaultModel());
    }

    /** 检查是否有任何 Provider 已注册 */
    public boolean hasProviders() {
        return !providers.isEmpty();
    }
}
