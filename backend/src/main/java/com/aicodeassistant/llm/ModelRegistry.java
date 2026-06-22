package com.aicodeassistant.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册表 — 内置模型映射表 + Provider 动态查询 + 用户自定义覆盖。
 */
@Service
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final Map<String, ModelCapabilities> customModels = new ConcurrentHashMap<>();
    private final LlmProviderRegistry providerRegistry;

    // 内置模型映射表
    private static final Map<String, ModelCapabilities> BUILTIN_MODELS = Map.ofEntries(
        // OpenAI
        entry("gpt-5.5",           caps("gpt-5.5",           "GPT-5.5",          128000, 1050000, true, true, true, 10, true, 0.005, 0.03)),
        entry("gpt-5.4-mini",      caps("gpt-5.4-mini",      "GPT-5.4 Mini",     128000, 400000,  true, true, true, 10, true, 0.00075, 0.0045)),
        // Anthropic
        entry("claude-sonnet-4-6", caps("claude-sonnet-4-6", "Claude Sonnet 4.6",  16384, 200000, true, true, true, 10, true, 0.003, 0.015)),
        entry("claude-opus-4-8",          caps("claude-opus-4-8",          "Claude Opus 4.8",  16384, 200000, true, true, true, 10, true, 0.015, 0.075)),
        entry("claude-haiku-4-5", caps("claude-haiku-4-5", "Claude Haiku 4.5", 8192, 200000, true, false, true, 10, true, 0.0008, 0.004)),
         // Anthropic via ZenMux (anthropic/ 前缀 = zenmux 中转，1M ctx · 128K 最大输出，我们保守设 64K)
        entry("anthropic/claude-opus-4.8", caps("anthropic/claude-opus-4.8", "Claude Opus 4.8", 64000, 1000000, true, false, true, 5, true, 0.005, 0.025)),
        entry("anthropic/claude-fable-5", caps("anthropic/claude-fable-5", "Claude Fable 5", 64000, 1000000, true, false, true, 5, true, 0.010, 0.050)),
        // OpenAI via ZenMux (openai/ 前缀 = zenmux 中转)
        entry("openai/gpt-5.5-pro",   caps("openai/gpt-5.5-pro",   "OpenAI GPT-5.5 Pro",   128000, 1050000, true,  false, true, 4, true, 0.030, 0.180)),
        // Google via ZenMux (google/ 前缀 = zenmux 中转)
        entry("google/gemini-3.5-flash",   caps("google/gemini-3.5-flash",   "Google Gemini 3.5 Flash",   65530, 1050000, false, false, true, 4, true, 0.0015, 0.009)),
        // 国产大模型
        entry("deepseek-v4-pro",   caps("deepseek-v4-pro",   "DeepSeek V4 Pro",  384000, 1000000, true, true, false, 0, true, 0.001, 0.004)),
        entry("deepseek-v4-flash", caps("deepseek-v4-flash", "DeepSeek V4 Flash", 384000, 1000000, true, true, false, 0, true, 0.0005, 0.002)),
        // Moonshot
        entry("kimi-k2.6",          caps("kimi-k2.6",          "Kimi K2.6",         16384, 256000,  true, true, true, 8, true, 0.002, 0.012)),
        entry("kimi-k2.7-code",     caps("kimi-k2.7-code",     "Kimi K2.7 Code",    16384, 256000,  true, true, true, 8, true, 0.002, 0.012)),
        entry("moonshot-v1-128k",  caps("moonshot-v1-128k",  "Moonshot V1 128K",   8192, 128000,  true, false, false, 0, true, 0.001, 0.002)),
        entry("qwen-turbo",        caps("qwen-turbo",        "Qwen Turbo",         8192, 1000000,  true, false, false, 0, true, 0.0003, 0.0006)),
        entry("qwen3.7-max", caps("qwen3.7-max", "Qwen 3.7 Max", 65536, 1000000, true, true, false, 0, true, 0.009, 0.054)),
        entry("qwen3.7-plus",      caps("qwen3.7-plus",      "Qwen 3.7 Plus",      8192, 1000000,  true, true, true, 4, true, 0.0008, 0.002)),
        entry("glm-5.2",           caps("glm-5.2",           "GLM-5.2",            131072, 1048576,  true, true, false, 0, true, 0.001, 0.001)),
        entry("glm-5v-turbo",      caps("glm-5v-turbo",      "GLM-5V-Turbo",       131072, 204800,  true, true, true, 150, true, 0.0012, 0.004)),
        // MiniMax
        entry("MiniMax-M3",       caps("MiniMax-M3",       "MiniMax M3",        16384, 1000000, true, true, true, 4, true, 0.001, 0.004)),
        // Ollama 本地
        entry("ollama/*",          caps("ollama/*",          "Ollama Local",       4096,   8192,  true, false, false, 0, false, 0.0, 0.0))
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
            boolean stream, boolean think, boolean img, int maxImages, boolean tool, double in$, double out$) {
        return new ModelCapabilities(id, name, maxOut, ctx, stream, think, img, maxImages, tool, in$, out$);
    }
}
