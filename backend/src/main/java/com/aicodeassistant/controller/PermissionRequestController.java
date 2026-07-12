package com.aicodeassistant.controller;

import com.aicodeassistant.model.PermissionBehavior;
import com.aicodeassistant.model.PermissionDecision;
import com.aicodeassistant.model.RuleScope;
import com.aicodeassistant.permission.DurablePermissionService;
import com.aicodeassistant.permission.PermissionPipeline;
import com.aicodeassistant.permission.PermissionRequestRecord;
import com.aicodeassistant.websocket.WebSocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST 备用权限决策通道 — WebSocket 不可用时的降级方案。
 * <p>
 * 端点:
 * <ul>
 *   <li>GET /api/sessions/{sessionId}/permissions?status=pending — 查询权限请求</li>
 *   <li>POST /api/permissions/{toolUseId}/decide — REST 决策</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class PermissionRequestController {

    private static final Logger log = LoggerFactory.getLogger(PermissionRequestController.class);

    private final DurablePermissionService durablePermissionService;
    private final PermissionPipeline permissionPipeline;
    private final WebSocketSessionManager sessionManager;

    public PermissionRequestController(DurablePermissionService durablePermissionService,
                                        PermissionPipeline permissionPipeline,
                                        WebSocketSessionManager sessionManager) {
        this.durablePermissionService = durablePermissionService;
        this.permissionPipeline = permissionPipeline;
        this.sessionManager = sessionManager;
    }

    /**
     * 查询指定会话的权限请求。
     *
     * @param sessionId 会话 ID
     * @param status    可选状态过滤（pending/approved/denied/timeout）
     */
    @GetMapping("/sessions/{sessionId}/permissions")
    public ResponseEntity<Map<String, Object>> getPermissions(
            @PathVariable String sessionId,
            @RequestParam(required = false) String status) {
        if (!sessionManager.isSessionOnline(sessionId)) {
            log.warn("Permission query rejected: sessionId={} not found in active sessions", sessionId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        List<PermissionRequestRecord> records = durablePermissionService.findBySession(sessionId, status);
        List<Map<String, Object>> items = records.stream().map(this::toMap).toList();
        return ResponseEntity.ok(Map.of("permissions", items, "total", items.size()));
    }

    /**
     * REST 决策通道 — WebSocket 不可用时的备选。
     * 安全检查：验证调用者对目标权限请求的会话归属权。
     */
    @PostMapping("/permissions/{toolUseId}/decide")
    public ResponseEntity<Map<String, Object>> decide(
            @PathVariable String toolUseId,
            @RequestParam String sessionId,
            @RequestBody DecideRequest request) {

        log.info("REST permission decide: toolUseId={}, sessionId={}, decision={}", toolUseId, sessionId, request.decision());

        // 安全检查 1: 验证 session 有效性
        if (!sessionManager.isSessionOnline(sessionId)) {
            log.warn("Permission decide rejected: sessionId={} not found in active sessions", sessionId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // 验证请求存在
        PermissionRequestRecord pending = durablePermissionService.findPendingByToolUseId(toolUseId);
        if (pending == null) {
            return ResponseEntity.notFound().build();
        }

        // 安全检查 2: 验证调用者对该权限请求的会话归属权
        if (!sessionId.equals(pending.sessionId())) {
            log.warn("Permission decide rejected: sessionId={} does not own toolUseId={} (owner={})",
                    sessionId, toolUseId, pending.sessionId());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // 原子更新 DB 状态
        String dbDecision = "allow".equalsIgnoreCase(request.decision()) ? "approved" : "denied";
        boolean updated = durablePermissionService.resolve(toolUseId, dbDecision, "USER_REST",
                request.remember() != null && request.remember(),
                request.scope());

        // 并发门控：如果 DB 更新失败（已被其他操作处理），返回 409 Conflict
        if (!updated) {
            log.warn("Permission already resolved by another operation: toolUseId={}", toolUseId);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("success", false, "toolUseId", toolUseId,
                            "error", "Permission request already resolved"));
        }

        // 同步解决内存中的 CompletableFuture
        PermissionBehavior behavior = "approved".equals(dbDecision)
                ? PermissionBehavior.ALLOW : PermissionBehavior.DENY;

        PermissionDecision decision;
        if (behavior == PermissionBehavior.ALLOW) {
            RuleScope scope = "global".equals(request.scope()) ? RuleScope.GLOBAL
                    : "project".equals(request.scope()) ? RuleScope.PROJECT
                    : RuleScope.SESSION;
            decision = PermissionDecision.allow(
                    com.aicodeassistant.model.PermissionDecisionReason.OTHER, null)
                    .withRemember(request.remember() != null && request.remember(), scope);
        } else {
            decision = PermissionDecision.denyByMode("User denied via REST");
        }

        permissionPipeline.resolvePermission(toolUseId, decision);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "toolUseId", toolUseId,
                "decision", dbDecision));
    }

    private Map<String, Object> toMap(PermissionRequestRecord r) {
        return Map.ofEntries(
                Map.entry("id", r.id()),
                Map.entry("toolUseId", r.toolUseId()),
                Map.entry("toolName", r.toolName()),
                Map.entry("riskLevel", r.riskLevel()),
                Map.entry("reason", r.reason() != null ? r.reason() : ""),
                Map.entry("inputSummary", r.inputSummary() != null ? r.inputSummary() : ""),
                Map.entry("status", r.status()),
                Map.entry("decision", r.decision() != null ? r.decision() : ""),
                Map.entry("decidedBy", r.decidedBy() != null ? r.decidedBy() : ""),
                Map.entry("remember", r.remember()),
                Map.entry("requestedAt", r.requestedAt().toString()),
                Map.entry("timeoutAt", r.timeoutAt().toString()),
                Map.entry("source", r.source() != null ? r.source() : "direct")
        );
    }

    public record DecideRequest(
            String decision,   // "allow" | "deny"
            Boolean remember,
            String scope       // "session" | "project" | "global"
    ) {}
}
