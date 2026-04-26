package com.aicodeassistant.controller;

import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.llm.ModelCapabilities;
import com.aicodeassistant.llm.ModelRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 模型管理 Controller — 列出可用 LLM 模型。
 *
 */
@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final LlmProviderRegistry providerRegistry;
    private final ModelRegistry modelRegistry;

    public ModelController(LlmProviderRegistry providerRegistry, ModelRegistry modelRegistry) {
        this.providerRegistry = providerRegistry;
        this.modelRegistry = modelRegistry;
    }

    /** 列出所有已配置的可用模型及其能力信息 */
    @GetMapping
    public ResponseEntity<ModelListResponse> listModels(
            @RequestParam(required = false) String modelId) {
        // 新增：单个模型查询时验证存在性
        if (modelId != null && !modelId.isBlank()) {
            ModelCapabilities mc = modelRegistry.getCapabilities(modelId);
            if (mc == ModelCapabilities.DEFAULT) {
                throw new IllegalArgumentException("Invalid model: " + modelId);
            }
        }
        // 优先使用 Provider 注册的模型（实际可用），通过 ModelRegistry 补充能力信息
        List<ModelInfo> models = providerRegistry.listAvailableModels().stream()
                .map(id -> {
                    ModelCapabilities mc = modelRegistry.getCapabilities(id);
                    return new ModelInfo(
                            mc.modelId(),
                            mc.displayName(),
                            mc.maxOutputTokens(),
                            mc.contextWindow(),
                            mc.supportsStreaming(),
                            mc.supportsThinking(),
                            mc.supportsImages(),
                            mc.supportsToolUse(),
                            mc.costPer1kInput(),
                            mc.costPer1kOutput());
                })
                .toList();
        return ResponseEntity.ok(new ModelListResponse(
                models, providerRegistry.getDefaultModel()));
    }

    // ═══ DTO Records ═══
    public record ModelListResponse(List<ModelInfo> models, String defaultModel) {}
    public record ModelInfo(
            String id, String displayName,
            int maxOutputTokens, int contextWindow,
            boolean supportsStreaming, boolean supportsThinking,
            boolean supportsImages, boolean supportsToolUse,
            double costPer1kInput, double costPer1kOutput) {}
}
