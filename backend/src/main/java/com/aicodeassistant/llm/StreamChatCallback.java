package com.aicodeassistant.llm;

/**
 * 流式聊天回调接口 — 替代 Reactor Flux 的事件通知机制。
 * <p>
 * 【架构裁决 #1】Spring MVC + Virtual Threads 下，
 * 使用回调模式接收 SSE 事件，方法阻塞直到流结束。
 *
 * @see <a href="SPEC §3.1.3">流式处理</a>
 */
public interface StreamChatCallback {

    /** 收到流式事件（TextDelta/ToolUseStart/ThinkingDelta 等） */
    void onEvent(LlmStreamEvent event);

    /** 流正常结束 */
    void onComplete();

    /** 流异常终止 */
    void onError(Throwable error);
}
