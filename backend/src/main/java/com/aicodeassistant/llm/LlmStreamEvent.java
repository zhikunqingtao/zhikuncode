package com.aicodeassistant.llm;

import com.aicodeassistant.model.Usage;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 统一的 LLM 流式事件 — 屏蔽各供应商差异。
 * <p>
 * OpenAI SSE 格式 → 统一 LlmStreamEvent:
 * <ul>
 *   <li>data: {"choices":[{"delta":{"content":"..."}}]} → TextDelta</li>
 *   <li>data: {"choices":[{"delta":{"tool_calls":[...]}}]} (首个含 name) → ToolUseStart</li>
 *   <li>data: {"choices":[{"delta":{"tool_calls":[...]}}]} (后续含 arguments) → ToolInputDelta</li>
 *   <li>data: {"choices":[{"finish_reason":"stop"}],"usage":{...}} → MessageDelta</li>
 *   <li>data: [DONE] → callback.onComplete()</li>
 * </ul>
 *
 */
public sealed interface LlmStreamEvent {

    /** 文本增量 */
    record TextDelta(String text) implements LlmStreamEvent {}

    /** 工具调用开始 */
    record ToolUseStart(String id, String name) implements LlmStreamEvent {}

    /** 工具参数增量 */
    record ToolInputDelta(String toolUseId, String jsonDelta) implements LlmStreamEvent {}

    /** 思考增量 */
    record ThinkingDelta(String thinking) implements LlmStreamEvent {}

    /** 消息完成（含 usage 和停止原因） */
    record MessageDelta(Usage usage, String stopReason) implements LlmStreamEvent {}

    /** 错误事件 */
    record Error(String message, boolean retryable) implements LlmStreamEvent {}

    /** Provider 已产生不可透明重试的实质流事件，但该事件不对前端展示。 */
    record ProviderProgress() implements LlmStreamEvent {}

    /** Responses API 的完整、按序 output 状态，仅用于本地持久化和同模型回放。 */
    record ProviderResponseState(List<JsonNode> outputItems) implements LlmStreamEvent {}

    // ===== Anthropic SSE 新增事件类型 =====

    /** 消息开始 (Anthropic message_start) */
    record MessageStart(String messageId) implements LlmStreamEvent {}

    /** 文本块开始 (Anthropic content_block_start type=text) */
    record TextStart(int index) implements LlmStreamEvent {}

    /** 思考块开始 (Anthropic content_block_start type=thinking) */
    record ThinkingStart(int index) implements LlmStreamEvent {}

    /** 内容块结束 (Anthropic content_block_stop) */
    record BlockStop(int index) implements LlmStreamEvent {}
}
