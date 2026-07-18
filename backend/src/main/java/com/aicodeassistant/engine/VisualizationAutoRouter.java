package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.StreamingToolExecutor;
import com.aicodeassistant.tool.impl.VisualizationTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Visualization Auto-Router — QueryEngine 与 Classifier/Tool 之间的适配器。
 *
 * <p>对齐 ZhikunCode 差异化升级方案 v1.5 升级项 C（全栈可视化 Beta）：
 * <ul>
 *   <li>{@link com.aicodeassistant.engine.QueryEngine#queryLoop} 每轮 {@code onTurnStart} 之后调用一次</li>
 *   <li>默认关闭时直接 return（零开销）；开启后由 Classifier 三道闸门决策</li>
 *   <li>命中则调用 {@link VisualizationTool#call} 发送 visualization 消息（独立消息路线，不进入 assistant 上下文）</li>
 * </ul>
 *
 * <p><b>黄金守则</b>：任何异常/失败都只记 debug 日志，不抛出、不影响主循环。
 */
@Component
public class VisualizationAutoRouter {

    private static final Logger log = LoggerFactory.getLogger(VisualizationAutoRouter.class);

    private final VisualizationIntentClassifier classifier;
    private final VisualizationTool visualizationTool;
    private final boolean autoRoutingEnabled;
    private final StreamingToolExecutor toolExecutor;

    public VisualizationAutoRouter(
            VisualizationIntentClassifier classifier,
            VisualizationTool visualizationTool,
            StreamingToolExecutor toolExecutor,
            @Value("${visualization.auto-routing.enabled:false}") boolean autoRoutingEnabled
    ) {
        this.classifier = classifier;
        this.visualizationTool = visualizationTool;
        this.toolExecutor = toolExecutor;
        this.autoRoutingEnabled = autoRoutingEnabled;
    }

    /**
     * QueryEngine 每轮调用入口 — 默认关闭时立即返回，零开销。
     *
     * @param sessionId 会话 ID（可为 null，内部静默跳过）
     * @param state     当前循环状态（用于抽取 user 问题与最近工具执行）
     */
    public void maybeRoute(String sessionId, QueryLoopState state) {
        if (!autoRoutingEnabled) return;
        if (sessionId == null || sessionId.isBlank()) return;
        if (state == null) return;

        try {
            List<Message> messages = state.getMessages();
            String userQuestion = extractLatestUserQuestion(messages);
            if (userQuestion == null || userQuestion.isBlank()) return;

            VisualizationHint hint = classifier.classify(sessionId, userQuestion, messages);
            if (hint == null || hint.isEmpty()) return;

            // 调 Tool（Tool 内部自行调 VisualizationPayloadBuilder.publish）
            Map<String, Object> toolInput = new LinkedHashMap<>();
            toolInput.put("viewType", hint.viewType());
            toolInput.put("props", hint.params() != null ? hint.params() : Map.of());
            ToolUseContext base = state.getToolUseContext();
            if (base == null || base.currentRunId() == null) return;
            ToolUseContext ctx = base.withToolUseId("visualization-auto-" + java.util.UUID.randomUUID());
            ToolResult result = toolExecutor.executeDetached(visualizationTool, ToolInput.from(toolInput),
                    ctx.toolUseId(), ctx).result();
            if (result != null && result.isError()) {
                log.debug("VisualizationAutoRouter tool returned error: {}", result.content());
            }
        } catch (RuntimeException e) {
            log.debug("VisualizationAutoRouter.maybeRoute swallowed exception: {}", e.getMessage());
        }
    }

    // ==================== internal ====================

    /**
     * 倒序查找最近一条 UserMessage 中的首个 TextBlock 作为用户问题。
     * ToolResultBlock 不算用户问题。
     */
    private String extractLatestUserQuestion(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m instanceof Message.UserMessage um) {
                for (ContentBlock cb : um.content()) {
                    if (cb instanceof ContentBlock.TextBlock tb) {
                        String text = tb.text();
                        if (text != null && !text.isBlank()) {
                            return text.strip();
                        }
                    }
                }
            }
        }
        return null;
    }
}
