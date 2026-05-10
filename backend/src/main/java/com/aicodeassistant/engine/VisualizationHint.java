package com.aicodeassistant.engine;

import java.util.Map;

/**
 * Visualization Auto-Routing 判别结果 — Classifier 输出契约。
 *
 * <p>对齐 ZhikunCode 差异化升级方案 v1.5 升级项 C（全栈可视化 Beta）：
 * <ul>
 *   <li>{@code viewType}：目标视图类型，对应前端 {@code VisualizationMessage} switch 分支
 *       （如 {@code "git-timeline" / "schema-viewer" / "change-impact-graph" /
 *       "code-path-tracer" / "code-complexity-treemap" / "api-sequence-diagram" / "mermaid"}）</li>
 *   <li>{@code dataSource}：数据来源标识（如 {@code "git-log" / "schema-catalog" / "tool-result"}），
 *       供 {@link com.aicodeassistant.tool.impl.VisualizationTool} 按类型分发</li>
 *   <li>{@code params}：前端/数据源参数（如 {@code repoPath / schema / range}）</li>
 * </ul>
 *
 * <p>静默失败约束：Classifier 解析失败时返回 {@link #EMPTY} 哨兵，调用方据此跳过 Tool 调用。
 */
public record VisualizationHint(
        String viewType,
        String dataSource,
        Map<String, Object> params
) {

    /** 空哨兵 — 表示"不可视化"，Caffeine 缓存可复用此单例避免 null 问题 */
    public static final VisualizationHint EMPTY = new VisualizationHint("", "", Map.of());

    /** 规范化构造：null -> 空字符串/空 Map，便于下游 switch/序列化 */
    public VisualizationHint {
        viewType = viewType == null ? "" : viewType;
        dataSource = dataSource == null ? "" : dataSource;
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** 是否为空哨兵（不可视化） */
    public boolean isEmpty() {
        return viewType.isBlank();
    }
}
