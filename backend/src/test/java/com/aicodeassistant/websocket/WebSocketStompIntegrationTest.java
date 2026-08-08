package com.aicodeassistant.websocket;

import com.aicodeassistant.engine.QueryEngine;
import com.aicodeassistant.exception.WorkspaceException;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.model.Usage;
import com.aicodeassistant.permission.PermissionInteractionService;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.prompt.EffectiveSystemPromptBuilder;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.service.ActivityRepository;
import com.aicodeassistant.session.SessionData;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private PermissionModeManager permissionModes;
    private WebSocketController controller;

    @BeforeEach
    void setUp() {
        messaging = mock(SimpMessagingTemplate.class);
        sessionManager = new WebSocketSessionManager(mock(JdbcTemplate.class));
        permissionModes = mock(PermissionModeManager.class);
        QueryEngine queryEngine = mock(QueryEngine.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        LlmProviderRegistry providerRegistry = mock(LlmProviderRegistry.class);
        EffectiveSystemPromptBuilder systemPromptBuilder = mock(EffectiveSystemPromptBuilder.class);
        controller = new WebSocketController(messaging, sessionManager,
                queryEngine, toolRegistry, providerRegistry, systemPromptBuilder,
                null, null, null, null, null, null, null, null, permissionModes, null, null, null, null, null,
                null, null, null, null, null);
    }

    private void bind(String principal, String session) {
        sessionManager.registerTransport(principal, principal);
        sessionManager.bindSession(principal, principal, session, 1);
    }

    @Test
    void permissionModeAcceptsAutoApproveAndRejectsInvalidValues() {
        bind("user-1", "session-1");
        Principal principal = () -> "user-1";

        controller.handleSetPermissionMode(
                new ClientMessage.SetPermissionModePayload("AUTO_APPROVE"), principal);

        verify(permissionModes).setMode("session-1", PermissionMode.AUTO_APPROVE);

        controller.handleSetPermissionMode(
                new ClientMessage.SetPermissionModePayload("invalid-client-value"), principal);

        verify(permissionModes, times(1)).setMode(anyString(), any());
        verify(messaging).convertAndSendToUser(
                eq("user-1"), eq("/queue/messages"),
                argThat(message -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = (Map<String, Object>) message;
                    return "error".equals(payload.get("type"))
                            && "INVALID_PERMISSION_MODE".equals(payload.get("code"))
                            && "Invalid permission mode".equals(payload.get("message"))
                            && !String.valueOf(payload).contains("invalid-client-value");
                }));
    }

    // ═══════════════ 1. 推送消息格式验证 ═══════════════

    @Test
    @DisplayName("pushToUser — 消息包含 type + ts + payload 字段")
    void pushToUser_shouldSendFlatJsonMessage() {
        // 绑定 session
        bind("user-1", "session-1");

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

    @Test
    void differentTransportsNeverReceiveAnotherApplicationSessionsEvents() {
        bind("transport-a", "session-a");
        bind("transport-b", "session-b");

        controller.sendStreamDelta("session-a", "private-a");

        verify(messaging).convertAndSendToUser(eq("transport-a"), eq("/queue/messages"), any());
        verify(messaging, never()).convertAndSendToUser(eq("transport-b"), anyString(), any());
    }

    @Test
    void normalSessionEventsBroadcastToAllTransportsBoundToThatSession() {
        bind("transport-a", "session-a");
        bind("transport-b", "session-a");

        controller.sendStreamDelta("session-a", "shared");

        verify(messaging).convertAndSendToUser(eq("transport-a"), eq("/queue/messages"), any());
        verify(messaging).convertAndSendToUser(eq("transport-b"), eq("/queue/messages"), any());
    }

    // ═══════════════ 3. sendStreamDelta 推送 ═══════════════

    @Test
    @DisplayName("sendStreamDelta — 推送文本增量")
    void sendStreamDelta_shouldPushTextDelta() {
        bind("user-1", "session-1");

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
        bind("user-1", "session-1");

        Map<String, Object> metadata = Map.of(
                "structuredResult", Map.of(
                        "schema", "external-resource/v1",
                        "url", "https://example.oss-cn-beijing.aliyuncs.com/object"),
                "internalSecret", "must-not-cross");
        controller.sendToolResult("session-1", "tool-1", "file content", false, metadata);

        verify(messaging).convertAndSendToUser(
                eq("user-1"),
                eq("/queue/messages"),
                argThat(msg -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) msg;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) m.get("result");
                    return "tool_result".equals(m.get("type"))
                            && "tool-1".equals(m.get("toolUseId"))
                            && Map.of("structuredResult", metadata.get("structuredResult"))
                                    .equals(result.get("metadata"));
                })
        );
    }

    // ═══════════════ 5. sendError 推送 ═══════════════

    @Test
    @DisplayName("sendError — 推送错误消息")
    void sendError_shouldPushError() {
        bind("user-1", "session-1");

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
        bind("user-1", "session-1");

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
        bind("user-1", "session-1");

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
        bind("principal-A", "session-A");

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

    @Test
    void invalidWorkspaceRejectsBindBeforeReplacingCurrentSession() {
        bind("principal-A", "session-old");
        SessionManager persistedSessions = mock(SessionManager.class);
        ProjectWorkspaceService projectWorkspaces =
                mock(ProjectWorkspaceService.class);
        SessionData target = new SessionData(
                "session-new", "model", "/saved/workspace", "title",
                "idle", List.of(), Map.of(), null, 0, null,
                Instant.now(), Instant.now());
        when(persistedSessions.loadSession("session-new"))
                .thenReturn(Optional.of(target));
        when(projectWorkspaces.requireCurrentBinding(
                "/saved/workspace"))
                .thenThrow(new WorkspaceException(
                        HttpStatus.CONFLICT, "WORKSPACE_REBOUND",
                        "Workspace path changed"));
        WebSocketController bindController = new WebSocketController(
                messaging, sessionManager, mock(QueryEngine.class),
                mock(ToolRegistry.class), mock(LlmProviderRegistry.class),
                mock(EffectiveSystemPromptBuilder.class), null,
                persistedSessions, null, null, null, null, null,
                projectWorkspaces, null, null, null, null, null, null,
                null, null, null, null, null);
        SimpMessageHeaderAccessor headers =
                SimpMessageHeaderAccessor.create();
        headers.setSessionId("principal-A");

        bindController.handleBindSession(Map.of(
                        "sessionId", "session-new",
                        "protocolVersion", 3,
                        "bindRequestId", "bind-2",
                        "bindingEpoch", 2),
                () -> "principal-A", headers);

        assertThat(sessionManager.getSessionForPrincipal("principal-A"))
                .isEqualTo("session-old");
        verify(messaging).convertAndSendToUser(
                eq("principal-A"), eq("/queue/messages"),
                argThat(message -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload =
                            (Map<String, Object>) message;
                    return "protocol_error".equals(payload.get("type"))
                            && "WORKSPACE_REBOUND".equals(
                                    payload.get("code"))
                            && "bind-2".equals(
                                    payload.get("bindRequestId"))
                            && Long.valueOf(2).equals(
                                    payload.get("bindingEpoch"));
                }));
    }

    @Test
    void sessionRestoreUsesActualPermissionModeWithoutDuplicateChangeEvent() {
        SessionManager persistedSessions = mock(SessionManager.class);
        ProjectWorkspaceService projectWorkspaces = mock(ProjectWorkspaceService.class);
        ActivityRepository activities = mock(ActivityRepository.class);
        PermissionInteractionService interactions = mock(PermissionInteractionService.class);
        SessionData target = new SessionData(
                "session-1", "model", "/saved/workspace", "title",
                "idle", List.of(), Map.of(), Usage.zero(), 0, null,
                Instant.now(), Instant.now());
        when(persistedSessions.loadSession("session-1")).thenReturn(Optional.of(target));
        when(permissionModes.getMode("session-1")).thenReturn(PermissionMode.AUTO_APPROVE);
        when(activities.findBySessionId("session-1")).thenReturn(List.of());
        when(interactions.getPendingInteractions("session-1")).thenReturn(List.of());
        WebSocketController bindController = new WebSocketController(
                messaging, sessionManager, mock(QueryEngine.class),
                mock(ToolRegistry.class), mock(LlmProviderRegistry.class),
                mock(EffectiveSystemPromptBuilder.class), null,
                persistedSessions, null, null, null, null, null,
                projectWorkspaces, permissionModes, null, activities,
                new ObjectMapper(), null, interactions,
                null, null, null, null, null);
        sessionManager.registerTransport("transport-1", "principal-A");
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        headers.setSessionId("transport-1");

        bindController.handleBindSession(Map.of(
                        "sessionId", "session-1",
                        "protocolVersion", 3,
                        "bindRequestId", "bind-1",
                        "bindingEpoch", 1),
                () -> "principal-A", headers);

        verify(messaging).convertAndSendToUser(
                eq("principal-A"), eq("/queue/messages"),
                argThat(message -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = (Map<String, Object>) message;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metadata =
                            (Map<String, Object>) payload.get("metadata");
                    return "session_restored".equals(payload.get("type"))
                            && metadata != null
                            && "AUTO_APPROVE".equals(metadata.get("permissionMode"));
                }));
        verify(messaging, never()).convertAndSendToUser(
                anyString(), eq("/queue/messages"),
                argThat(message -> message instanceof Map<?, ?> payload
                        && "permission_mode_changed".equals(payload.get("type"))));
    }

    // ═══════════════ 9. sendMessageComplete 推送 ═══════════════

    @Test
    @DisplayName("sendMessageComplete — 推送消息完成标记")
    void sendMessageComplete_shouldPushComplete() {
        bind("user-1", "session-1");

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

    @Test
    @DisplayName("sendMessageComplete — 携带权威提交消息尾和替换锚点")
    void sendMessageComplete_shouldPushCommittedMessageTail() {
        bind("user-1", "session-1");
        Message committed = new Message.UserMessage(
                "message-1", Instant.ofEpochMilli(123),
                List.of(new ContentBlock.TextBlock("saved")), null, null);

        controller.sendMessageComplete(
                "session-1", new Usage(20, 10, 0, 0), "end_turn",
                "run-1", "anchor-1", List.of(committed));

        verify(messaging).convertAndSendToUser(
                eq("user-1"),
                eq("/queue/messages"),
                argThat(msg -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = (Map<String, Object>) msg;
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> messages =
                            (List<Map<String, Object>>) payload.get("committedMessages");
                    return "message_complete".equals(payload.get("type"))
                            && "session-1".equals(payload.get("sessionId"))
                            && "run-1".equals(payload.get("runId"))
                            && "anchor-1".equals(payload.get("replaceAfterMessageId"))
                            && messages != null
                            && messages.size() == 1
                            && "message-1".equals(messages.getFirst().get("uuid"))
                            && "user".equals(messages.getFirst().get("type"));
                })
        );
    }

    // ═══════════════ 10. sendPong 推送 ═══════════════

    @Test
    @DisplayName("sendPong — 响应 Ping")
    void sendPong_shouldPushPong() {
        bind("user-1", "session-1");

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
