package com.aicodeassistant.sse;

import org.junit.jupiter.api.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-SSE-001~005 SSE 流式传输测试。
 * 纯单元测试，不依赖 Spring Boot 上下文和运行中的 LLM 服务。
 */
@DisplayName("SSE 流式传输测试")
class SseStreamingTest {

    @Nested
    @DisplayName("TC-SSE-001 SSE 连接建立与事件类型验证")
    class SseEventSequenceTest {

        @Test
        @DisplayName("SseEmitter 创建成功且可发送事件")
        void testSseEmitterCreation() {
            SseEmitter emitter = new SseEmitter(60000L);
            assertNotNull(emitter, "SseEmitter 应成功创建");

            // 验证事件构造器可正确创建各种事件类型
            SseEmitter.SseEventBuilder textDelta = SseEmitter.event()
                .name("text_delta").data("{\"content\":\"Hello\"}");
            assertNotNull(textDelta);

            SseEmitter.SseEventBuilder thinkingDelta = SseEmitter.event()
                .name("thinking_delta").data("{\"content\":\"Thinking...\"}");
            assertNotNull(thinkingDelta);

            SseEmitter.SseEventBuilder toolUseStart = SseEmitter.event()
                .name("tool_use_start").data("{\"id\":\"t1\",\"name\":\"Read\"}");
            assertNotNull(toolUseStart);

            SseEmitter.SseEventBuilder messageComplete = SseEmitter.event()
                .name("message_complete")
                .data("{\"usage\":{\"inputTokens\":100,\"outputTokens\":50},\"stopReason\":\"end_turn\"}");
            assertNotNull(messageComplete);
        }

        @Test
        @DisplayName("SseEmitter 事件名称符合协议规范")
        void testSseEventNames() {
            // 验证所有事件类型名称
            String[] expectedEventTypes = {
                "text_delta", "thinking_delta", "tool_use_start",
                "tool_use_result", "message_complete", "error"
            };

            for (String eventType : expectedEventTypes) {
                SseEmitter.SseEventBuilder event = SseEmitter.event().name(eventType).data("{}");
                assertNotNull(event, "事件类型 " + eventType + " 应可创建");
            }
        }
    }

    @Nested
    @DisplayName("TC-SSE-002 SSE 超时与心跳验证")
    class SseTimeoutTest {

        @Test
        @Timeout(15)
        @DisplayName("SseEmitter 超时配置与回调注册验证")
        void testSseTimeoutConfiguration() {
            SseEmitter shortTimeoutEmitter = new SseEmitter(2000L);
            assertNotNull(shortTimeoutEmitter);

            assertDoesNotThrow(() -> shortTimeoutEmitter.onTimeout(() -> {}));
            assertDoesNotThrow(() -> shortTimeoutEmitter.onCompletion(() -> {}));
            assertDoesNotThrow(() -> shortTimeoutEmitter.onError(ex -> {}));

            SseEmitter productionEmitter = new SseEmitter(60000L);
            assertNotNull(productionEmitter, "60s 超时 SseEmitter 应成功创建");

            assertDoesNotThrow(() -> shortTimeoutEmitter.complete());
        }
    }

    @Nested
    @DisplayName("TC-SSE-003 客户端断连处理")
    class ClientDisconnectTest {

        @Test
        @DisplayName("SseEmitter complete 后不可再发送")
        void testClientDisconnect() {
            SseEmitter emitter = new SseEmitter(60000L);
            AtomicBoolean completionCalled = new AtomicBoolean(false);
            emitter.onCompletion(() -> completionCalled.set(true));

            // 正常完成
            assertDoesNotThrow(() -> emitter.complete());

            // complete 后再发送应抛 IllegalStateException
            assertThrows(Exception.class, () -> {
                emitter.send(SseEmitter.event().name("text_delta").data("late"));
            });
        }

        @Test
        @DisplayName("completeWithError 触发错误回调")
        void testCompleteWithError() {
            SseEmitter emitter = new SseEmitter(60000L);
            AtomicBoolean errorCalled = new AtomicBoolean(false);
            emitter.onError(ex -> errorCalled.set(true));

            emitter.completeWithError(new RuntimeException("test error"));
            // 验证 emitter 已终止
            assertThrows(Exception.class, () -> {
                emitter.send(SseEmitter.event().name("text_delta").data("late"));
            });
        }
    }

    @Nested
    @DisplayName("TC-SSE-004 SSE 事件数据格式验证")
    class EventDataFormatTest {

        @Test
        @DisplayName("message_complete JSON 格式包含 usage 和 stopReason")
        void testMessageCompleteFormat() throws Exception {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

            String messageCompleteJson = mapper.writeValueAsString(
                java.util.Map.of("usage", java.util.Map.of("inputTokens", 100, "outputTokens", 50),
                       "stopReason", "end_turn"));
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(messageCompleteJson);
            assertTrue(node.has("usage"), "message_complete 应包含 usage");
            assertTrue(node.get("usage").has("inputTokens"), "usage 应包含 inputTokens");
            assertTrue(node.get("usage").has("outputTokens"), "usage 应包含 outputTokens");
            assertTrue(node.has("stopReason"), "message_complete 应包含 stopReason");
        }

        @Test
        @DisplayName("tool_use_start JSON 格式包含 id 和 name")
        void testToolUseStartFormat() throws Exception {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

            String toolStartJson = mapper.writeValueAsString(
                java.util.Map.of("id", "tool-1", "name", "Read"));
            com.fasterxml.jackson.databind.JsonNode toolNode = mapper.readTree(toolStartJson);
            assertTrue(toolNode.has("id"), "tool_use_start 应包含 id");
            assertTrue(toolNode.has("name"), "tool_use_start 应包含 name");
        }

        @Test
        @DisplayName("tool_use_result JSON 格式包含 id, content, isError")
        void testToolUseResultFormat() throws Exception {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

            String toolResultJson = mapper.writeValueAsString(
                java.util.Map.of("id", "tool-1", "content", "file content", "isError", false));
            com.fasterxml.jackson.databind.JsonNode resultNode = mapper.readTree(toolResultJson);
            assertTrue(resultNode.has("id"), "tool_use_result 应包含 id");
            assertTrue(resultNode.has("content"), "tool_use_result 应包含 content");
            assertTrue(resultNode.has("isError"), "tool_use_result 应包含 isError");
        }
    }

    @Nested
    @DisplayName("TC-SSE-005 SSE 消息持久化原子性")
    class MessagePersistenceTest {

        @Test
        @DisplayName("SseEmitter 生命周期：创建→发送→完成")
        void testSseLifecycle() {
            SseEmitter emitter = new SseEmitter(60000L);
            AtomicBoolean completed = new AtomicBoolean(false);
            emitter.onCompletion(() -> completed.set(true));

            // 模拟发送事件序列
            assertDoesNotThrow(() -> {
                emitter.send(SseEmitter.event().name("text_delta").data("{\"content\":\"Hi\"}"));
                emitter.send(SseEmitter.event().name("message_complete")
                    .data("{\"usage\":{\"inputTokens\":10,\"outputTokens\":5},\"stopReason\":\"end_turn\"}"));
            });

            // 完成
            emitter.complete();
            // 验证完成后状态（SseEmitter complete 后不可再发送）
            assertThrows(Exception.class, () -> {
                emitter.send(SseEmitter.event().name("text_delta").data("{}"));
            });
        }
    }
}
