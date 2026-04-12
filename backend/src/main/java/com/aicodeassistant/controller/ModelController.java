package com.aicodeassistant.controller;

import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.llm.ModelCapabilities;
import com.aicodeassistant.llm.ModelRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 模型管理 Controller — 列出可用 LLM 模型。
 *
 * @see <a href="SPEC §6.1.8 #20">GET /api/models</a>
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

    /** 列出所有可用模型及其能力信息 */
    @GetMapping
    public ResponseEntity<ModelListResponse> listModels() {
        // 使用 ModelRegistry.listAllModels() — 包含内置 + 自定义 + Provider 注册的完整模型列表
        List<ModelInfo> models = modelRegistry.listAllModels().stream()
                .map(mc -> new ModelInfo(
                        mc.modelId(),
                        mc.displayName(),
                        mc.maxOutputTokens(),
                        mc.contextWindow(),
                        mc.supportsStreaming(),
                        mc.supportsThinking(),
                        mc.supportsImages(),
                        mc.supportsToolUse(),
                        mc.costPer1kInput(),
                        mc.costPer1kOutput()))
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
