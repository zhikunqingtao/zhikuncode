package com.aicodeassistant.run;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import org.springframework.scheduling.annotation.Scheduled;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 运行追踪器 — 核心集成点。
 * <p>
 * 管理 RunEnvelope 生命周期和 RunEvent 序列号分配。
 * 内存中维护每个 run 的 seq 计数器，保证事件顺序。
 */
@Service
public class RunTracker {

    private static final Logger log = LoggerFactory.getLogger(RunTracker.class);

    private final RunEnvelopeRepository envelopeRepository;
    private final RunEventRepository eventRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** 内存 seq 计数器 — runId → AtomicInteger */
    private final ConcurrentHashMap<String, AtomicInteger> seqCounters = new ConcurrentHashMap<>();

    public RunTracker(RunEnvelopeRepository envelopeRepository,
                      RunEventRepository eventRepository,
                      ApplicationEventPublisher eventPublisher) {
        this.envelopeRepository = envelopeRepository;
        this.eventRepository = eventRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 启动一次新运行。
     */
    public RunEnvelope startRun(String sessionId, String parentRunId, String agentType, String model) {
        RunEnvelope envelope = RunEnvelope.start(sessionId, parentRunId, agentType, model);
        envelopeRepository.insert(envelope);
        seqCounters.put(envelope.id(), new AtomicInteger(0));
        log.info("Started run: id={}, session={}, agent={}, model={}", envelope.id(), sessionId, agentType, model);
        recordEvent(envelope.id(), "run_started", Map.of(
                "sessionId", sessionId,
                "agentType", agentType != null ? agentType : "unknown",
                "model", model != null ? model : "unknown"));
        return envelope;
    }

    /**
     * 记录事件 — 自增 seq。
     */
    public RunEvent recordEvent(String runId, String eventType, Object data) {
        AtomicInteger counter = seqCounters.computeIfAbsent(runId, k -> {
            // 回退: 从数据库恢复 seq
            int maxSeq = eventRepository.getMaxSeq(runId);
            return new AtomicInteger(maxSeq);
        });
        int seq = counter.incrementAndGet();
        RunEvent event = RunEvent.of(runId, seq, eventType, data);
        eventRepository.append(event);
        return event;
    }

    /**
     * 完成运行 — 附带统计数据。
     */
    public void completeRun(String runId, int totalTokens, double totalCost, int toolCallCount, int turnCount) {
        envelopeRepository.findById(runId).ifPresent(envelope -> {
            RunEnvelope completed = envelope.complete(totalTokens, totalCost, toolCallCount, turnCount);
            envelopeRepository.updateStatus(runId, completed);
            recordEvent(runId, "run_completed", Map.of(
                    "totalTokens", String.valueOf(totalTokens),
                    "totalCost", String.valueOf(totalCost),
                    "toolCallCount", String.valueOf(toolCallCount),
                    "turnCount", String.valueOf(turnCount)));
            seqCounters.remove(runId);
            log.info("Completed run: id={}, tokens={}, cost={}", runId, totalTokens, totalCost);

            // Publish event for async artifact verification
            eventPublisher.publishEvent(new RunCompletedEvent(
                this, runId, envelope.sessionId(), totalTokens, totalCost, toolCallCount, turnCount));
        });
    }

    /**
     * 运行失败。
     */
    public void failRun(String runId, String errorSummary) {
        envelopeRepository.findById(runId).ifPresent(envelope -> {
            RunEnvelope failed = envelope.fail(errorSummary);
            envelopeRepository.updateStatus(runId, failed);
            recordEvent(runId, "run_failed", Map.of("error", errorSummary != null ? errorSummary : "unknown"));
            seqCounters.remove(runId);
            log.warn("Failed run: id={}, error={}", runId, errorSummary);
        });
    }

    /**
     * 中止运行。
     */
    public void abortRun(String runId, String reason) {
        envelopeRepository.findById(runId).ifPresent(envelope -> {
            RunEnvelope aborted = envelope.abort(reason);
            envelopeRepository.updateStatus(runId, aborted);
            recordEvent(runId, "run_aborted", Map.of("reason", reason != null ? reason : "unknown"));
            seqCounters.remove(runId);
            log.warn("Aborted run: id={}, reason={}", runId, reason);
        });
    }

    /**
     * 获取运行信封。
     */
    public Optional<RunEnvelope> getRun(String runId) {
        return envelopeRepository.findById(runId);
    }

    /**
     * 启动时清理残留的 running 状态 — 标记为 aborted。
     */
    @PostConstruct
    public void cleanupStaleRuns() {
        int count = envelopeRepository.markStaleRunsAborted("server_restart");
        if (count > 0) {
            log.info("Cleaned up {} stale running envelopes on startup", count);
        }
        // Clear any stale seq counters from previous runs
        seqCounters.clear();
    }

    /**
     * 定期清理 seqCounters 中已不再活跃的运行条目 — 防止内存泄漏。
     * 每 5 分钟执行一次，移除不属于 running 状态的残留计数器。
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void cleanupStaleSeqCounters() {
        if (seqCounters.isEmpty()) {
            return;
        }
        Set<String> activeRunIds = envelopeRepository.findRunning().stream()
                .map(RunEnvelope::id)
                .collect(Collectors.toSet());
        int removed = 0;
        var it = seqCounters.keySet().iterator();
        while (it.hasNext()) {
            if (!activeRunIds.contains(it.next())) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Cleaned up {} stale seq counters", removed);
        }
    }
}
