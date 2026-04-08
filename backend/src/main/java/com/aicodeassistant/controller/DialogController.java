package com.aicodeassistant.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 对话框决策 Controller — 处理前端权限确认/快照更新等对话框交互。
 * <p>
 * 工作流程:
 * 1. 后端流程中遇到需要用户决策的点 → 创建 pending 请求 (通过 WebSocket 推送到前端)
 * 2. 前端展示对话框 UI → 用户操作
 * 3. 前端发送决策到此 Controller → 后端恢复流程
 *
 * @see <a href="SPEC §8.2.5">React Context 提供者层与对话启动器</a>
 */
@RestController
@RequestMapping("/api/dialogs")
public class DialogController {

    private static final Logger log = LoggerFactory.getLogger(DialogController.class);

    /** 待决策请求: requestId → CompletableFuture */
    private final Map<String, CompletableFuture<Object>> pendingRequests = new ConcurrentHashMap<>();

    /**
     * 快照更新决策 — 对齐 launchSnapshotUpdateDialog。
     * 用户选择: merge | keep | replace
     */
    @PostMapping("/snapshot-update/{requestId}/decision")
    public ResponseEntity<Void> resolveSnapshotUpdate(
            @PathVariable String requestId,
            @RequestBody SnapshotDecision decision) {
        log.info("Snapshot update decision: requestId={}, action={}", requestId, decision.action());
        CompletableFuture<Object> future = pendingRequests.remove(requestId);
        if (future != null) {
            future.complete(decision.action());
        }
        return ResponseEntity.ok().build();
    }

    /**
     * 权限确认决策 — 对齐 launchPluginPermissionDialog。
     */
    @PostMapping("/plugin-permission/{requestId}/decision")
    public ResponseEntity<Void> resolvePluginPermission(
            @PathVariable String requestId,
            @RequestBody PermissionDecisionRequest decision) {
        log.info("Plugin permission decision: requestId={}, allowed={}", requestId, decision.allowed());
        CompletableFuture<Object> future = pendingRequests.remove(requestId);
        if (future != null) {
            future.complete(decision.allowed());
        }
        return ResponseEntity.ok().build();
    }

    // ===== 内部 API — 供其他 Service 调用 =====

    /**
     * 创建待决策请求 — 返回 Future 供调用方阻塞等待。
     */
    public CompletableFuture<Object> createPendingRequest(String requestId) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        // 超时自动取消（60 秒）
        future.orTimeout(60, TimeUnit.SECONDS)
                .whenComplete((result, error) -> pendingRequests.remove(requestId));
        return future;
    }

    // ═══ DTO Records ═══
    public record SnapshotDecision(String action) {} // merge | keep | replace
    public record PermissionDecisionRequest(boolean allowed) {}
}
