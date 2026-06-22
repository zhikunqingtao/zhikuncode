package com.aicodeassistant.mcp.progress;

import com.aicodeassistant.websocket.WebSocketSessionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * M4 — McpProgressTracker 单元测试。
 *
 * <p>覆盖注册/注销、有 principal 时 progress 转发、无 principal 时静默丢弃，
 * 以及未知 token 静默忽略的契约。
 */
@DisplayName("M4 McpProgressTracker 单元测试")
class McpProgressTrackerTest {

    private SimpMessagingTemplate messagingTemplate;
    private WebSocketSessionManager wsSessionManager;
    private McpProgressTracker tracker;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        wsSessionManager = mock(WebSocketSessionManager.class);
        tracker = new McpProgressTracker(messagingTemplate, wsSessionManager);
    }

    @Test
    @DisplayName("register 后 active 计数 +1，unregister 后归零")
    void testRegisterAndUnregister() {
        assertEquals(0, tracker.activeCount(), "初始 active 应为 0");

        tracker.registerProgress("tok-1", "session-1", "fs-server", "read_file");
        assertEquals(1, tracker.activeCount(), "register 后 active 应为 1");

        // 重复 register 同一 token 应覆盖而非新增
        tracker.registerProgress("tok-1", "session-1", "fs-server", "read_file");
        assertEquals(1, tracker.activeCount(), "重复 register 同一 token 不应增加计数");

        tracker.unregisterProgress("tok-1");
        assertEquals(0, tracker.activeCount(), "unregister 后 active 应为 0");

        // null / 空 token 容错
        assertDoesNotThrow(() -> tracker.registerProgress(null, "s", "srv", "t"));
        assertDoesNotThrow(() -> tracker.registerProgress("", "s", "srv", "t"));
        assertDoesNotThrow(() -> tracker.unregisterProgress(null));
        assertDoesNotThrow(() -> tracker.unregisterProgress(""));
        assertEquals(0, tracker.activeCount(),
                "null/空 token 不应被记录");
    }

    @Test
    @DisplayName("正常 progress 通知应通过 convertAndSendToUser 转发")
    @SuppressWarnings("unchecked")
    void testHandleProgressNotification() throws Exception {
        tracker.registerProgress("tok-X", "session-X", "fs-server", "read_file");
        when(wsSessionManager.getPrincipalForSession("session-X")).thenReturn("user-1");

        String json = "{\"method\":\"notifications/progress\","
                + "\"params\":{\"progressToken\":\"tok-X\","
                + "\"progress\":42.0,\"total\":100.0,\"message\":\"halfway\"}}";
        JsonNode notification = objectMapper.readTree(json);

        tracker.handleProgressNotification(notification);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(1))
                .convertAndSendToUser(eq("user-1"), eq("/queue/messages"), payloadCaptor.capture());

        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertEquals("mcp_tool_progress", payload.get("type"));
        assertEquals("tok-X", payload.get("progressToken"));
        assertEquals("fs-server", payload.get("serverName"));
        assertEquals("read_file", payload.get("toolName"));
        assertEquals(42.0, (double) payload.get("progress"), 1e-9);
        assertEquals(100.0, (double) payload.get("total"), 1e-9);
        assertEquals("halfway", payload.get("message"));
    }

    @Test
    @DisplayName("无法定位 principal 时不抛异常、不广播")
    void testHandleProgressNoPrincipal() throws Exception {
        // 注册 token 但 principal 解析返回 null
        tracker.registerProgress("tok-N", "session-N", "fs-server", "read_file");
        when(wsSessionManager.getPrincipalForSession("session-N")).thenReturn(null);

        String json = "{\"params\":{\"progressToken\":\"tok-N\","
                + "\"progress\":1,\"total\":10,\"message\":\"\"}}";
        JsonNode notification = objectMapper.readTree(json);

        assertDoesNotThrow(() -> tracker.handleProgressNotification(notification));
        verify(messagingTemplate, never())
                .convertAndSendToUser(any(), any(), any());

        // 未知 token 同样静默忽略
        String unknownJson = "{\"params\":{\"progressToken\":\"unknown-tok\"}}";
        JsonNode unknownNotification = objectMapper.readTree(unknownJson);
        assertDoesNotThrow(() -> tracker.handleProgressNotification(unknownNotification));

        // null notification 容错
        assertDoesNotThrow(() -> tracker.handleProgressNotification(null));

        // 全程不应有任何 messagingTemplate 调用
        verify(messagingTemplate, never())
                .convertAndSendToUser(any(), any(), any());
    }
}
