package com.aicodeassistant.engine;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 层级化中断控制器，参考 JavaScript AbortController 设计。
 *
 * 设计特性：
 * - 父中断自动传播到所有子控制器（级联）
 * - 子中断不影响父（单向传播）
 * - 支持中断原因传播（AbortReason）
 * - 支持监听器通知（onAbort 回调）
 * - GC 友好（removeChild 支持手动清理）
 *
 * 使用示例：
 * <pre>
 * AbortController parent = new AbortController();
 * AbortController child = parent.createChild();
 * child.onAbort(reason -> log.info("Child aborted: {}", reason));
 * parent.abort(AbortReason.TIMEOUT); // 触发 child 的 onAbort 回调
 * </pre>
 */
public class AbortController {

    private static final Logger log = LoggerFactory.getLogger(AbortController.class);

    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private volatile AbortReason reason;
    private final List<AbortController> children = new CopyOnWriteArrayList<>();
    private final List<Consumer<AbortReason>> listeners = new CopyOnWriteArrayList<>();

    /**
     * 发出中断信号。仅第一次调用生效（CAS 保证幂等）。
     * 中断信号会级联传播到所有子控制器。
     */
    public void abort(AbortReason reason) {
        if (aborted.compareAndSet(false, true)) {
            this.reason = reason;
            // 通知所有监听器
            for (Consumer<AbortReason> listener : listeners) {
                try {
                    listener.accept(reason);
                } catch (Exception e) {
                    log.warn("Abort listener execution failed, continuing propagation", e);
                }
            }
            // 级联传播到子控制器
            for (AbortController child : children) {
                child.abort(reason);
            }
        }
    }

    /**
     * 创建子控制器。
     * - 父 abort 时自动传播到子
     * - 子 abort 不影响父
     * - 若父已中断，子直接标记为已中断
     */
    public AbortController createChild() {
        AbortController child = new AbortController();
        if (this.aborted.get()) {
            // 父已中断，子直接标记（快速路径）
            child.abort(this.reason);
        } else {
            this.children.add(child);
        }
        return child;
    }

    /**
     * 添加中断监听器。
     * 如果调用时已经中断，监听器将立即被调用。
     */
    public void onAbort(Consumer<AbortReason> listener) {
        if (aborted.get()) {
            // 已中断，立即回调
            listener.accept(reason);
        } else {
            listeners.add(listener);
        }
    }

    /**
     * 是否已中断。
     */
    public boolean isAborted() {
        return aborted.get();
    }

    /**
     * 获取中断原因（未中断时返回 null）。
     */
    public AbortReason getReason() {
        return reason;
    }

    /**
     * 移除子控制器（GC 友好）。
     * 建议在子 Agent 执行完毕后调用，避免内存泄漏。
     */
    public void removeChild(AbortController child) {
        children.remove(child);
    }

    /**
     * 获取当前子控制器数量（诊断用）。
     */
    public int getChildCount() {
        return children.size();
    }
}
