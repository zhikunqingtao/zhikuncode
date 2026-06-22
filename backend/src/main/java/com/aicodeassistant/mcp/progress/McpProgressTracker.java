package com.aicodeassistant.mcp.progress;

import com.aicodeassistant.websocket.WebSocketSessionManager;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理 MCP 工具调用的进度追踪 — M4 长操作支持。
 * <p>
 * 维护 progressToken -> 会话信息映射，接收 MCP 服务器
 * {@code notifications/progress} 通知后推送到前端。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>token 在 {@link com.aicodeassistant.mcp.McpToolAdapter} 调用工具前注册，调用结束（成功/失败/超时）后 unregister；</li>
 *   <li>未知 token 的通知被静默忽略，避免脏数据触发推送；</li>
 *   <li>使用 {@link SimpMessagingTemplate#convertAndSendToUser} 复用现有
 *       {@code /user/queue/messages} 通道（与 mcp_health_status 对齐），
 *       前端无需新增 STOMP 订阅。</li>
 * </ul>
 */
@Component
public class McpProgressTracker {

    private static final Logger log = LoggerFactory.getLogger(McpProgressTracker.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketSessionManager wsSessionManager;

    /** progressToken -> ProgressInfo */
    private final Map<String, ProgressInfo> activeProgress = new ConcurrentHashMap<>();

    public McpProgressTracker(SimpMessagingTemplate messagingTemplate,
                              WebSocketSessionManager wsSessionManager) {
        this.messagingTemplate = messagingTemplate;
        this.wsSessionManager = wsSessionManager;
    }

    /** 注册一个新的进度追踪 — 工具调用开始时调用 */
    public void registerProgress(String progressToken, String sessionId,
                                 String serverName, String toolName) {
        if (progressToken == null || progressToken.isEmpty()) return;
        activeProgress.put(progressToken, new ProgressInfo(sessionId, serverName, toolName));
    }

    /** 移除进度追踪 — 工具调用完成（成功/失败/超时）时调用 */
    public void unregisterProgress(String progressToken) {
        if (progressToken == null || progressToken.isEmpty()) return;
        activeProgress.remove(progressToken);
    }

    /** 查询活跃数量（测试与监控用） */
    public int activeCount() {
        return activeProgress.size();
    }

    /** 处理来自 MCP 服务器的 notifications/progress 通知 */
    public void handleProgressNotification(JsonNode notification) {
        if (notification == null) return;
        JsonNode params = notification.path("params");
        String progressToken = params.path("progressToken").asText("");

        ProgressInfo info = activeProgress.get(progressToken);
        if (info == null) {
            // 未知 token — 静默忽略（可能是迟到的通知或服务器误发）
            log.debug("Ignored progress notification for unknown token: {}", progressToken);
            return;
        }

        double progress = params.path("progress").asDouble(0);
        double total = params.path("total").asDouble(0);
        String message = params.path("message").asText("");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "mcp_tool_progress");
        payload.put("ts", System.currentTimeMillis());
        payload.put("progressToken", progressToken);
        payload.put("serverName", info.serverName());
        payload.put("toolName", info.toolName());
        payload.put("progress", progress);
        payload.put("total", total);
        payload.put("message", message);

        try {
            String sessionId = info.sessionId();
            String principal = sessionId != null
                    ? wsSessionManager.getPrincipalForSession(sessionId)
                    : null;
            if (principal != null) {
                messagingTemplate.convertAndSendToUser(principal, "/queue/messages", payload);
            } else {
                // 无法定位 principal 时记录日志并丢弃，避免跨会话广播
                log.debug("Skip MCP progress push: no principal for session {}", sessionId);
            }
        } catch (Exception e) {
            log.debug("Failed to push MCP progress to frontend: {}", e.getMessage());
        }
    }

    /** 进度追踪的元数据 */
    public record ProgressInfo(String sessionId, String serverName, String toolName) {}
}
