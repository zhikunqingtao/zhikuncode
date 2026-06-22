package com.aicodeassistant.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 视觉模型路由器 — 当用户附带图片但当前模型不支持图片时，
 * 自动选择一个支持视觉输入的目标模型用于本次请求。
 * <p>
 * 路由策略（按优先级）：
 * <ol>
 *   <li>当前模型本身已支持图片：返回 null（无需路由）</li>
 *   <li>同 Provider 下查找首个支持图片的模型</li>
 *   <li>使用全局兜底视觉模型 {@link #FALLBACK_VISION_MODEL}</li>
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
    private static final String FALLBACK_VISION_MODEL = "qwen3.7-plus";

    public VisionModelRouter(LlmProviderRegistry providerRegistry, ModelRegistry modelRegistry) {
        this.providerRegistry = providerRegistry;
        this.modelRegistry = modelRegistry;
    }

    /**
     * 为不支持图片的模型查找视觉路由目标。
     *
     * @param currentModel 当前模型 ID
     * @return 目标视觉模型 ID；如果当前模型已支持图片则返回 null（无需路由）
     */
    public String resolveVisionModel(String currentModel) {
        ModelCapabilities caps = modelRegistry.getCapabilities(currentModel);
        if (caps.supportsImages()) {
            return null; // 当前模型已支持图片，无需路由
        }

        // 1. 查找同 Provider 下支持图片的模型
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

        // 2. 同 Provider 无视觉模型，使用全局兜底
        log.info("Vision route: {} -> {} (fallback)", currentModel, FALLBACK_VISION_MODEL);
        return FALLBACK_VISION_MODEL;
    }
}
