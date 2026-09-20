package com.aicodeassistant.permission;

import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.websocket.WebSocketController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;

/**
 * 权限模式管理器 — 在会话数据库中保存选择，具体安全裁决由 AuthorizationService 完成。
 * <p>
 * <ul>
 *   <li>仅保存会话选择；所有安全裁决由 AuthorizationService 完成</li>
 * </ul>
 */
@Service
public class PermissionModeManager {

    private static final Logger log = LoggerFactory.getLogger(PermissionModeManager.class);

    private final WebSocketController wsPusher;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public PermissionModeManager(@Lazy WebSocketController wsPusher,
                                 @Qualifier("projectJdbcTemplate") JdbcTemplate jdbc,
                                 @Qualifier("projectTransactionManager") PlatformTransactionManager transactionManager) {
        this.wsPusher = wsPusher;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 从数据库读取会话模式；不存在或无效的权限明确报错。
     */
    public PermissionMode getMode(String sessionId) {
        var modes = jdbc.query("SELECT permission_mode FROM sessions WHERE id = ?",
                (rs, row) -> PermissionMode.valueOf(rs.getString("permission_mode")), sessionId);
        if (modes.size() != 1) throw new IllegalArgumentException("Session not found: " + sessionId);
        return modes.getFirst();
    }

    /**
     * 持久化权限模式后再通知前端；保存失败时不确认切换。
     */
    public void setMode(String sessionId, PermissionMode mode) {
        setMode(sessionId, mode, null);
    }

    public void setMode(String sessionId, PermissionMode mode, String requestId) {
        Objects.requireNonNull(mode, "mode");
        transaction.executeWithoutResult(status -> {
            PermissionMode previous = getMode(sessionId);
            int updated = jdbc.update("UPDATE sessions SET permission_mode = ?, updated_at = ? WHERE id = ?",
                    mode.name(), Instant.now().toString(), sessionId);
            if (updated != 1) throw new IllegalArgumentException("Session not found: " + sessionId);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { notifyModeChanged(sessionId, previous, mode, requestId); }
            });
        });
    }

    private void notifyModeChanged(String sessionId, PermissionMode previous, PermissionMode mode, String requestId) {
        if (mode == PermissionMode.AUTO_APPROVE || previous == PermissionMode.AUTO_APPROVE) {
            log.warn("Permission mode changed: session={}, {} → {}", sessionId, previous, mode);
        } else {
            log.info("Permission mode changed: session={}, {} → {}", sessionId, previous, mode);
        }
        if (wsPusher != null) {
            try {
                var event = new java.util.HashMap<String, Object>();
                event.put("mode", mode.name()); event.put("previous", previous.name());
                if (requestId != null) event.put("requestId", requestId);
                wsPusher.pushToUser(sessionId, "permission_mode_changed", event);
            } catch (Exception e) {
                log.debug("Failed to push permission_mode_changed (non-fatal): {}", e.getMessage());
            }
        }
    }

    /** 重置会话为标准授权；会话已删除时无需处理。 */
    public void clearSession(String sessionId) {
        jdbc.update("UPDATE sessions SET permission_mode = 'DEFAULT' WHERE id = ?", sessionId);
    }
}
