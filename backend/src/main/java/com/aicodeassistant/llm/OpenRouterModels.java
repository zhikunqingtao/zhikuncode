package com.aicodeassistant.llm;

import java.util.Map;

/** Stable UI/session IDs; only the wire request uses OpenRouter's upstream slug. */
public final class OpenRouterModels {
    public static final String ASTRA = "openrouter/openai/gpt-6-astra";
    public static final String FABLE = "openrouter/anthropic/claude-fable-5.1";
    private static final Map<String, ModelCapabilities> MODELS = Map.of(
            ASTRA, new ModelCapabilities(ASTRA, "GPT-6 Astra（OpenRouter）", 128000, 1050000,
                    true, true, true, 4, true, .010, .050),
            FABLE, new ModelCapabilities(FABLE, "Claude Fable 5.1（OpenRouter）", 128000, 1000000,
                    true, true, true, 4, true, .010, .050));

    private OpenRouterModels() {}

    public static String localId(String model) {
        return MODELS.containsKey("openrouter/" + model) ? "openrouter/" + model : model;
    }

    public static String upstreamId(String model) {
        return MODELS.containsKey(model) ? model.substring("openrouter/".length()) : model;
    }

    public static ModelCapabilities capabilities(String model) { return MODELS.get(model); }
}
