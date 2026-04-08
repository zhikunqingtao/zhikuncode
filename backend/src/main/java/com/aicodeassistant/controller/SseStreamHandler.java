package com.aicodeassistant.controller;

import com.aicodeassistant.engine.QueryMessageHandler;
import com.aicodeassistant.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * SSE 流式处理器 — 将 QueryMessageHandler 事件转为 SSE events。
 * 用于 CLI 和 SDK 的流式 API 调用。
 */
public class SseStreamHandler implements QueryMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(SseStreamHandler.class);
    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;

    public SseStreamHandler(SseEmitter emitter, ObjectMapper objectMapper) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onTextDelta(String text) {
        sendEvent("text_delta", Map.of("text", text));
    }

    @Override
    public void onThinkingDelta(String thinking) {
        sendEvent("thinking_delta", Map.of("thinking", thinking));
    }

    @Override
    public void onToolUseStart(String toolUseId, String toolName) {
        sendEvent("tool_use_start", Map.of("id", toolUseId, "name", toolName));
    }

    @Override
    public void onToolInputDelta(String toolUseId, String jsonDelta) {
        sendEvent("tool_input_delta", Map.of("id", toolUseId, "delta", jsonDelta));
    }

    @Override
    public void onToolUseComplete(String toolUseId, ContentBlock.ToolUseBlock toolUse) {
        sendEvent("tool_use_complete", Map.of("id", toolUseId, "name", toolUse.name()));
    }

    @Override
    public void onToolResult(String toolUseId, ContentBlock.ToolResultBlock result) {
        sendEvent("tool_result", Map.of(
                "id", toolUseId,
                "content", result.content() != null ? result.content() : "",
                "is_error", result.isError()));
    }

    @Override
    public void onAssistantMessage(Message.AssistantMessage message) {
        sendEvent("assistant_message", Map.of(
                "uuid", message.uuid(),
                "stop_reason", message.stopReason()));
    }

    @Override
    public void onUsage(Usage usage) {
        sendEvent("usage", Map.of(
                "input_tokens", usage.inputTokens(),
                "output_tokens", usage.outputTokens()));
    }

    @Override
    public void onTurnStart(int turnNumber) {
        sendEvent("turn_start", Map.of("turn", turnNumber));
    }

    @Override
    public void onTurnEnd(int turnNumber, String stopReason) {
        sendEvent("turn_end", Map.of("turn", turnNumber, "stop_reason", stopReason));
    }

    @Override
    public void onError(Throwable error) {
        sendEvent("error", Map.of("message",
                error.getMessage() != null ? error.getMessage() : "Unknown error"));
    }

    @Override
    public void onSystemMessage(Message.SystemMessage message) {
        sendEvent("system_message", Map.of(
                "content", message.content(),
                "type", message.type().name()));
    }

    @Override
    public void onCompactEvent(String type, int beforeTokens, int afterTokens) {
        sendEvent("compact", Map.of(
                "type", type,
                "before_tokens", beforeTokens,
                "after_tokens", afterTokens));
    }

    private void sendEvent(String name, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(name).data(json));
        } catch (IOException e) {
            log.debug("SSE send failed (client disconnected?): {}", e.getMessage());
        }
    }
}
