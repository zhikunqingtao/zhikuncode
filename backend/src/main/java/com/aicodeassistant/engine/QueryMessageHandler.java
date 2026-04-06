package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.Usage;

/**
 * 查询循环消息处理器 — 流式事件分发接口。
 * <p>
 * 源码使用 AsyncGenerator yield，Java 使用事件监听器模式替代。
 * 实现者通过 WebSocket 将事件推送到前端。
 *
 * @see <a href="SPEC §3.1.1">核心流程</a>
 */
public interface QueryMessageHandler {

    /** 文本增量 — 流式推送到前端 */
    void onTextDelta(String text);

    /** 思考增量 — 流式推送到前端 */
    default void onThinkingDelta(String thinking) {}

    /** 工具调用开始 */
    void onToolUseStart(String toolUseId, String toolName);

    /** 工具参数增量 */
    default void onToolInputDelta(String toolUseId, String jsonDelta) {}

    /** 工具调用完成 */
    void onToolUseComplete(String toolUseId, ContentBlock.ToolUseBlock toolUse);

    /** 工具执行结果 */
    void onToolResult(String toolUseId, ContentBlock.ToolResultBlock result);

    /** 助手消息完成 */
    void onAssistantMessage(Message.AssistantMessage message);

    /** 流式事件 — API 层流控信号 */
    default void onStreamEvent(String eventType, Object payload) {}

    /** 系统消息 (如 compact_boundary) */
    default void onSystemMessage(Message.SystemMessage message) {}

    /** 使用量统计 */
    default void onUsage(Usage usage) {}

    /** 循环迭代开始 */
    default void onTurnStart(int turnNumber) {}

    /** 循环迭代结束 */
    default void onTurnEnd(int turnNumber, String stopReason) {}

    /** 压缩事件 */
    default void onCompactEvent(String type, int beforeTokens, int afterTokens) {}

    /** 错误事件 */
    default void onError(Throwable error) {}

    // ==================== P1 消息类型 ====================

    default void onTombstone(String targetMessageId) {}
    default void onProgress(String toolUseId, String progressText) {}
    default void onStreamRequestStart(String requestId) {}
    default void onToolUseSummary(String toolUseId, String summary) {}
}
