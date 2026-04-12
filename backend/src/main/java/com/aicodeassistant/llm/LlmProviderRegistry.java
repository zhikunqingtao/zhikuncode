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

    /**
     * 构造函数 — 通过 Spring 自动注入所有 LlmProvider 实现，自动注册。
     */
    public LlmProviderRegistry(List<LlmProvider> providerList) {
        for (LlmProvider provider : providerList) {
            register(provider);
        }
        log.info("LlmProviderRegistry initialized with {} providers: {}",
                this.providers.size(), this.providers.keySet());
    }

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
                        .map(model -> {
                            try {
                                return p.getModelCapabilities(model);
                            } catch (Exception e) {
                                log.debug("Skipping model '{}' from provider '{}': {}",
                                        model, p.getProviderName(), e.getMessage());
                                return null;
                            }
                        }))
                .filter(java.util.Objects::nonNull)
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

    /** 获取轻量级模型 — 用于分类器、摘要等低延迟场景 (SPEC: YoloClassifier) */
    public String getLightweightModel() {
        return getFastModel();
    }

    /** 获取主循环模型 — 用于核心对话循环 (SPEC: MainLoop) */
    public String getMainLoopModel() {
        return getDefaultModel();
    }

    /**
     * 四级回退解析分类器模型。
     * 对齐原版 resolveClassifierModel():
     * env → Spring配置 → getFastModel → getDefaultModel
     */
    public String resolveClassifierModel() {
        // Level 1: 环境变量
        String envModel = System.getenv("CLASSIFIER_MODEL");
        if (envModel != null && !envModel.isBlank()) return envModel;

        // Level 2: 轻量模型
        String fast = getFastModel();
        if (fast != null) return fast;

        // Level 3: 主循环模型
        return getDefaultModel();
    }
}
