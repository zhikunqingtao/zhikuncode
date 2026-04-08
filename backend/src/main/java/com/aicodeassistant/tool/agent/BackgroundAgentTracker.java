package com.aicodeassistant.tool.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后台代理追踪器 — 管理异步启动的子代理的生命周期和进度。
 * <p>
 * 通过 STOMP 向前端推送代理状态变更事件。
 * 推送路径统一为 /topic/session/{sessionId}，
 * 对齐 §6.2 定义的前端标准订阅目标。
 *
 * @see <a href="SPEC §4.1.1d.2">BackgroundAgentTracker</a>
 */
@Component
public class BackgroundAgentTracker {

    private static final Logger log = LoggerFactory.getLogger(BackgroundAgentTracker.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final Map<String, AgentStatus> activeAgents = new ConcurrentHashMap<>();

    public BackgroundAgentTracker(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 注册后台代理。
     *
     * @param agentId    代理唯一标识
     * @param sessionId  所属会话 ID
     * @param prompt     代理任务描述
     * @param outputFile 输出文件路径
     */
    public void register(String agentId, String sessionId, String prompt, String outputFile) {
        AgentStatus status = new AgentStatus(
                agentId, sessionId, prompt, outputFile, "running",
                Instant.now(), null, null);
        activeAgents.put(agentId, status);
        pushEvent(agentId, "agent_started", Map.of(
                "agentId", agentId, "prompt", prompt));
        log.info("Background agent registered: {} (session: {})", agentId, sessionId);
    }

    /**
     * 标记代理完成。
     */
    public void markCompleted(String agentId, SubAgentExecutor.AgentResult result) {
        AgentStatus current = activeAgents.get(agentId);
        if (current != null) {
            activeAgents.put(agentId, current.withStatus("completed", Instant.now()));
            pushEvent(agentId, "agent_completed", Map.of(
                    "agentId", agentId,
                    "resultPreview", truncate(result.result(), 500)));
            log.info("Background agent completed: {}", agentId);
        }
    }

    /**
     * 标记代理失败。
     */
    public void markFailed(String agentId, String error) {
        AgentStatus current = activeAgents.get(agentId);
        if (current != null) {
            AgentStatus failed = new AgentStatus(
                    current.agentId(), current.sessionId(), current.prompt(),
                    current.outputFile(), "failed", current.startedAt(),
                    Instant.now(), error);
            activeAgents.put(agentId, failed);
            pushEvent(agentId, "agent_failed", Map.of(
                    "agentId", agentId, "error", error != null ? error : "unknown"));
            log.warn("Background agent failed: {} — {}", agentId, error);
        }
    }

    /**
     * 列出指定会话的活跃代理。
     */
    public List<AgentStatus> listActive(String sessionId) {
        return activeAgents.values().stream()
                .filter(a -> "running".equals(a.status()))
                .filter(a -> sessionId == null || sessionId.equals(a.sessionId()))
                .toList();
    }

    /**
     * 获取指定代理状态。
     */
    public AgentStatus getStatus(String agentId) {
        return activeAgents.get(agentId);
    }

    // v1.49.0 修正 (F3-06): 推送路径统一为 /topic/session/{sessionId}
    private void pushEvent(String agentId, String type, Map<String, Object> payload) {
        AgentStatus agent = activeAgents.get(agentId);
        String sessionId = agent != null ? agent.sessionId() : "unknown";
        try {
            messagingTemplate.convertAndSend(
                    "/topic/session/" + sessionId,
                    Map.of("type", "task_update",
                            "agentId", agentId,
                            "eventType", type,
                            "data", payload));
        } catch (Exception e) {
            log.warn("Failed to push agent event: {} — {}", type, e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : (s != null ? s : "");
    }

    /**
     * 代理状态记录。
     */
    public record AgentStatus(
            String agentId, String sessionId, String prompt, String outputFile,
            String status, Instant startedAt, Instant completedAt, String error
    ) {
        /** 创建新状态的副本 */
        AgentStatus withStatus(String newStatus, Instant time) {
            return new AgentStatus(agentId, sessionId, prompt, outputFile,
                    newStatus, startedAt, time,
                    "completed".equals(newStatus) ? null : error);
        }
    }
}
