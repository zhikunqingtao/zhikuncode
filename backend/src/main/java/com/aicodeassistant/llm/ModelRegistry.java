package com.aicodeassistant.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册表 — 内置模型映射表 + Provider 动态查询 + 用户自定义覆盖。
 * 对照 SPEC §3.1.3 内置模型能力映射表。
 */
@Service
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final Map<String, ModelCapabilities> customModels = new ConcurrentHashMap<>();
    private final LlmProviderRegistry providerRegistry;

    // 内置模型映射表 — 对照 SPEC §3.1.3
    private static final Map<String, ModelCapabilities> BUILTIN_MODELS = Map.ofEntries(
        // OpenAI
        entry("gpt-4o",            caps("gpt-4o",            "GPT-4o",            16384, 128000,  true, false, true, true, 0.005, 0.015)),
        entry("gpt-4o-mini",       caps("gpt-4o-mini",       "GPT-4o Mini",       16384, 128000,  true, false, true, true, 0.00015, 0.0006)),
        entry("gpt-4-turbo",       caps("gpt-4-turbo",       "GPT-4 Turbo",        4096, 128000,  true, false, true, true, 0.01, 0.03)),
        // Anthropic
        entry("claude-sonnet-4-5", caps("claude-sonnet-4-5", "Claude Sonnet 4.5", 16384, 200000,  true, true, true, true, 0.003, 0.015)),
        entry("claude-opus-4",     caps("claude-opus-4",     "Claude Opus 4",     16384, 200000,  true, true, true, true, 0.015, 0.075)),
        entry("claude-3-5-haiku",  caps("claude-3-5-haiku",  "Claude 3.5 Haiku",   8192, 200000,  true, false, true, true, 0.0008, 0.004)),
        // 国产大模型
        entry("deepseek-chat",     caps("deepseek-chat",     "DeepSeek Chat",      8192,  64000,  true, true, false, true, 0.00027, 0.0011)),
        entry("deepseek-reasoner", caps("deepseek-reasoner", "DeepSeek Reasoner",  8192,  64000,  true, true, false, false, 0.00055, 0.0022)),
        entry("qwen-turbo",        caps("qwen-turbo",        "Qwen Turbo",         8192, 1000000,  true, false, false, true, 0.0003, 0.0006)),
        entry("qwen-plus",         caps("qwen-plus",         "Qwen Plus",         16384, 1000000,  true, false, false, true, 0.0008, 0.002)),
        entry("qwen-max",          caps("qwen-max",          "Qwen Max",          16384,  262144,  true, true, true, true, 0.002, 0.006)),
        entry("qwen3.6-plus",      caps("qwen3.6-plus",      "Qwen 3.6 Plus",      8192, 1000000,  true, false, true, true, 0.0008, 0.002)),
        entry("glm-4",             caps("glm-4",             "GLM-4",              8192, 128000,  true, false, true, true, 0.001, 0.001)),
        // Ollama 本地
        entry("ollama/*",          caps("ollama/*",          "Ollama Local",       4096,   8192,  true, false, false, false, 0.0, 0.0))
    );

    public ModelRegistry(LlmProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
        log.info("ModelRegistry initialized with {} built-in models", BUILTIN_MODELS.size());
    }

    /** 获取模型的 contextWindow — 核心 API */
    public int getContextWindowForModel(String modelId) {
        return getCapabilities(modelId).contextWindow();
    }

    /** 获取模型的 maxOutputTokens */
    public int getMaxOutputTokensForModel(String modelId) {
        return getCapabilities(modelId).maxOutputTokens();
    }

    /**
     * 获取完整模型能力 — 三级查询优先级:
     * 1. 用户自定义覆盖（YAML 配置）
     * 2. Provider 动态查询（已注册的 LlmProvider）
     * 3. 内置映射表（BUILTIN_MODELS）
     * 4. 保守默认值（ModelCapabilities.DEFAULT）
     */
    public ModelCapabilities getCapabilities(String modelId) {
        // Level 1: 用户自定义覆盖
        ModelCapabilities custom = customModels.get(modelId);
        if (custom != null) return custom;

        // Level 2: 从 Provider 动态查询
        // 注意: getProvider() 在无匹配时抛 IllegalArgumentException，不返回 null
        try {
            return providerRegistry.getProvider(modelId).getModelCapabilities(modelId);
        } catch (Exception e) {
            log.trace("No provider for model {}, falling back to built-in", modelId);
        }

        // Level 3: 内置映射表（精确匹配 + 前缀匹配）
        ModelCapabilities builtin = BUILTIN_MODELS.get(modelId);
        if (builtin != null) return builtin;

        // 前缀匹配 (ollama/* 等通配符)
        for (var e : BUILTIN_MODELS.entrySet()) {
            String key = e.getKey();
            if (key.endsWith("/*") && modelId.startsWith(key.substring(0, key.length() - 1))) {
                return e.getValue();
            }
        }

        // Level 4: 保守默认值
        log.debug("Unknown model '{}', using DEFAULT capabilities", modelId);
        return ModelCapabilities.DEFAULT;
    }

    /** 注册自定义模型能力（YAML 配置加载时调用） */
    public void registerCustomModel(ModelCapabilities capabilities) {
        customModels.put(capabilities.modelId(), capabilities);
        log.info("Custom model registered: {}", capabilities.modelId());
    }

    /** 列出所有已知模型（内置 + 自定义 + Provider） */
    public List<ModelCapabilities> listAllModels() {
        Map<String, ModelCapabilities> all = new LinkedHashMap<>(BUILTIN_MODELS);
        all.putAll(customModels);
        // 追加 Provider 注册的模型
        try {
            providerRegistry.listModelCapabilities().forEach(mc -> all.putIfAbsent(mc.modelId(), mc));
        } catch (Exception ignored) {}
        return List.copyOf(all.values());
    }

    // ── 内部工具方法 ──

    private static Map.Entry<String, ModelCapabilities> entry(String k, ModelCapabilities v) {
        return Map.entry(k, v);
    }

    private static ModelCapabilities caps(String id, String name, int maxOut, int ctx,
            boolean stream, boolean think, boolean img, boolean tool, double in$, double out$) {
        return new ModelCapabilities(id, name, maxOut, ctx, stream, think, img, tool, in$, out$);
    }
}
