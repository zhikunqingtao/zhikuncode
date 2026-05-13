package com.aicodeassistant.coordinator;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.model.dto.AbortRequest;
import com.aicodeassistant.service.AnomalyEventRepository;
import com.aicodeassistant.websocket.WebSocketSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * SwarmController 单元测试（v9.3 安全加固）—— teamName 路径穿越防护。
 */
class SwarmControllerTest {

    private SwarmService swarmService;
    private FeatureFlagService featureFlags;
    private LeaderPermissionBridge permissionBridge;
    private AnomalyEventRepository anomalyEventRepository;
    private WebSocketSessionManager webSocketSessionManager;
    private SwarmController controller;

    @BeforeEach
    void setUp() {
        swarmService = mock(SwarmService.class);
        featureFlags = mock(FeatureFlagService.class);
        permissionBridge = mock(LeaderPermissionBridge.class);
        anomalyEventRepository = mock(AnomalyEventRepository.class);
        webSocketSessionManager = mock(WebSocketSessionManager.class);
        when(featureFlags.isEnabled("ENABLE_AGENT_SWARMS")).thenReturn(true);
        when(swarmService.createSwarm(any(), any()))
                .thenAnswer(inv -> new SwarmState("swarm-test", ((SwarmConfig) inv.getArgument(0)).teamName()));
        controller = new SwarmController(swarmService, featureFlags, permissionBridge, anomalyEventRepository, webSocketSessionManager);
    }

    @Test
    @DisplayName("teamName 含 ../ 必须返回 400 且不创建 Swarm")
    void reject_teamName_path_traversal_dotdot() {
        Map<String, Object> req = new HashMap<>();
        req.put("teamName", "../../../tmp/pwned");
        req.put("maxWorkers", 2);
        ResponseEntity<?> resp = controller.createSwarm(req);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().toString().contains("Invalid teamName"));
        verify(swarmService, never()).createSwarm(any(), any());
    }

    @Test
    @DisplayName("teamName 含正斜杠必须返回 400")
    void reject_teamName_with_slash() {
        Map<String, Object> req = Map.of("teamName", "ok/bad", "maxWorkers", 2);
        ResponseEntity<?> resp = controller.createSwarm(req);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(swarmService, never()).createSwarm(any(), any());
    }

    @Test
    @DisplayName("teamName 含反斜杠必须返回 400")
    void reject_teamName_with_backslash() {
        Map<String, Object> req = Map.of("teamName", "ok\\bad", "maxWorkers", 2);
        ResponseEntity<?> resp = controller.createSwarm(req);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    @DisplayName("teamName 超长（>64 字符）必须返回 400")
    void reject_teamName_too_long() {
        String tn = "a".repeat(65);
        Map<String, Object> req = Map.of("teamName", tn, "maxWorkers", 2);
        ResponseEntity<?> resp = controller.createSwarm(req);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    @DisplayName("合法 teamName 字母+数字+下划线+中划线必须通过")
    void accept_valid_teamName() {
        Map<String, Object> req = Map.of(
                "teamName", "team-alpha_01",
                "maxWorkers", 3,
                "sessionId", "sec-ok"
        );
        ResponseEntity<?> resp = controller.createSwarm(req);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(swarmService, times(1)).createSwarm(any(), any());
    }

    @Test
    @DisplayName("不传 teamName 使用默认 UUID 前缀名必须通过")
    void accept_default_teamName() {
        Map<String, Object> req = Map.of("maxWorkers", 2, "sessionId", "sec-default");
        ResponseEntity<?> resp = controller.createSwarm(req);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(swarmService, times(1)).createSwarm(any(), any());
    }

    @Test
    @DisplayName("teamName 含空格必须返回 400")
    void reject_teamName_whitespace() {
        Map<String, Object> req = Map.of("teamName", "team name", "maxWorkers", 2);
        ResponseEntity<?> resp = controller.createSwarm(req);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    @DisplayName("teamName 为空字符串必须返回 400")
    void reject_empty_teamName() {
        Map<String, Object> req = new HashMap<>();
        req.put("teamName", "");
        req.put("maxWorkers", 2);
        ResponseEntity<?> resp = controller.createSwarm(req);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    // ═══════════════════════════════════════════════════════════════════
    // abortWorker 身份验证测试
    // ═══════════════════════════════════════════════════════════════════

    private SwarmState buildSwarmStateWithWorker(String swarmId, String workerId) {
        SwarmState state = new SwarmState(swarmId, "test-team");
        state.registerWorker(workerId, "test task");
        state.markWorkerWorking(workerId, "doing work");
        return state;
    }

    @Test
    @DisplayName("abortWorker: 正确 sessionId 通过 Layer1 返回 200")
    void testAbortWorker_CorrectSessionId_Returns200() {
        String swarmId = "swarm-001";
        String workerId = "worker-001";
        String sessionId = "test-session-uuid";

        SwarmState state = buildSwarmStateWithWorker(swarmId, workerId);
        when(swarmService.getSwarmState(swarmId)).thenReturn(state);
        when(swarmService.getSessionIdForSwarm(swarmId)).thenReturn(sessionId);

        AbortRequest request = new AbortRequest("test reason", sessionId);
        ResponseEntity<?> resp = controller.abortWorker(swarmId, workerId, request, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    @DisplayName("abortWorker: 错误 sessionId 被 Layer1 拒绝返回 403")
    void testAbortWorker_WrongSessionId_Returns403() {
        String swarmId = "swarm-002";
        String workerId = "worker-002";

        SwarmState state = buildSwarmStateWithWorker(swarmId, workerId);
        when(swarmService.getSwarmState(swarmId)).thenReturn(state);
        when(swarmService.getSessionIdForSwarm(swarmId)).thenReturn("owner-session-uuid");

        AbortRequest request = new AbortRequest("test reason", "wrong-session-uuid");
        ResponseEntity<?> resp = controller.abortWorker(swarmId, workerId, request, null);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    @DisplayName("abortWorker: 无 sessionId 且无 Principal，Layer3 兜底拒绝返回 403")
    void testAbortWorker_NoSessionId_NoPrincipal_Returns403() {
        String swarmId = "swarm-003";
        String workerId = "worker-003";

        SwarmState state = buildSwarmStateWithWorker(swarmId, workerId);
        when(swarmService.getSwarmState(swarmId)).thenReturn(state);
        when(swarmService.getSessionIdForSwarm(swarmId)).thenReturn("test-session-uuid");

        AbortRequest request = new AbortRequest("test reason", null);
        ResponseEntity<?> resp = controller.abortWorker(swarmId, workerId, request, null);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    @DisplayName("abortWorker: 无 sessionId 但 Principal 映射匹配，Layer2 通过返回 200")
    void testAbortWorker_NoSessionId_PrincipalMapped_Returns200() {
        String swarmId = "swarm-004";
        String workerId = "worker-004";
        String ownerSession = "test-session-uuid";

        SwarmState state = buildSwarmStateWithWorker(swarmId, workerId);
        when(swarmService.getSwarmState(swarmId)).thenReturn(state);
        when(swarmService.getSessionIdForSwarm(swarmId)).thenReturn(ownerSession);
        when(webSocketSessionManager.getSessionForPrincipal("user-abc12345")).thenReturn(ownerSession);

        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("user-abc12345");

        AbortRequest request = new AbortRequest("test reason", null);
        ResponseEntity<?> resp = controller.abortWorker(swarmId, workerId, request, principal);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    @DisplayName("abortWorker: ownerSessionId 为 null（旧数据无绑定）直接允许")
    void testAbortWorker_NoOwnerSession_AllowsAbort() {
        String swarmId = "swarm-005";
        String workerId = "worker-005";

        SwarmState state = buildSwarmStateWithWorker(swarmId, workerId);
        when(swarmService.getSwarmState(swarmId)).thenReturn(state);
        when(swarmService.getSessionIdForSwarm(swarmId)).thenReturn(null);

        AbortRequest request = new AbortRequest("test reason", null);
        ResponseEntity<?> resp = controller.abortWorker(swarmId, workerId, request, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }
}
