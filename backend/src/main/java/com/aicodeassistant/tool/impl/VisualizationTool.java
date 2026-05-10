package com.aicodeassistant.tool.impl;

import com.aicodeassistant.service.VisualizationPayloadBuilder;
import com.aicodeassistant.tool.PermissionRequirement;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * VisualizationTool — Auto-Routing 的出口工具。
 *
 * <p>对齐 ZhikunCode 差异化升级方案 v1.5 升级项 C（全栈可视化 Beta）：
 * <ul>
 *   <li>{@link com.aicodeassistant.engine.VisualizationAutoRouter} 调用 Classifier 产生
 *       {@link com.aicodeassistant.engine.VisualizationHint} 后，以该 Tool 为统一出口
 *       发送 visualization 独立消息到前端（与 /visualize 命令走同一 Builder）。</li>
 *   <li>只做 viewType 白名单校验 + params 透传 + {@link VisualizationPayloadBuilder#publish}，
 *       不承担业务数据查询（数据由调用方或前端组件自行拉取）。</li>
 * </ul>
 *
 * <p><b>延迟加载</b>：{@code shouldDefer=true}，不出现在默认 tool prompt 中，仅由
 * {@code VisualizationAutoRouter} 内部调用（LLM 不直接感知此工具）。
 */
@Component
public class VisualizationTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(VisualizationTool.class);

    /** 允许的 viewType 白名单 — 对齐前端 VisualizationMessage switch 分支 */
    private static final Set<String> ALLOWED_VIEW_TYPES = Set.of(
            "git-timeline",
            "schema-viewer",
            "change-impact-graph",
            "code-path-tracer",
            "code-complexity-treemap",
            "api-sequence-diagram",
            "mermaid"
    );

    private final VisualizationPayloadBuilder payloadBuilder;

    public VisualizationTool(VisualizationPayloadBuilder payloadBuilder) {
        this.payloadBuilder = payloadBuilder;
    }

    @Override
    public String getName() {
        return "Visualization";
    }

    @Override
    public String getDescription() {
        return "Publish a visualization payload to the frontend (internal tool for auto-routing). "
                + "Takes a viewType and props, delivers them via the visualization message channel.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "viewType", Map.of(
                                "type", "string",
                                "description", "Target view type, e.g. git-timeline / schema-viewer / mermaid"
                        ),
                        "props", Map.of(
                                "type", "object",
                                "description", "Props passed to the frontend visualization component"
                        )
                ),
                "required", java.util.List.of("viewType")
        );
    }

    @Override
    public String getGroup() {
        return "visualization";
    }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.NONE;
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return true;
    }

    /** Auto-Router 内部工具 — 不对 LLM 暴露 */
    @Override
    public boolean shouldDefer() {
        return true;
    }

    @Override
    public ValidationResult validateInput(ToolInput input, ToolUseContext context) {
        String viewType = input.getString("viewType", null);
        if (viewType == null || viewType.isBlank()) {
            return ValidationResult.invalid("MISSING_VIEW_TYPE", "viewType is required");
        }
        if (!ALLOWED_VIEW_TYPES.contains(viewType)) {
            return ValidationResult.invalid("UNKNOWN_VIEW_TYPE", "Unsupported viewType: " + viewType);
        }
        return ValidationResult.ok();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String viewType = input.getString("viewType", null);
        if (viewType == null || !ALLOWED_VIEW_TYPES.contains(viewType)) {
            return ToolResult.error("Unsupported viewType: " + viewType);
        }
        String sessionId = context != null ? context.sessionId() : null;
        if (sessionId == null || sessionId.isBlank()) {
            return ToolResult.error("sessionId is required to publish visualization");
        }

        Object rawProps = input.getRawData().get("props");
        Map<String, Object> props = rawProps instanceof Map<?, ?> m
                ? copyStringKeyedMap((Map<Object, Object>) m)
                : new LinkedHashMap<>();

        // 按 viewType 做轻量 props 规范化；不做数据查询
        Map<String, Object> normalized = normalizeProps(viewType, props);

        boolean ok = payloadBuilder.publish(sessionId, viewType, normalized);
        if (!ok) {
            log.debug("VisualizationTool publish skipped: sessionId={}, viewType={}", sessionId, viewType);
            return ToolResult.success("Visualization publish skipped (no principal or invalid args).");
        }
        return ToolResult.success("Published visualization: viewType=" + viewType
                + ", propsKeys=" + normalized.keySet());
    }

    // ==================== internal ====================

    /**
     * 按 viewType 做最小化 props 规范化；未识别字段透传。
     * <p>不对缺失字段强制报错：前端组件可自行兜底（如 GitTimeline 无 repoPath 时用当前工作目录）。
     */
    private Map<String, Object> normalizeProps(String viewType, Map<String, Object> props) {
        Map<String, Object> out = new LinkedHashMap<>(props);
        switch (viewType) {
            case "git-timeline" -> out.putIfAbsent("repoPath", Objects.toString(out.get("repoPath"), ""));
            case "schema-viewer" -> out.putIfAbsent("schema", out.getOrDefault("schema", new HashMap<>()));
            case "mermaid" -> out.putIfAbsent("source", Objects.toString(out.get("source"), ""));
            default -> { /* 自治组件 — 前端按 store 拉数据，props 仅作 hint */ }
        }
        return out;
    }

    private Map<String, Object> copyStringKeyedMap(Map<Object, Object> src) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> e : src.entrySet()) {
            if (e.getKey() != null) {
                out.put(e.getKey().toString(), e.getValue());
            }
        }
        return out;
    }
}
