package com.aicodeassistant.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CompactMetrics — 上下文溢出恢复指标追踪。
 * <p>
 * 线程安全：所有计数器使用 AtomicInteger/AtomicLong。
 * 用于追踪 ReactiveCompact 和 ContextCollapseDrain 的恢复效果。
 */
@Component
public class CompactMetrics {

    private static final Logger log = LoggerFactory.getLogger(CompactMetrics.class);

    private final AtomicInteger recoveryAttempts = new AtomicInteger(0);
    private final AtomicInteger recoverySuccesses = new AtomicInteger(0);
    private final AtomicLong totalCompressionRatioX1000 = new AtomicLong(0);
    private final AtomicLong totalRecoveryLatencyMs = new AtomicLong(0);
    private volatile Instant lastRecoveryTimestamp;

    /**
     * 记录一次恢复尝试。
     */
    public void recordRecoveryAttempt() {
        recoveryAttempts.incrementAndGet();
    }

    /**
     * 记录一次成功的恢复。
     *
     * @param compressionRatio 压缩比 (0.0 ~ 1.0)
     * @param latencyMs        恢复耗时（毫秒）
     */
    public void recordRecoverySuccess(double compressionRatio, long latencyMs) {
        recoverySuccesses.incrementAndGet();
        totalCompressionRatioX1000.addAndGet((long) (compressionRatio * 1000));
        totalRecoveryLatencyMs.addAndGet(latencyMs);
        lastRecoveryTimestamp = Instant.now();
        log.debug("Recovery success: compressionRatio={}, latencyMs={}", compressionRatio, latencyMs);
    }

    /**
     * 获取指标快照。
     */
    public Map<String, Object> getSnapshot() {
        int attempts = recoveryAttempts.get();
        int successes = recoverySuccesses.get();
        return Map.of(
                "recoveryAttempts", attempts,
                "recoverySuccesses", successes,
                "recoveryRate", attempts > 0 ? (double) successes / attempts : 0.0,
                "avgCompressionRatio", successes > 0
                        ? totalCompressionRatioX1000.get() / (successes * 1000.0) : 0.0,
                "avgRecoveryLatencyMs", successes > 0
                        ? totalRecoveryLatencyMs.get() / successes : 0L,
                "lastRecoveryTimestamp", lastRecoveryTimestamp != null
                        ? lastRecoveryTimestamp.toString() : "never"
        );
    }

    /**
     * 重置所有指标。
     */
    public void reset() {
        recoveryAttempts.set(0);
        recoverySuccesses.set(0);
        totalCompressionRatioX1000.set(0);
        totalRecoveryLatencyMs.set(0);
        lastRecoveryTimestamp = null;
    }
}
