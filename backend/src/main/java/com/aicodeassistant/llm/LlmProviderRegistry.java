package com.aicodeassistant.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM Provider 注册表 — 管理多供应商实例。
 * <p>
 * 根据模型名称查找对应供应商，支持动态注册。
 *
 */
@Service
public class LlmProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderRegistry.class);

    private final Map<String, LlmProvider> providers = Collections.synchronizedMap(new java.util.LinkedHashMap<>());
    private final Environment env;
    private final Set<String> warnedInvalidDefaults = ConcurrentHashMap.newKeySet();
    private final Set<String> warnedInvalidTierAliases = ConcurrentHashMap.newKeySet();

    @Value("${classifier.model:}")
    private String classifierModel;

    @Value("${app.model.default:qwen3.8-max-0902}")
    private String configuredDefaultModel;

    /** 内置别名映射（模型层级别名 → 实际部署模型） */
    private static final Map<String, String> BUILTIN_ALIASES = Map.ofEntries(
            // 统一指向最强模型，取消自动降级
            Map.entry("light", "qwen3.8-max-0902"),
            Map.entry("standard", "qwen3.8-max-0902"),
            Map.entry("premium", "qwen3.8-max-0902")
    );

    /**
     * 构造函数 — 通过 Spring 注入 MultiProviderConfiguration 创建的 Provider 列表。
     */
    public LlmProviderRegistry(
            @Qualifier("openAiCompatibleProviders") List<LlmProvider> providerList,
            Environment env) {
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

    /** 列出所有可用模型 — 默认模型排在最前 */
    public List<String> listAvailableModels() {
        String defaultModel = getDefaultModel();
        List<String> all = providers.values().stream()
                .flatMap(p -> p.getSupportedModels().stream())
                .distinct()
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        // 将默认模型移到列表最前面
        if (defaultModel != null && all.remove(defaultModel)) {
            all.add(0, defaultModel);
        }
        return List.copyOf(all);
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

    /**
     * 获取全局默认模型。
     * <p>
     * 配置值只有在已注册 Provider 确实支持时才生效；陈旧配置会回退到
     * Provider 的有效默认模型（再回退到首个可用模型），避免向客户端发布
     * 一个无法调用的默认模型。
     */
    public String getDefaultModel() {
        if (supportsModel(configuredDefaultModel)) {
            return configuredDefaultModel;
        }

        List<LlmProvider> snapshot = providerSnapshot();
        String fallback = snapshot.stream()
                .map(LlmProvider::getDefaultModel)
                .filter(this::supportsModel)
                .findFirst()
                .orElseGet(() -> snapshot.stream()
                        .flatMap(provider -> provider.getSupportedModels().stream())
                        .filter(model -> model != null && !model.isBlank())
                        .findFirst()
                        .orElse(null));

        if (fallback != null) {
            if (configuredDefaultModel != null && !configuredDefaultModel.isBlank()
                    && warnedInvalidDefaults.add(configuredDefaultModel)) {
                log.warn("Configured default model '{}' is unavailable; using '{}'",
                        configuredDefaultModel, fallback);
            }
            return fallback;
        }

        // 启动早期或测试环境可能尚无 Provider；保留非空返回契约。
        return configuredDefaultModel != null && !configuredDefaultModel.isBlank()
                ? configuredDefaultModel : "qwen3.8-max-0902";
    }

    /** 获取快速模型 — 用于分类器/摘要 */
    public String getFastModel() {
        return providerSnapshot().stream()
                .map(LlmProvider::getFastModel)
                .filter(Objects::nonNull)
                .filter(this::supportsModel)
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
     * 1. 环境变量 AGENT_MODEL_<ALIAS> (如 AGENT_MODEL_LIGHT=qwen3.7-plus)
     * 2. application.yml 配置 agent.model-aliases.<alias>
     * 3. 内置映射表（light→轻量模型, standard→默认模型, premium→旗舰模型）
     * 4. 显式模型 ID 按既有契约直接透传
     * <p>
     * 内置 tier 别名的目标未部署时回退到当前有效默认模型；需要严格
     * 校验用户输入的入口应再调用 {@link #supportsModel(String)}。
     */
    public String resolveModelAlias(String modelNameOrAlias) {
        if (modelNameOrAlias == null || modelNameOrAlias.isBlank()) {
            return getDefaultModel();
        }

        String normalized = modelNameOrAlias.toLowerCase();
        boolean tierAlias = BUILTIN_ALIASES.containsKey(normalized);

        // Level 1: 环境变量覆盖
        String envKey = "AGENT_MODEL_" + modelNameOrAlias.toUpperCase().replace("-", "_");
        String envModel = System.getenv(envKey);
        if (envModel != null && !envModel.isBlank()) {
            log.debug("resolveModelAlias: '{}' resolved via env var {}={}", modelNameOrAlias, envKey, envModel);
            return fallbackUnavailableTier(modelNameOrAlias, envModel, tierAlias);
        }

        // Level 2: application.yml 配置映射
        String configModel = env.getProperty("agent.model-aliases." + normalized);
        if (configModel != null && !configModel.isBlank()) {
            log.debug("resolveModelAlias: '{}' resolved via config={}", modelNameOrAlias, configModel);
            return fallbackUnavailableTier(modelNameOrAlias, configModel, tierAlias);
        }

        // Level 3: 内置别名映射（模型别名 → 实际部署模型）
        String builtinModel = BUILTIN_ALIASES.get(normalized);
        if (builtinModel != null) {
            log.debug("resolveModelAlias: '{}' resolved via builtin alias={}", modelNameOrAlias, builtinModel);
            return fallbackUnavailableTier(modelNameOrAlias, builtinModel, true);
        }

        // Level 4: 保留通用调用方的既有契约，直接透传显式模型 ID。
        // Session/WebSocket 等用户输入边界会另行按 Provider 校验。
        return modelNameOrAlias;
    }

    private String fallbackUnavailableTier(String requested, String resolved,
                                           boolean tierAlias) {
        if (!tierAlias || supportsModel(resolved)) {
            return resolved;
        }

        String fallback = getDefaultModel();
        if (supportsModel(fallback)) {
            String warningKey = String.valueOf(requested) + "->" + String.valueOf(resolved)
                    + "->" + fallback;
            if (warnedInvalidTierAliases.add(warningKey)) {
                log.warn("Model tier alias '{}' resolved to unavailable model '{}'; using active default '{}'",
                        requested, resolved, fallback);
            }
            return fallback;
        }

        return resolved;
    }

    /** 获取内置别名列表（供 AgentTool 动态构建模型枚举） */
    public List<String> getBuiltinAliases() {
        return List.of("light", "standard", "premium");
    }

    /**
     * 检查指定模型名称是否有对应的 Provider 可用。
     */
    public boolean hasProvider(String modelName) {
        return supportsModel(modelName);
    }

    /** 指定模型是否由当前已注册 Provider 明确支持。 */
    public boolean supportsModel(String modelName) {
        if (modelName == null || modelName.isBlank()) return false;
        return providerSnapshot().stream()
                .anyMatch(provider -> provider.getSupportedModels().contains(modelName));
    }

    private List<LlmProvider> providerSnapshot() {
        synchronized (providers) {
            return List.copyOf(providers.values());
        }
    }

    /**
     * 四级回退解析分类器模型。
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
