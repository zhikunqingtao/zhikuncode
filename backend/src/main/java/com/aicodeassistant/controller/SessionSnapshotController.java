package com.aicodeassistant.controller;

import com.aicodeassistant.exception.SessionNotFoundException;
import com.aicodeassistant.session.SessionData;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.session.SessionSnapshot;
import com.aicodeassistant.session.SessionSnapshotService;
import com.aicodeassistant.session.SessionSnapshotSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SessionSnapshotController — 会话快照管理 REST API。
 * <p>
 * 端点:
 * <ul>
 *   <li>GET  /api/sessions/snapshots — 列出所有快照</li>
 *   <li>POST /api/sessions/{id}/snapshot — 手动保存当前会话快照</li>
 *   <li>POST /api/sessions/{id}/snapshot/resume — 从快照恢复会话</li>
 *   <li>DELETE /api/sessions/snapshots/{id} — 删除快照</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionSnapshotController {

    private static final Logger log = LoggerFactory.getLogger(SessionSnapshotController.class);

    private final SessionSnapshotService snapshotService;
    private final SessionManager sessionManager;

    public SessionSnapshotController(SessionSnapshotService snapshotService,
                                     SessionManager sessionManager) {
        this.snapshotService = snapshotService;
        this.sessionManager = sessionManager;
    }

    private boolean isLocalRequest(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        return "127.0.0.1".equals(remoteAddr) || "0:0:0:0:0:0:0:1".equals(remoteAddr) || "::1".equals(remoteAddr);
    }

    /**
     * 列出所有快照摘要（按创建时间降序）。
     */
    @GetMapping("/snapshots")
    public ResponseEntity<List<SessionSnapshotSummary>> listSnapshots(HttpServletRequest request) {
        if (!isLocalRequest(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        List<SessionSnapshotSummary> snapshots = snapshotService.listSnapshots();
        return ResponseEntity.ok(snapshots);
    }

    /**
     * 手动保存当前会话快照。
     * <p>
     * 从 SessionManager 加载会话数据，构建 SessionSnapshot 并持久化到磁盘。
     */
    @PostMapping("/{sessionId}/snapshot")
    public ResponseEntity<SessionSnapshotSummary> saveSnapshot(@PathVariable String sessionId, HttpServletRequest request) {
        if (!isLocalRequest(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        SessionData data = sessionManager.loadSession(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        // 计算 turnCount: user 消息数量
        int turnCount = (int) data.messages().stream()
                .filter(m -> m instanceof com.aicodeassistant.model.Message.UserMessage)
                .count();

        // 构建 metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("title", data.title());
        metadata.put("workingDir", data.workingDir());
        metadata.put("status", data.status());
        if (data.totalUsage() != null) {
            metadata.put("totalInputTokens", data.totalUsage().inputTokens());
            metadata.put("totalOutputTokens", data.totalUsage().outputTokens());
        }
        metadata.put("totalCostUsd", data.totalCostUsd());

        SessionSnapshot snapshot = new SessionSnapshot(
                data.sessionId(),
                data.messages(),
                data.model(),
                turnCount,
                Instant.now(),
                metadata
        );

        snapshotService.saveSnapshot(sessionId, snapshot);

        SessionSnapshotSummary summary = new SessionSnapshotSummary(
                snapshot.sessionId(),
                snapshot.model(),
                snapshot.turnCount(),
                snapshot.messages().size(),
                snapshot.createdAt()
        );

        log.info("Snapshot manually saved for session: {}", sessionId);
        return ResponseEntity.status(201).body(summary);
    }

    /**
     * 从快照恢复会话。
     * <p>
     * 使用快照中的消息和元数据恢复会话状态。
     */
    @PostMapping("/{sessionId}/snapshot/resume")
    public ResponseEntity<SessionSnapshotSummary> resumeFromSnapshot(@PathVariable String sessionId, HttpServletRequest request) {
        if (!isLocalRequest(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        SessionSnapshot snapshot = snapshotService.loadSnapshot(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(
                        "Snapshot not found for session: " + sessionId));

        // 通过 SessionManager.resumeSession 恢复到 AppState
        sessionManager.resumeSession(sessionId);

        SessionSnapshotSummary summary = new SessionSnapshotSummary(
                snapshot.sessionId(),
                snapshot.model(),
                snapshot.turnCount(),
                snapshot.messages().size(),
                snapshot.createdAt()
        );

        log.info("Session resumed from snapshot: {} (messages={})", sessionId, snapshot.messages().size());
        return ResponseEntity.ok(summary);
    }

    /**
     * 删除快照。
     */
    @DeleteMapping("/snapshots/{sessionId}")
    public ResponseEntity<Map<String, Boolean>> deleteSnapshot(@PathVariable String sessionId, HttpServletRequest request) {
        if (!isLocalRequest(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        boolean deleted = snapshotService.deleteSnapshot(sessionId);
        return ResponseEntity.ok(Map.of("success", deleted));
    }
}
