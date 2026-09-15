package com.aicodeassistant.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

/**
 * 视觉模型路由器 — 当用户附带图片但当前模型不支持图片时，
 * 自动选择一个支持视觉输入的目标模型用于本次请求。
 * <p>
 * 路由策略（按优先级）：
 * <ol>
 *   <li>当前模型本身已支持图片：返回 null（无需路由）</li>
 *   <li>优先使用配置的视觉兜底模型（默认百炼 DeepSeek V4.1 Flash）</li>
 *   <li>配置模型不可用时，DeepSeek 系列尝试官方 DeepSeek 视觉模型</li>
 *   <li>同 Provider 下查找首个支持图片的模型</li>
 *   <li>查找其他已配置的视觉模型；均不可用则由调用方提示无可用模型</li>
 * </ol>
 * <p>
 * 路由是单次请求级别行为，不修改会话级模型选择（{@code sessionModels}）。
 */
@Service
public class VisionModelRouter {

    private static final Logger log = LoggerFactory.getLogger(VisionModelRouter.class);

    private final LlmProviderRegistry providerRegistry;
    private final ModelRegistry modelRegistry;

    /** 全局兜底视觉模型 */
    @Value("${app.model.vision-fallback:deepseek-v4.1-flash}")
    private String fallbackVisionModel = "deepseek-v4.1-flash";

    /** DeepSeek 系列专属图片理解兜底模型（V4.1 Flash 原生支持视觉） */
    static final String DEEPSEEK_VISION_MODEL = "deepseek-flash";

    public VisionModelRouter(LlmProviderRegistry providerRegistry, ModelRegistry modelRegistry) {
        this.providerRegistry = providerRegistry;
        this.modelRegistry = modelRegistry;
    }

    /**
     * 为不支持图片的模型查找视觉路由目标。
     *
     * @param currentModel 当前模型 ID
     * @return 目标视觉模型 ID；当前模型已支持图片或没有可用目标时返回 null
     */
    public String resolveVisionModel(String currentModel) {
        ModelCapabilities caps = modelRegistry.getCapabilities(currentModel);
        if (caps.supportsImages()) {
            return null; // 当前模型已支持图片，无需路由
        }

        if (isAvailableVisionModel(fallbackVisionModel)) {
            log.info("Vision route: {} -> {} (configured fallback)", currentModel, fallbackVisionModel);
            return fallbackVisionModel;
        }

        // 1. 首选兜底不可用时，DeepSeek 系列优先保持模型家族一致。可用性必须以 Provider 实际配置为准，
        // 避免只有内置能力声明、但没有配置 DeepSeek API Key 时路由到不可调用模型。
        if (currentModel != null && currentModel.startsWith("deepseek-")
                && isAvailableVisionModel(DEEPSEEK_VISION_MODEL)) {
            log.info("Vision route: {} -> {} (DeepSeek family fallback)",
                    currentModel, DEEPSEEK_VISION_MODEL);
            return DEEPSEEK_VISION_MODEL;
        }

        // 2. 查找同 Provider 下支持图片的模型
        try {
            LlmProvider provider = providerRegistry.getProvider(currentModel);
            if (provider != null) {
                for (String candidateModel : provider.getSupportedModels()) {
                    if (candidateModel.equals(currentModel)) continue;
                    ModelCapabilities candidateCaps = modelRegistry.getCapabilities(candidateModel);
                    if (candidateCaps.supportsImages()) {
                        log.info("Vision route: {} -> {} (same provider)", currentModel, candidateModel);
                        return candidateModel;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("No provider found for model {}, using fallback", currentModel);
        }

        // 3. 只返回已配置且支持图片的模型，避免路由到没有 Key 的模型。
        for (String candidate : providerRegistry.listAvailableModels()) {
            if (isAvailableVisionModel(candidate)) {
                log.info("Vision route: {} -> {} (available fallback)", currentModel, candidate);
                return candidate;
            }
        }
        log.warn("No configured vision model is available (preferred: {})", fallbackVisionModel);
        return null;
    }

    private boolean isAvailableVisionModel(String model) {
        if (model == null || model.isBlank()) return false;
        ModelCapabilities capabilities = modelRegistry.getCapabilities(model);
        if (capabilities == null || !capabilities.supportsImages()) {
            return false;
        }
        try {
            return providerRegistry.getProvider(model) != null;
        } catch (Exception e) {
            log.debug("Vision model {} is not configured", model);
            return false;
        }
    }
}
