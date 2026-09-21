package com.aicodeassistant.llm;

/** Provider-qualified IDs keep Token Plan models separate from direct providers. */
public final class BailianTokenPlanModels {
    public static final String GLM_53 = "bailian/glm-5.3";
    // 2026-09-21: https://help.aliyun.com/zh/model-studio/glm
    // Context: https://help.aliyun.com/zh/model-studio/openclaw
    // 65536 is the application output budget, accepted by the subscription endpoint.
    // Zero token cost represents subscription accounting, not free usage.
    private static final ModelCapabilities GLM_53_CAPABILITIES = new ModelCapabilities(
            GLM_53, "GLM-5.3（百炼）", 65536, 1000000,
            true, true, false, 0, true, 0.0, 0.0, 3.5, true);

    private BailianTokenPlanModels() {}

    public static String localId(String model) {
        return "glm-5.3".equals(model) ? GLM_53 : model;
    }

    public static String upstreamId(String model) {
        return GLM_53.equals(model) ? "glm-5.3" : model;
    }

    public static ModelCapabilities capabilities(String model) {
        return GLM_53.equals(model) ? GLM_53_CAPABILITIES : null;
    }
}
