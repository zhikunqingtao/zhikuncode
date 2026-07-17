package com.aicodeassistant.engine;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 中断上下文。
 * <p>
 * 技术栈适配:
 * - 默认使用 AbortController + signal.aborted + signal.reason
 * - Java 21 等价: volatile + CompletableFuture (不用 Thread.interrupt() 避免与 CF 冲突)
 * <p>
 * 注意: 可变状态，不适合用 record。
 *
 */
public class AbortContext implements com.aicodeassistant.llm.CancellationSignal {

    private static final Logger log = LoggerFactory.getLogger(AbortContext.class);

    private final long createdAt = System.currentTimeMillis();
    private static final long DEFAULT_EXPIRY_MS = 3600_000L; // 1 hour

    private volatile boolean aborted = false;
    private volatile AbortReason reason;
    private final CompletableFuture<AbortReason> abortFuture = new CompletableFuture<>();
    private final List<Runnable> abortCallbacks = new CopyOnWriteArrayList<>();

    /**
     * 触发中断 — volatile 写保证 happens-before。
     */
    public void abort(AbortReason r) {
        List<Runnable> callbacks;
        synchronized (this) {
            if (this.aborted) return;
            this.reason = r;
            this.aborted = true;
            callbacks = List.copyOf(abortCallbacks);
            abortCallbacks.clear();
        }
        abortFuture.complete(r);
        for (Runnable callback : callbacks) {
            try {
                callback.run();
            } catch (Exception e) {
                log.warn("Abort callback failed for reason={}, continuing: {}",
                        r, e.getMessage(), e);
            }
        }
    }

    /**
     * 注册 abort 回调，用于中断正在进行的 HTTP 请求等。
     * 如果已经 aborted，立即执行回调。
     */
    public com.aicodeassistant.llm.CancellationSignal.Registration register(Runnable callback) {
        java.util.Objects.requireNonNull(callback, "callback");
        synchronized (this) {
            if (!aborted) {
                abortCallbacks.add(callback);
                return () -> abortCallbacks.remove(callback);
            }
        }
        callback.run();
        return () -> { };
    }

    /** Compatibility adapter for older cancellation-aware tools. */
    public void onAbortDo(Runnable callback) {
        register(callback);
    }

    public boolean isAborted() {
        return aborted;
    }

    @Override
    public boolean isCancelled() { return isAborted(); }

    public AbortReason getReason() {
        return reason;
    }

    /**
     * Only an explicit stop action is represented as a user-interrupt message.
     * Submit replacement, timeout, internal failure and shutdown have their own
     * structured exit reasons and must not be mislabeled as user actions.
     */
    public boolean shouldInjectInterruptMessage() {
        return reason == AbortReason.USER_INTERRUPT;
    }

    /**
     * 等待中断事件。
     * 在 VirtualThread 上调用 join()/get() 不会阻塞平台线程。
     */
    public CompletableFuture<AbortReason> onAbort() {
        return abortFuture;
    }

    /**
     * 判断此 AbortContext 是否已过期（超过 1 小时）。
     * 用于定期清理泄漏的 AbortContext。
     */
    public boolean isExpired() {
        return System.currentTimeMillis() - createdAt > DEFAULT_EXPIRY_MS;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}
