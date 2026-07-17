package com.aicodeassistant.controller;

import com.aicodeassistant.engine.AbortReason;
import com.aicodeassistant.run.RunEnvelope;
import com.aicodeassistant.run.RunEnvelopeRepository;
import com.aicodeassistant.run.RunTerminationCoordinator;
import com.aicodeassistant.websocket.WebSocketController;
import com.aicodeassistant.websocket.WebSocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 远程控制 REST API — 供手机端紧急中断使用。
 * <p>
 * 安全模型: 完全复用 {@link com.aicodeassistant.config.RemoteAccessSecurityFilter}
 * 三层递进认证（localhost 免认证 / 局域网 Token / Cookie），无需额外鉴权。
 * <p>
 * 使用场景: 用户在电脑上部署 ZhikunCode 后，通过手机浏览器访问
 * {@code http://<局域网IP>:8080/remote.html?token=xxx}，
 * 查看 AI 运行状态并在紧急情况下一键中断。
 */
@RestController
@RequestMapping("/api/remote")
public class RemoteControlController {

    private static final Logger log = LoggerFactory.getLogger(RemoteControlController.class);
    private static final Instant START_TIME = Instant.now();

    private final WebSocketSessionManager wsSessionManager;
    private final WebSocketController wsController;
    private final RunEnvelopeRepository runs;
    private final RunTerminationCoordinator termination;

    public RemoteControlController(WebSocketSessionManager wsSessionManager,
                                   WebSocketController wsController,
                                   RunEnvelopeRepository runs,
                                   RunTerminationCoordinator termination) {
        this.wsSessionManager = wsSessionManager;
        this.wsController = wsController;
        this.runs = runs;
        this.termination = termination;
    }

    /**
     * 获取当前活跃会话状态 — 供手机端轮询展示。
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Set<String> activeIds = wsSessionManager.getActiveSessionIds();

        List<Map<String, Object>> sessions = new ArrayList<>();
        for (String sessionId : activeIds) {
            sessions.add(Map.of(
                    "sessionId", sessionId,
                    "online", true
            ));
        }

        Duration uptime = Duration.between(START_TIME, Instant.now());
        String uptimeStr = formatUptime(uptime);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeSessions", activeIds.size());
        result.put("sessions", sessions);
        result.put("serverUptime", uptimeStr);

        return ResponseEntity.ok(result);
    }

    /**
     * 紧急中断所有活跃会话 — 一键停止 AI 执行。
     * <p>
     * 执行步骤:
     * <ol>
     *   <li>遍历所有活跃 WebSocket 会话</li>
     *   <li>对每个活动 Run 调用统一终止协调器</li>
     *   <li>通过 WebSocket 推送 interrupt_ack 到已连接前端</li>
     *   <li>取消所有挂起的权限请求</li>
     * </ol>
     */
    @PostMapping("/interrupt")
    public ResponseEntity<Map<String, Object>> interruptAll() {
        Set<String> activeIds = wsSessionManager.getActiveSessionIds();
        int count = 0;
        boolean anyError = false;

        for (String sessionId : activeIds) {
            try {
                // Database Run state is the cancellation authority. Wake-ups and
                // process termination happen only after winning requestCancel.
                for (RunEnvelope run : runs.findBySession(sessionId, 100)) {
                    if (run.status().terminal()) continue;
                    termination.cancelByUser(run.id(), "remote_user_cancelled");
                }
                // 2. 推送 interrupt_ack 到已连接前端（使其 UI 回到 idle 状态）
                wsController.pushToUser(sessionId, "interrupt_ack",
                        Map.of("reason", AbortReason.USER_INTERRUPT.name()));

                count++;
                log.info("Remote interrupt: sessionId={}", sessionId);
            } catch (Exception e) {
                anyError = true;
                log.warn("Failed to interrupt session {}: {}", sessionId, e.getMessage());
            }
        }

        log.info("Remote interrupt completed: {} sessions interrupted", count);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("interrupted", count > 0 || activeIds.isEmpty());
        result.put("sessionCount", count);
        if (anyError) {
            result.put("partialFailure", true);
        }

        return ResponseEntity.ok(result);
    }

    private String formatUptime(Duration uptime) {
        long hours = uptime.toHours();
        long minutes = uptime.toMinutesPart();
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }
}
