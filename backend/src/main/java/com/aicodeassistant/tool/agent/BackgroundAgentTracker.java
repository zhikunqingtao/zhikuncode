package com.aicodeassistant.tool.agent;

import com.aicodeassistant.engine.AbortContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 后台代理追踪器 — 管理异步启动的子代理的生命周期和进度。
 * <p>
 * 通过 STOMP 向前端推送代理状态变更事件。
 * 推送路径统一为 /topic/session/{sessionId}，
 *
 */
@Component
public class BackgroundAgentTracker {

    private static final Logger log = LoggerFactory.getLogger(BackgroundAgentTracker.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final Map<String, AgentStatus> activeAgents = new ConcurrentHashMap<>();

    // 会话级锁 — 用于 awaitAllAgents 等待通知
    private final ConcurrentHashMap<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Condition> sessionConditions = new ConcurrentHashMap<>();

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
            // 通知等待线程
            signalSession(current.sessionId());
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
            // 通知等待线程
            signalSession(current.sessionId());
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

    /**
     * 获取指定会话中仍在运行的代理 ID 列表。
     */
    public List<String> getActiveAgentIds(String sessionId) {
        return activeAgents.values().stream()
                .filter(a -> sessionId.equals(a.sessionId()) && "running".equals(a.status()))
                .map(AgentStatus::agentId)
                .toList();
    }

    /**
     * 等待指定会话的所有后台代理完成。
     * @return true=全部完成, false=超时或被 abort
     */
    public boolean awaitAllAgents(String sessionId, Duration timeout, AbortContext abortContext) {
        ReentrantLock lock = sessionLocks.computeIfAbsent(sessionId, k -> new ReentrantLock());
        Condition condition = sessionConditions.computeIfAbsent(sessionId, k -> lock.newCondition());

        long deadline = System.nanoTime() + timeout.toNanos();
        lock.lock();
        try {
            while (true) {
                // 检查是否全部完成
                List<String> running = getActiveAgentIds(sessionId);
                if (running.isEmpty()) {
                    return true;
                }

                // 检查 abort 信号
                if (abortContext != null && abortContext.isAborted()) {
                    log.info("Abort signal received while waiting for agents in session {}", sessionId);
                    return false;
                }

                // 计算剩余等待时间
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    log.warn("Timeout waiting for {} agents in session {}", running.size(), sessionId);
                    return false;
                }

                // 等待信号（被 markCompleted/markFailed 唤醒）
                try {
                    condition.awaitNanos(remainingNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 清理会话相关的所有追踪数据。
     */
    public void removeSession(String sessionId) {
        activeAgents.entrySet().removeIf(e -> sessionId.equals(e.getValue().sessionId()));
        sessionLocks.remove(sessionId);
        sessionConditions.remove(sessionId);
        log.debug("Removed tracking data for session {}", sessionId);
    }

    /**
     * 唤醒等待指定会话代理完成的线程。
     */
    private void signalSession(String sessionId) {
        ReentrantLock lock = sessionLocks.get(sessionId);
        Condition condition = sessionConditions.get(sessionId);
        if (lock != null && condition != null) {
            lock.lock();
            try {
                condition.signal();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 每 10 分钟清理超过 30 分钟的已完成/失败代理记录。
     */
    @Scheduled(fixedRate = 600_000)
    public void cleanup() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(30));
        int removed = 0;
        var it = activeAgents.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            AgentStatus status = entry.getValue();
            if (!"running".equals(status.status())
                    && status.completedAt() != null
                    && status.completedAt().isBefore(cutoff)) {
                // 清理对应的输出文件（确保 QueryEngine.formatAgentResults() 已读取完毕）
                deleteOutputFileIfExists(status.outputFile());
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Cleaned up {} stale agent records (including output files)", removed);
        }
    }

    /**
     * 安全删除代理输出文件。
     * 在定时清理时调用，此时距代理完成已超过 30 分钟，
     * 确保 QueryEngine.formatAgentResults() 已有足够时间读取该文件。
     */
    private void deleteOutputFileIfExists(String outputFile) {
        if (outputFile == null || outputFile.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(outputFile);
            if (Files.deleteIfExists(path)) {
                log.debug("Deleted agent output file: {}", outputFile);
            }
        } catch (Exception e) {
            log.warn("Failed to delete agent output file {}: {}", outputFile, e.getMessage());
        }
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
