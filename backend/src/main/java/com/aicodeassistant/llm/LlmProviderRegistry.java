package com.aicodeassistant.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Environment env;

    @Value("${classifier.model:}")
    private String classifierModel;

    /** 内置别名映射（Claude 系列 → 实际部署模型） */
    private static final Map<String, String> BUILTIN_ALIASES = Map.of(
            "haiku", "qwen-plus",
            "sonnet", "qwen3.6-plus",
            "opus", "qwen-max",
            "claude-haiku", "qwen-plus",
            "claude-sonnet", "qwen3.6-plus",
            "claude-opus", "qwen-max"
    );

    /**
     * 构造函数 — 通过 Spring 自动注入所有 LlmProvider 实现，自动注册。
     */
    public LlmProviderRegistry(List<LlmProvider> providerList, Environment env) {
        this.env = env;
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
     * 解析模型别名为实际模型名称。
     * <p>
     * 别名映射规则（四级回退）：
     * 1. 环境变量 AGENT_MODEL_<ALIAS> (如 AGENT_MODEL_HAIKU=qwen-plus)
     * 2. application.yml 配置 agent.model-aliases.<alias>
     * 3. 内置映射表（haiku→轻量模型, sonnet→默认模型, opus→旗舰模型）
     * 4. 直接使用别名作为模型名（透传）
     */
    public String resolveModelAlias(String modelNameOrAlias) {
        if (modelNameOrAlias == null || modelNameOrAlias.isBlank()) {
            log.debug("resolveModelAlias: input is null/blank, falling back to default model: {}", getDefaultModel());
            return getDefaultModel();
        }

        // Level 1: 环境变量覆盖
        String envKey = "AGENT_MODEL_" + modelNameOrAlias.toUpperCase().replace("-", "_");
        String envModel = System.getenv(envKey);
        if (envModel != null && !envModel.isBlank()) {
            log.debug("resolveModelAlias: '{}' resolved via env var {}={}", modelNameOrAlias, envKey, envModel);
            return envModel;
        }

        // Level 2: application.yml 配置映射
        String configModel = env.getProperty("agent.model-aliases." + modelNameOrAlias.toLowerCase());
        if (configModel != null && !configModel.isBlank()) {
            log.debug("resolveModelAlias: '{}' resolved via config={}", modelNameOrAlias, configModel);
            return configModel;
        }

        // Level 3: 内置别名映射（Claude → 实际部署模型）
        String builtinModel = BUILTIN_ALIASES.get(modelNameOrAlias.toLowerCase());
        if (builtinModel != null) {
            log.debug("resolveModelAlias: '{}' resolved via builtin alias={}", modelNameOrAlias, builtinModel);
            return builtinModel;
        }

        // Level 4: 直接返回，尝试作为模型名使用
        log.debug("resolveModelAlias: '{}' used as-is (no alias match)", modelNameOrAlias);
        return modelNameOrAlias;
    }

    /** 获取内置别名列表（供 AgentTool 动态构建模型枚举） */
    public List<String> getBuiltinAliases() {
        return List.of("haiku", "sonnet", "opus");
    }

    /**
     * 检查指定模型名称是否有对应的 Provider 可用。
     */
    public boolean hasProvider(String modelName) {
        if (modelName == null || modelName.isBlank()) return false;
        try {
            getProvider(modelName);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 四级回退解析分类器模型。
     * 对齐原版 resolveClassifierModel():
     * 1. 环境变量 CLASSIFIER_MODEL
     * 2. application.yml classifier.model
     * 3. 轻量级模型 (getLightweightModel)
     * 4. 默认模型 (getDefaultModel) 兆底
     */
    public String resolveClassifierModel() {
        // Level 1: 环境变量
        String envModel = System.getenv("CLASSIFIER_MODEL");
        if (envModel != null && !envModel.isBlank() && hasProvider(envModel)) {
            log.debug("resolveClassifierModel: resolved via env CLASSIFIER_MODEL={}", envModel);
            return envModel;
        }

        // Level 2: Spring 配置文件 classifier.model
        if (classifierModel != null && !classifierModel.isBlank() && hasProvider(classifierModel)) {
            log.debug("resolveClassifierModel: resolved via config classifier.model={}", classifierModel);
            return classifierModel;
        }

        // Level 3: 轻量级模型
        String lightweight = getLightweightModel();
        if (lightweight != null && hasProvider(lightweight)) {
            log.debug("resolveClassifierModel: resolved via lightweight model={}", lightweight);
            return lightweight;
        }

        // Level 4: 默认模型兆底
        String defaultModel = getDefaultModel();
        log.debug("resolveClassifierModel: fallback to default model={}", defaultModel);
        return defaultModel;
    }
}
