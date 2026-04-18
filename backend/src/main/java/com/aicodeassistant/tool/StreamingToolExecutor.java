package com.aicodeassistant.tool;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 流式工具执行器 — 管理工具的并发执行和有序结果返回。
 * <p>
 * 核心机制:
 * <ol>
 *   <li>每个工具声明 isConcurrencySafe(input) — 是否可与其他工具并发执行</li>
 *   <li>安全工具(如Read/Glob/Grep)可并行执行多个</li>
 *   <li>非安全工具(如Bash写入命令)独占执行</li>
 *   <li>乱序执行，但按原始顺序有序返回结果(FIFO缓冲)</li>
 * </ol>
 *
 * @see <a href="SPEC §3.2.2">工具并发执行引擎</a>
 */
@Service
public class StreamingToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(StreamingToolExecutor.class);

    private final ToolExecutionPipeline pipeline;
    private final MeterRegistry meterRegistry;
    private final AtomicInteger activeVirtualThreads = new AtomicInteger(0);
    private final Counter toolExecutionTotal;
    private final Counter toolExecutionErrors;

    public StreamingToolExecutor(ToolExecutionPipeline pipeline, MeterRegistry meterRegistry) {
        this.pipeline = pipeline;
        this.meterRegistry = meterRegistry;

        // ★ Virtual Thread 活跃数 Gauge
        Gauge.builder("zhiku.tool.virtual_threads.active", activeVirtualThreads, AtomicInteger::get)
                .description("Active virtual threads executing tools")
                .register(meterRegistry);

        // ★ 工具执行总次数和错误次数 Counter
        this.toolExecutionTotal = Counter.builder("zhiku.tool.executions.total")
                .description("Total tool executions")
                .register(meterRegistry);
        this.toolExecutionErrors = Counter.builder("zhiku.tool.executions.errors")
                .description("Total tool execution errors")
                .register(meterRegistry);
    }

    /** 工具状态机 */
    public enum ToolState {
        QUEUED,
        EXECUTING,
        COMPLETED,
        YIELDED
    }

    /** 被追踪的工具调用 */
    public static class TrackedTool {
        private final String toolUseId;
        private final Tool tool;
        private final ToolInput input;
        private final ToolUseContext context;
        private volatile ToolState state;
        private volatile ToolResult result;
        private volatile ToolUseContext updatedContext;  // contextModifier 产生的更新上下文

        public TrackedTool(String toolUseId, Tool tool, ToolInput input, ToolUseContext context) {
            this.toolUseId = toolUseId;
            this.tool = tool;
            this.input = input;
            this.context = context;
            this.state = ToolState.QUEUED;
        }

        public String getToolUseId() { return toolUseId; }
        public Tool getTool() { return tool; }
        public ToolInput getInput() { return input; }
        public ToolState getState() { return state; }
        public ToolResult getResult() { return result; }
        public ToolUseContext getUpdatedContext() { return updatedContext; }
    }

    /**
     * 创建一个新的执行会话（每次 runTools 调用一个新实例）。
     *
     * @param initialContext 初始工具使用上下文
     */
    public ExecutionSession newSession(ToolUseContext initialContext) {
        return new ExecutionSession(initialContext);
    }

    /**
     * 执行会话 — 非单例，每次 runTools 调用创建一个。
     */
    public class ExecutionSession {

        private final Queue<TrackedTool> queue = new ConcurrentLinkedQueue<>();
        private final List<TrackedTool> tracked = new CopyOnWriteArrayList<>();
        private final AtomicInteger active = new AtomicInteger(0);
        private volatile boolean sessionDiscarded = false;
        // ★ 新增：会话级当前上下文，支持 CAS 更新（contextModifier 传播）
        private final AtomicReference<ToolUseContext> currentContext;

        // ★ 有参构造器，接收初始 context
        public ExecutionSession(ToolUseContext initialContext) {
            this.currentContext = new AtomicReference<>(initialContext);
        }

        /** 添加工具到执行队列 */
        public void addTool(Tool tool, ToolInput input, String toolUseId, ToolUseContext context) {
            // ★ 修改：使用会话级 currentContext 而非调用方传入的原始 context
            ToolUseContext effectiveContext = currentContext.get();
            TrackedTool tt = new TrackedTool(toolUseId, tool, input, effectiveContext);
            tracked.add(tt);
            queue.add(tt);
            processQueue();
        }

        private boolean canExecute(TrackedTool tt) {
            if (active.get() == 0) return true;
            boolean isSafe = tt.tool.isConcurrencySafe(tt.input);
            if (!isSafe) return false;
            return tracked.stream()
                    .filter(t -> t.state == ToolState.EXECUTING)
                    .allMatch(t -> t.tool.isConcurrencySafe(t.input));
        }

        private void processQueue() {
            while (!queue.isEmpty()) {
                TrackedTool next = queue.peek();
                if (!canExecute(next)) break;

                queue.poll();
                active.incrementAndGet();
                next.state = ToolState.EXECUTING;

                Thread.ofVirtual().name("zhiku-tool-" + next.tool.getName()).start(() -> {
                    activeVirtualThreads.incrementAndGet();
                    Timer.Sample sample = Timer.start(meterRegistry);
                    try {
                        if (sessionDiscarded) {
                            next.result = ToolResult.error(
                                    "<tool_use_error>Tool execution discarded</tool_use_error>");
                        } else {
                            ToolExecutionResult execResult = pipeline.execute(next.tool, next.input,
                                    next.context.withToolUseId(next.toolUseId),
                                    next.context.permissionNotifier());
                            next.result = execResult.result();
                            next.updatedContext = execResult.updatedContext();
                        }
                        next.state = ToolState.COMPLETED;

                        // ★ 新增：applyContextModifier 传播（在 state 更新之后）
                        if (next.updatedContext != null && !next.tool.isConcurrencySafe(next.input)) {
                            applyContextModifier(next.updatedContext);
                        } else if (next.updatedContext != null && next.tool.isConcurrencySafe(next.input)) {
                            log.warn("Tool '{}' is concurrencySafe but returned contextModifier — ignored",
                                    next.tool.getName());
                        }

                        toolExecutionTotal.increment();
                    } catch (Exception e) {
                        next.result = ToolResult.error(
                                "<tool_use_error>Execution error: " + e.getMessage() + "</tool_use_error>");
                        next.state = ToolState.COMPLETED;
                        toolExecutionTotal.increment();
                        toolExecutionErrors.increment();
                    } finally {
                        // ★ 记录工具执行耗时 (按工具名称分 tag)
                        sample.stop(Timer.builder("zhiku.tool.execution_time")
                                .tag("tool", next.tool != null ? next.tool.getName() : "unknown")
                                .description("Tool execution time")
                                .register(meterRegistry));
                        activeVirtualThreads.decrementAndGet();
                        active.decrementAndGet();
                        processQueue();
                    }
                });
            }
        }

        /** 按原始顺序 yield 已完成的结果 */
        public List<TrackedTool> yieldCompleted() {
            List<TrackedTool> yielded = new ArrayList<>();
            for (TrackedTool t : tracked) {
                if (t.state == ToolState.YIELDED) continue;
                if (t.state != ToolState.COMPLETED) break;
                t.state = ToolState.YIELDED;
                yielded.add(t);
            }
            return yielded;
        }

        /** 是否所有工具都已完成 */
        public boolean isAllCompleted() {
            return tracked.stream().allMatch(t ->
                    t.state == ToolState.COMPLETED || t.state == ToolState.YIELDED);
        }

        /** 总工具数 */
        public int totalCount() { return tracked.size(); }

        /** 已完成数 */
        public int completedCount() {
            return (int) tracked.stream()
                    .filter(t -> t.state == ToolState.COMPLETED || t.state == ToolState.YIELDED)
                    .count();
        }

        /** 丢弃所有挂起工具 */
        public void discard() {
            this.sessionDiscarded = true;
        }

        /** 是否已被丢弃 */
        public boolean isDiscarded() {
            return sessionDiscarded;
        }

        /**
         * 直接添加一个已完成的 error result（工具未找到等场景）。
         * 对齐原版 StreamingToolExecutor.ts:78-101
         */
        public void addErrorResult(String toolUseId, String errorContent) {
            TrackedTool tt = new TrackedTool(toolUseId, null, null, null);
            tt.result = ToolResult.error(errorContent);
            tt.state = ToolState.COMPLETED;
            tracked.add(tt);
        }

        /** 是否有未完成的工具 */
        public boolean hasUnfinishedTools() {
            return tracked.stream().anyMatch(t ->
                    t.state == ToolState.QUEUED || t.state == ToolState.EXECUTING);
        }

        /**
         * 通过 CAS 循环安全地更新会话级上下文。
         * 若多个非并发安全工具顺序完成，CAS 保证每次更新基于最新状态。
         *
         * 超时保护：最多重试 100 次 CAS，防止异常场景下无限循环。
         */
        private void applyContextModifier(ToolUseContext newContext) {
            int maxRetries = 100;  // CAS 超时保护
            int attempt = 0;
            ToolUseContext prev;
            do {
                if (++attempt > maxRetries) {
                    log.warn("CAS loop exceeded {} retries for contextModifier, using last known context", maxRetries);
                    currentContext.set(newContext);  // 强制设置，避免卡死
                    return;
                }
                prev = currentContext.get();
            } while (!currentContext.compareAndSet(prev, newContext));
            log.debug("Context updated via contextModifier (CAS attempts: {})", attempt);
        }

        /**
         * 获取当前会话级上下文（供 QueryEngine 在工具全部完成后获取最新 context）。
         */
        public ToolUseContext getCurrentContext() {
            return currentContext.get();
        }
    }
}
