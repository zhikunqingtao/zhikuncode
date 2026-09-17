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

    private final java.util.Set<String> retainedRuns = ConcurrentHashMap.newKeySet();

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
        register(agentId, sessionId, null, prompt, outputFile);
    }

    public void register(String agentId, String sessionId, String parentRunId, String prompt, String outputFile) {
        AgentStatus status = new AgentStatus(
                agentId, sessionId, parentRunId, prompt, outputFile, "running",
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
        String status = result.status();
        if (status == null || !java.util.Set.of("completed", "failed", "timeout", "interrupted", "max_turns").contains(status)) {
            status = "failed";
        }
        finish(agentId, status, "completed".equals(status) ? null : truncate(result.result(), 500), result.result());
    }

    public void markFailed(String agentId, String error) {
        finish(agentId, "failed", error != null ? error : "unknown", null);
    }

    private void finish(String agentId, String status, String error, String output) {
        var changed = new java.util.concurrent.atomic.AtomicReference<AgentStatus>();
        activeAgents.computeIfPresent(agentId, (id, current) -> {
            if (!"running".equals(current.status())) return current;
            var terminal = new AgentStatus(current.agentId(), current.sessionId(), current.parentRunId(),
                    current.prompt(), current.outputFile(), status, current.startedAt(), Instant.now(), error);
            changed.set(terminal);
            return terminal;
        });
        AgentStatus terminal = changed.get();
        if (terminal == null) return;
        signalSession(terminal.sessionId());
        pushEvent(agentId, "completed".equals(status) ? "agent_completed" : "agent_failed",
                Map.of("agentId", agentId, "status", status, "resultPreview", truncate(output, 500),
                        "error", error == null ? "" : error));
        log.info("Background agent terminal: agentId={}, parentRunId={}, status={}", agentId, terminal.parentRunId(), status);
    }

    public List<AgentStatus> listForRun(String sessionId, String runId) {
        if (runId == null) return List.of();
        return activeAgents.values().stream()
                .filter(a -> sessionId.equals(a.sessionId()) && runId.equals(a.parentRunId()))
                .sorted(java.util.Comparator.comparing(AgentStatus::startedAt).thenComparing(AgentStatus::agentId))
                .toList();
    }

    // Keep result files available while their parent Run may still need to collect them.
    public void retainRun(String runId) { if (runId != null) retainedRuns.add(runId); }
    public void releaseRun(String runId) { if (runId != null) retainedRuns.remove(runId); }

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
    public enum WaitResult { COMPLETED, TIMED_OUT, CANCELLED, INTERRUPTED }

    public boolean awaitAllAgents(String sessionId, Duration timeout, AbortContext abortContext) {
        return await(sessionId, timeout, abortContext, () -> getActiveAgentIds(sessionId).isEmpty()) == WaitResult.COMPLETED;
    }

    public WaitResult awaitRun(String sessionId, String runId, Duration timeout, AbortContext abortContext) {
        return await(sessionId, timeout, abortContext,
                () -> listForRun(sessionId, runId).stream().noneMatch(a -> "running".equals(a.status())));
    }

    private WaitResult await(String sessionId, Duration timeout, AbortContext abortContext,
                             java.util.function.BooleanSupplier complete) {
        ReentrantLock lock = sessionLocks.computeIfAbsent(sessionId, k -> new ReentrantLock());
        Condition condition = sessionConditions.computeIfAbsent(sessionId, k -> lock.newCondition());
        long deadline = System.nanoTime() + timeout.toNanos();
        var registration = abortContext == null ? (com.aicodeassistant.llm.CancellationSignal.Registration) () -> { }
                : abortContext.register(() -> signalSession(sessionId));
        lock.lock();
        try (registration) {
            while (true) {
                if (abortContext != null && abortContext.isAborted()) return WaitResult.CANCELLED;
                if (Thread.currentThread().isInterrupted()) return WaitResult.INTERRUPTED;
                if (complete.getAsBoolean()) return WaitResult.COMPLETED;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return WaitResult.TIMED_OUT;
                try {
                    condition.awaitNanos(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return WaitResult.INTERRUPTED;
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
                condition.signalAll();
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
            if ((status.parentRunId() == null || !retainedRuns.contains(status.parentRunId()))
                    && !"running".equals(status.status())
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
            String agentId, String sessionId, String parentRunId, String prompt, String outputFile,
            String status, Instant startedAt, Instant completedAt, String error
    ) {}
}
