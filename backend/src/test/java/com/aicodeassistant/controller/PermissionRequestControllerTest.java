package com.aicodeassistant.controller;

import com.aicodeassistant.permission.DurablePermissionService;
import com.aicodeassistant.permission.PermissionPipeline;
import com.aicodeassistant.permission.PermissionRequestRecord;
import com.aicodeassistant.websocket.WebSocketSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * PermissionRequestController 单元测试 — StandaloneMockMvc 模式，覆盖 REST 端点鉴权和业务逻辑。
 */
@ExtendWith(MockitoExtension.class)
class PermissionRequestControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock private DurablePermissionService durablePermissionService;
    @Mock private PermissionPipeline permissionPipeline;
    @Mock private WebSocketSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        PermissionRequestController controller = new PermissionRequestController(
                durablePermissionService, permissionPipeline, sessionManager);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldReturn403_whenSessionIdNotOnline() throws Exception {
        // Given: session 不在线
        when(sessionManager.isSessionOnline("invalid-session")).thenReturn(false);

        // When & Then
        mockMvc.perform(get("/api/sessions/invalid-session/permissions"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturnPermissionList_whenSessionIdIsValid() throws Exception {
        // Given: session 在线，并有权限记录
        when(sessionManager.isSessionOnline("valid-session")).thenReturn(true);

        Instant now = Instant.now();
        PermissionRequestRecord record = new PermissionRequestRecord(
                "id-1", "run-1", "valid-session", "tool-use-1",
                "BashTool", "high", "Execute command", "{\"cmd\":\"ls\"}",
                "pending", null, null, false, null,
                now, null, now.plusSeconds(300),
                "direct", null, now
        );
        when(durablePermissionService.findBySession(eq("valid-session"), isNull()))
                .thenReturn(List.of(record));

        // When & Then
        mockMvc.perform(get("/api/sessions/valid-session/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.permissions[0].toolUseId").value("tool-use-1"))
                .andExpect(jsonPath("$.permissions[0].toolName").value("BashTool"))
                .andExpect(jsonPath("$.permissions[0].riskLevel").value("high"));
    }

    @Test
    void shouldReturn404_whenToolUseIdNotFound() throws Exception {
        // Given: session 在线但 toolUseId 对应的 pending 请求不存在
        when(sessionManager.isSessionOnline("session-1")).thenReturn(true);
        when(durablePermissionService.findPendingByToolUseId("non-existent")).thenReturn(null);

        String body = objectMapper.writeValueAsString(
                new PermissionRequestController.DecideRequest("allow", true, "session"));

        // When & Then
        mockMvc.perform(post("/api/permissions/non-existent/decide")
                        .param("sessionId", "session-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldResolvePermission_whenDecideCalledWithValidToolUseId() throws Exception {
        // Given: session 在线且 pending 请求存在，sessionId 归属匹配
        when(sessionManager.isSessionOnline("session-1")).thenReturn(true);

        Instant now = Instant.now();
        PermissionRequestRecord pending = new PermissionRequestRecord(
                "id-2", "run-1", "session-1", "tool-use-2",
                "FileWriteTool", "medium", "Write file", "{\"path\":\"/tmp/a.txt\"}",
                "pending", null, null, false, null,
                now, null, now.plusSeconds(300),
                "direct", null, now
        );
        when(durablePermissionService.findPendingByToolUseId("tool-use-2")).thenReturn(pending);
        when(durablePermissionService.resolve(eq("tool-use-2"), eq("approved"),
                eq("USER_REST"), eq(true), eq("project"))).thenReturn(true);

        String body = objectMapper.writeValueAsString(
                new PermissionRequestController.DecideRequest("allow", true, "project"));

        // When & Then
        mockMvc.perform(post("/api/permissions/tool-use-2/decide")
                        .param("sessionId", "session-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.toolUseId").value("tool-use-2"))
                .andExpect(jsonPath("$.decision").value("approved"));

        // 验证 resolve 和 permissionPipeline 被调用
        verify(durablePermissionService).resolve(eq("tool-use-2"), eq("approved"),
                eq("USER_REST"), eq(true), eq("project"));
        verify(permissionPipeline).resolvePermission(eq("tool-use-2"), any());
    }

    @Test
    void shouldDenyPermission_whenDecideCalledWithDeny() throws Exception {
        // Given: session 在线且 pending 请求存在，sessionId 归属匹配
        when(sessionManager.isSessionOnline("session-1")).thenReturn(true);

        Instant now = Instant.now();
        PermissionRequestRecord pending = new PermissionRequestRecord(
                "id-3", "run-1", "session-1", "tool-use-3",
                "BashTool", "high", "rm -rf", "{}",
                "pending", null, null, false, null,
                now, null, now.plusSeconds(300),
                "direct", null, now
        );
        when(durablePermissionService.findPendingByToolUseId("tool-use-3")).thenReturn(pending);
        when(durablePermissionService.resolve(eq("tool-use-3"), eq("denied"),
                eq("USER_REST"), eq(false), eq("session"))).thenReturn(true);

        String body = objectMapper.writeValueAsString(
                new PermissionRequestController.DecideRequest("deny", false, "session"));

        // When & Then
        mockMvc.perform(post("/api/permissions/tool-use-3/decide")
                        .param("sessionId", "session-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("denied"));

        verify(durablePermissionService).resolve(eq("tool-use-3"), eq("denied"),
                eq("USER_REST"), eq(false), eq("session"));
    }

    @Test
    void shouldReturn403_whenSessionIdMismatch() throws Exception {
        // Given: session 在线但不拥有该权限请求
        when(sessionManager.isSessionOnline("session-attacker")).thenReturn(true);

        Instant now = Instant.now();
        PermissionRequestRecord pending = new PermissionRequestRecord(
                "id-4", "run-1", "session-owner", "tool-use-4",
                "BashTool", "high", "Execute command", "{}",
                "pending", null, null, false, null,
                now, null, now.plusSeconds(300),
                "direct", null, now
        );
        when(durablePermissionService.findPendingByToolUseId("tool-use-4")).thenReturn(pending);

        String body = objectMapper.writeValueAsString(
                new PermissionRequestController.DecideRequest("allow", true, "session"));

        // When & Then: 攻击者 session 与权限请求归属 session 不匹配，应返回 403
        mockMvc.perform(post("/api/permissions/tool-use-4/decide")
                        .param("sessionId", "session-attacker")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }
}
