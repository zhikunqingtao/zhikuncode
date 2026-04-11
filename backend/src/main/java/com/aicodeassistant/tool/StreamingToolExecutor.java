package com.aicodeassistant.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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

    public StreamingToolExecutor(ToolExecutionPipeline pipeline) {
        this.pipeline = pipeline;
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
     */
    public ExecutionSession newSession() {
        return new ExecutionSession();
    }

    /**
     * 执行会话 — 非单例，每次 runTools 调用创建一个。
     */
    public class ExecutionSession {

        private final Queue<TrackedTool> queue = new ConcurrentLinkedQueue<>();
        private final List<TrackedTool> tracked = new CopyOnWriteArrayList<>();
        private final AtomicInteger active = new AtomicInteger(0);
        private volatile boolean sessionDiscarded = false;

        /** 添加工具到执行队列 */
        public void addTool(Tool tool, ToolInput input, String toolUseId, ToolUseContext context) {
            TrackedTool tt = new TrackedTool(toolUseId, tool, input, context);
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

                Thread.startVirtualThread(() -> {
                    try {
                        if (sessionDiscarded) {
                            next.result = ToolResult.error(
                                    "<tool_use_error>Tool execution discarded</tool_use_error>");
                        } else {
                            ToolExecutionResult execResult = pipeline.execute(next.tool, next.input,
                                    next.context.withToolUseId(next.toolUseId));
                            next.result = execResult.result();
                            next.updatedContext = execResult.updatedContext();
                        }
                        next.state = ToolState.COMPLETED;
                    } catch (Exception e) {
                        next.result = ToolResult.error(
                                "<tool_use_error>Execution error: " + e.getMessage() + "</tool_use_error>");
                        next.state = ToolState.COMPLETED;
                    } finally {
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
    }
}
