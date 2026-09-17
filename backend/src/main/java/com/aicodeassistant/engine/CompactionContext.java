package com.aicodeassistant.engine;

import com.aicodeassistant.llm.LlmCallContext;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;

/** Budget is for history after system/tools/output reservations. Deadline belongs to one logical phase. */
public record CompactionContext(String model, int contextWindow, int historyBudget, double tokenCharRatio,
                                LlmCallContext call, long deadlineNanos, BooleanSupplier stillValid,
                                java.util.concurrent.atomic.AtomicLong phaseDeadline,
                                java.util.concurrent.atomic.AtomicBoolean summaryAttempted) {
    public CompactionContext(String model, int window, int budget, double ratio,
                             LlmCallContext call, long deadline, BooleanSupplier valid) {
        this(model, window, budget, ratio, call, deadline, valid, new java.util.concurrent.atomic.AtomicLong(), new java.util.concurrent.atomic.AtomicBoolean());
    }
    public long startDeadline(long timeoutMillis) {
        long now = System.nanoTime();
        long duration = timeoutMillis * 1_000_000L;
        long end = now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
        phaseDeadline.compareAndSet(0, Math.min(end, deadlineNanos));
        return phaseDeadline.get();
    }
    public static CompactionContext unscoped(int window) {
        return new CompactionContext(null, window, (int)(window * .95), 3.5,
                LlmCallContext.unscoped(), Long.MAX_VALUE, () -> true);
    }
    public void checkValid() {
        if (call.cancellation().isCancelled() || Thread.currentThread().isInterrupted())
            throw new CancellationException("compaction_cancelled");
        if (!stillValid.getAsBoolean()) throw new IllegalStateException("RUN_TERMINATION_UNCONFIRMED: compaction_run_not_active");
    }
}
