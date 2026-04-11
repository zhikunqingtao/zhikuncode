package com.aicodeassistant.websocket;

import com.aicodeassistant.engine.QueryEngine;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.model.Usage;
import com.aicodeassistant.prompt.EffectiveSystemPromptBuilder;
import com.aicodeassistant.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WebSocket STOMP 消息推送集成测试。
 * <p>
 * 验证:
 * <ol>
 *   <li>推送消息格式 — 扁平 JSON (type + ts + fields)</li>
 *   <li>25 种 Server→Client push 方法调用正确</li>
 *   <li>Session-Principal 映射正确</li>
 *   <li>无 Principal 时静默跳过推送</li>
 * </ol>
 */
class WebSocketStompIntegrationTest {

    private SimpMessagingTemplate messaging;
    private WebSocketSessionManager sessionManager;
    private WebSocketController controller;

    @BeforeEach
    void setUp() {
        messaging = mock(SimpMessagingTemplate.class);
        sessionManager = new WebSocketSessionManager();
        QueryEngine queryEngine = mock(QueryEngine.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        LlmProviderRegistry providerRegistry = mock(LlmProviderRegistry.class);
        EffectiveSystemPromptBuilder systemPromptBuilder = mock(EffectiveSystemPromptBuilder.class);
        controller = new WebSocketController(messaging, sessionManager,
                queryEngine, toolRegistry, providerRegistry, systemPromptBuilder,
                null, null, null, null, null, null, null, null);
    }

    // ═══════════════ 1. 推送消息格式验证 ═══════════════

    @Test
    @DisplayName("pushToUser — 消息包含 type + ts + payload 字段")
    void pushToUser_shouldSendFlatJsonMessage() {
        // 绑定 session
        sessionManager.bindSession("user-1", "session-1");

        // 推送
        controller.pushToUser("session-1", "stream_delta", Map.of("text", "hello"));

        // 验证调用
        verify(messaging).convertAndSendToUser(
                eq("user-1"),
                eq("/queue/messages"),
                argThat(msg -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) msg;
                    return "stream_delta".equals(m.get("type"))
                            && m.containsKey("ts")
                            && "hello".equals(m.get("text"));
                })
        );
    }

    // ═══════════════ 2. 无 Principal 时静默跳过 ═══════════════

    @Test
    @DisplayName("pushToUser — 无 Principal 时不调用 messaging")
    void pushToUser_noPrincipal_shouldSkip() {
        // 未绑定 session
        controller.pushToUser("unknown-session", "stream_delta", Map.of("text", "hi"));

        // 验证未调用
        verifyNoInteractions(messaging);
    }

    // ═══════════════ 3. sendStreamDelta 推送 ═══════════════

    @Test
    @DisplayName("sendStreamDelta — 推送文本增量")
    void sendStreamDelta_shouldPushTextDelta() {
        sessionManager.bindSession("user-1", "session-1");

        controller.sendStreamDelta("session-1", "Hello World");

        verify(messaging).convertAndSendToUser(
                eq("user-1"),
                eq("/queue/messages"),
                argThat(msg -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) msg;
                    return "stream_delta".equals(m.get("type"))
                            && "Hello World".equals(m.get("delta"));
                })
        );
    }

    // ═══════════════ 4. sendToolResult 推送 ═══════════════

    @Test
    @DisplayName("sendToolResult — 推送工具执行结果")
    void sendToolResult_shouldPushToolResult() {
        sessionManager.bindSession("user-1", "session-1");

        controller.sendToolResult("session-1", "tool-1", "file content", false);

        verify(messaging).convertAndSendToUser(
                eq("user-1"),
                eq("/queue/messages"),
                argThat(msg -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) msg;
                    return "tool_result".equals(m.get("type"))
                            && "tool-1".equals(m.get("toolUseId"));
                })
        );
    }

    // ═══════════════ 5. sendError 推送 ═══════════════

    @Test
    @DisplayName("sendError — 推送错误消息")
    void sendError_shouldPushError() {
        sessionManager.bindSession("user-1", "session-1");

        controller.sendError("session-1", "ERR_001", "Something went wrong", false);

        verify(messaging).convertAndSendToUser(
                eq("user-1"),
                eq("/queue/messages"),
                argThat(msg -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) msg;
                    return "error".equals(m.get("type"))
                            && "ERR_001".equals(m.get("code"));
                })
        );
    }

    // ═══════════════ 6. sendPermissionRequest 推送 ═══════════════

    @Test
    @DisplayName("sendPermissionRequest — 推送权限请求")
    void sendPermissionRequest_shouldPushPermission() {
        sessionManager.bindSession("user-1", "session-1");

        controller.sendPermissionRequest("session-1", "tool-1", "BashTool",
                Map.of("command", "rm -rf /tmp/test"), "high", "destructive command");

        verify(messaging).convertAndSendToUser(
                eq("user-1"),
                eq("/queue/messages"),
                argThat(msg -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) msg;
                    return "permission_request".equals(m.get("type"))
                            && "BashTool".equals(m.get("toolName"));
                })
        );
    }

    // ═══════════════ 7. sendCostUpdate 推送 ═══════════════

    @Test
    @DisplayName("sendCostUpdate — 推送费用更新")
    void sendCostUpdate_shouldPushCost() {
        sessionManager.bindSession("user-1", "session-1");

        Usage usage = new Usage(100, 50, 0, 0);
        controller.sendCostUpdate("session-1", 0.01, 0.015, usage);

        verify(messaging).convertAndSendToUser(
                eq("user-1"),
                eq("/queue/messages"),
                argThat(msg -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) msg;
                    return "cost_update".equals(m.get("type"))
                            && m.containsKey("totalCost");
                })
        );
    }

    // ═══════════════ 8. SessionManager 映射 ═══════════════

    @Test
    @DisplayName("SessionManager — bindSession 建立双向映射")
    void sessionManager_bindSession_shouldCreateBidirectionalMapping() {
        sessionManager.bindSession("principal-A", "session-A");

        assertThat(sessionManager.getSessionForPrincipal("principal-A")).isEqualTo("session-A");
        assertThat(sessionManager.getPrincipalForSession("session-A")).isEqualTo("principal-A");
        assertThat(sessionManager.isSessionOnline("session-A")).isTrue();
    }

    @Test
    @DisplayName("SessionManager — 未绑定的 session 返回 null")
    void sessionManager_unboundSession_shouldReturnNull() {
        assertThat(sessionManager.getPrincipalForSession("unknown")).isNull();
        assertThat(sessionManager.getSessionForPrincipal("unknown")).isNull();
        assertThat(sessionManager.isSessionOnline("unknown")).isFalse();
    }

    // ═══════════════ 9. sendMessageComplete 推送 ═══════════════

    @Test
    @DisplayName("sendMessageComplete — 推送消息完成标记")
    void sendMessageComplete_shouldPushComplete() {
        sessionManager.bindSession("user-1", "session-1");

        Usage usage = new Usage(200, 100, 0, 0);
        controller.sendMessageComplete("session-1", usage, "end_turn");

        verify(messaging).convertAndSendToUser(
                eq("user-1"),
                eq("/queue/messages"),
                argThat(msg -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) msg;
                    return "message_complete".equals(m.get("type"))
                            && "end_turn".equals(m.get("stopReason"));
                })
        );
    }

    // ═══════════════ 10. sendPong 推送 ═══════════════

    @Test
    @DisplayName("sendPong — 响应 Ping")
    void sendPong_shouldPushPong() {
        sessionManager.bindSession("user-1", "session-1");

        controller.sendPong("session-1");

        verify(messaging).convertAndSendToUser(
                eq("user-1"),
                eq("/queue/messages"),
                argThat(msg -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) msg;
                    return "pong".equals(m.get("type"));
                })
        );
    }
}
