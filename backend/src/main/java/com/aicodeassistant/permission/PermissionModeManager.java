package com.aicodeassistant.permission;

import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.websocket.WebSocketController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限模式管理器 — 只保存当前会话选择，具体安全裁决由 AuthorizationService 完成。
 * <p>
 * <ul>
 *   <li>仅保存会话选择；所有安全裁决由 AuthorizationService 完成</li>
 * </ul>
 */
@Service
public class PermissionModeManager {

    private static final Logger log = LoggerFactory.getLogger(PermissionModeManager.class);

    private final WebSocketController wsPusher;

    /** 当前会话权限模式 — sessionId → PermissionMode */
    private final ConcurrentHashMap<String, PermissionMode> sessionModes = new ConcurrentHashMap<>();

    public PermissionModeManager(@Lazy WebSocketController wsPusher) {
        this.wsPusher = wsPusher;
    }

    /**
     * 获取当前模式。
     */
    public PermissionMode getMode(String sessionId) {
        return sessionModes.getOrDefault(sessionId, PermissionMode.DEFAULT);
    }

    /**
     * 检查会话是否已显式设置过权限模式（区别于 getMode 的默认 DEFAULT 回退）。
     */
    public boolean hasExplicitMode(String sessionId) {
        return sessionModes.containsKey(sessionId);
    }

    /**
     * 设置权限模式。
     */
    public void setMode(String sessionId, PermissionMode mode) {
        PermissionMode previous = sessionModes.put(sessionId, mode);
        // 推送权限模式变更到前端（仅当模式实际变化时）
        if (previous != mode) {
            log.info("Permission mode changed: session={}, {} → {}", sessionId, previous, mode);
            if (wsPusher != null) {
                try {
                    wsPusher.pushToUser(sessionId, "permission_mode_changed",
                        java.util.Map.of("mode", mode.name(), "previous", String.valueOf(previous)));
                } catch (Exception e) {
                    log.debug("Failed to push permission_mode_changed (non-fatal): {}", e.getMessage());
                }
            }
        }
    }

    /** 清除会话模式 */
    public void clearSession(String sessionId) {
        sessionModes.remove(sessionId);
    }
}
