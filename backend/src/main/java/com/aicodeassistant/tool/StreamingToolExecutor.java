package com.aicodeassistant.tool;

import com.aicodeassistant.observability.MdcScope;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import com.aicodeassistant.run.RunExecutionRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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
 */
@Service
public class StreamingToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(StreamingToolExecutor.class);

    private final ToolExecutionPipeline pipeline;
    private final MeterRegistry meterRegistry;
    private final AtomicInteger activeVirtualThreads = new AtomicInteger(0);
    private final Counter toolExecutionTotal;
    private final Counter toolExecutionErrors;
    private final Counter cascadeAbortCounter;
    private final Counter watchdogFiredCounter;
    private final Counter syntheticErrorCounter;
    private final ManagedProcessRunner processRunner;
    private final RunExecutionRegistry runExecutions;
    private final ConcurrentHashMap<String, java.util.Set<ExecutionSession>> sessionsByRun = new ConcurrentHashMap<>();

    @Autowired
    public StreamingToolExecutor(ToolExecutionPipeline pipeline, MeterRegistry meterRegistry,
                                 ManagedProcessRunner processRunner,
                                 RunExecutionRegistry runExecutions) {
        this.pipeline = pipeline;
        this.meterRegistry = meterRegistry;
        this.processRunner = processRunner;
        this.runExecutions = runExecutions;

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

        this.cascadeAbortCounter = Counter.builder("zhiku.tool.cascade_aborts")
                .description("Count of selective error cascade triggered by high-risk tools")
                .register(meterRegistry);
        this.watchdogFiredCounter = Counter.builder("zhiku.tool.watchdog_fired")
                .description("Count of watchdog timeout fires (should be zero in normal operation)")
                .register(meterRegistry);
        this.syntheticErrorCounter = Counter.builder("zhiku.tool.synthetic_errors")
                .description("Count of synthetic error tool_results generated for orphaned tool_use blocks")
                .register(meterRegistry);
    }

    public StreamingToolExecutor(ToolExecutionPipeline pipeline, MeterRegistry meterRegistry) {
        this(pipeline, meterRegistry, null, null);
    }

    /** 仅供隔离单元测试使用的构造器。 */
    public StreamingToolExecutor(ToolExecutionPipeline pipeline, MeterRegistry meterRegistry,
                                 ManagedProcessRunner processRunner) {
        this(pipeline, meterRegistry, processRunner, null);
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
        private volatile boolean executionExited;
        private final Map<String, String> diagnosticContext;
        private volatile ToolState state;
        private volatile ToolResult result;
        private volatile ToolUseContext updatedContext;  // contextModifier 产生的更新上下文
        private volatile Thread executionThread;
        private boolean concurrencySafe;
        // Unknown risk fails closed if preparation itself fails before pipeline admission.
        private boolean highRisk = true;
        private boolean cascaded;
        private Throwable preparationFailure;

        public TrackedTool(String toolUseId, Tool tool, ToolInput input, ToolUseContext context) {
            this.toolUseId = toolUseId;
            this.tool = tool;
            this.input = input;
            this.diagnosticContext = MdcScope.capture();
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
        // 仅创建 LLM 回合收集器不算 Run 工作；收到首个真实工具调用时才注册。
        // 否则正常的最终助手回合会保留空租约，导致 Run 无法完成。
        return new ExecutionSession(initialContext);
    }

    /**
     * 让内部调度工具与模型发起的工具共用同一队列、Run 租约和取消权威。
     * 调用方不得直接调用 ToolExecutionPipeline。
     */
    public ToolExecutionResult executeDetached(Tool tool, ToolInput input, String toolUseId,
                                               ToolUseContext context) {
        if (tool == null || context == null || toolUseId == null || toolUseId.isBlank()) {
            throw new IllegalArgumentException("DETACHED_TOOL_EXECUTION_INVALID");
        }
        ExecutionSession session = newSession(context);
        session.addTool(tool, input, toolUseId, context);
        while (!session.isAllCompleted()) {
            session.awaitAnyCompletion(1, TimeUnit.SECONDS);
            if (Thread.currentThread().isInterrupted()) {
                session.discard();
                Thread.currentThread().interrupt();
                return ToolExecutionResult.of(ToolResult.cancelled("TOOL_CANCELLED",
                        "Detached tool execution interrupted", ToolResult.EffectState.UNKNOWN));
            }
        }
        List<TrackedTool> completed = session.yieldCompleted();
        if (completed.size() != 1 || completed.getFirst().getResult() == null) {
            return ToolExecutionResult.of(ToolResult.internalError("DETACHED_TOOL_RESULT_MISSING",
                    "Detached tool did not produce exactly one result", ToolResult.EffectState.UNKNOWN));
        }
        TrackedTool tracked = completed.getFirst();
        return ToolExecutionResult.of(tracked.getResult(), tracked.getUpdatedContext());
    }

    /**
     * Task-owned detached execution. A cancellation interrupts only this tool session and
     * its exact process ownership; the parent Run can contain unrelated work.
     * Return only after the worker and its foreground process resources have exited.
     */
    public ToolExecutionResult executeTaskDetached(Tool tool, ToolInput input, String toolUseId,
                                                   ToolUseContext context) {
        return executeTaskDetached(tool, input, toolUseId, context, () -> { });
    }

    /** The callback belongs to a Task-owned Run and also terminates its pending approvals. */
    public ToolExecutionResult executeTaskDetached(Tool tool, ToolInput input, String toolUseId,
                                                   ToolUseContext context, Runnable requestCancellation) {
        if (tool == null || context == null || toolUseId == null || toolUseId.isBlank()) {
            throw new IllegalArgumentException("DETACHED_TOOL_EXECUTION_INVALID");
        }
        String runId = context.currentRunId();
        if (processRunner == null || runId == null || runId.isBlank()) {
            return ToolExecutionResult.of(ToolResult.internalError("TASK_EXECUTION_OWNERSHIP_MISSING",
                    "Task tool execution requires process ownership support and a Run ID",
                    ToolResult.EffectState.NOT_STARTED));
        }
        if (Thread.currentThread().isInterrupted()) {
            return ToolExecutionResult.of(ToolResult.cancelled("TOOL_NOT_STARTED",
                    "Task cancelled before tool execution", ToolResult.EffectState.NOT_STARTED)
                    .withMetadata("terminationConfirmed", true));
        }
        ExecutionSession session = newSession(context);
        boolean interrupted = false;
        boolean cancellationRequested = false;
        boolean inspectionFailureLogged = false;
        try {
            session.addTool(tool, input, toolUseId, context);
            for (;;) {
                if (Thread.interrupted()) {
                    interrupted = true;
                    cancellationRequested = true;
                    session.discard(false);
                }
                try {
                    if (cancellationRequested) {
                        requestCancellation.run();
                        // Repeat after worker exit as well: it may have registered a process
                        // after the first cancellation scan. Never cancel the entire parent Run.
                        processRunner.cancel(runId, toolUseId);
                    }
                    if (session.active.get() == 0 && !session.hasUnfinishedTools()
                            && session.tracked.stream().allMatch(t -> t.executionExited)
                            && processRunner.currentTermination(runId, toolUseId).allTerminated()) {
                        break;
                    }
                } catch (RuntimeException unknownTermination) {
                    if (!inspectionFailureLogged) {
                        log.warn("Task tool termination remains unconfirmed: toolUseId={}",
                                toolUseId, unknownTermination);
                        inspectionFailureLogged = true;
                    }
                    // Failure to inspect/clean ownership must not release the Task's capacity.
                }
                session.awaitAnyCompletion(100, TimeUnit.MILLISECONDS);
            }
            List<TrackedTool> completed = session.yieldCompleted();
            if (completed.size() != 1 || completed.getFirst().getResult() == null) {
                return ToolExecutionResult.of(ToolResult.internalError("DETACHED_TOOL_RESULT_MISSING",
                        "Detached tool did not produce exactly one result", ToolResult.EffectState.UNKNOWN));
            }
            TrackedTool tracked = completed.getFirst();
            if (cancellationRequested) {
                ToolResult result = tracked.getResult();
                return ToolExecutionResult.of(ToolResult.cancelled("TOOL_CANCELLED",
                        result.content() == null ? "Task tool execution cancelled" : result.content(),
                        result.effectState()).withMetadata("terminationConfirmed", true),
                        tracked.getUpdatedContext());
            }
            return ToolExecutionResult.of(tracked.getResult(), tracked.getUpdatedContext());
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Seal cancellation accepted after executeTaskDetached returned but before Task settlement. */
    public void finishTaskCancellation(ToolUseContext context) {
        // The managed entry rejects missing ownership before starting any work.
        if (processRunner == null || context == null || context.currentRunId() == null
                || context.currentRunId().isBlank() || context.toolUseId() == null
                || context.toolUseId().isBlank()) return;
        boolean interrupted = Thread.interrupted();
        try {
            for (;;) {
                processRunner.cancel(context.currentRunId(), context.toolUseId());
                if (processRunner.currentTermination(context.currentRunId(), context.toolUseId()).allTerminated()) {
                    return;
                }
                try { TimeUnit.MILLISECONDS.sleep(100); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** 取消指定 Run 的排队任务；正在运行的进程型工具由 ManagedProcessRunner 负责终止。 */
    public int cancelRun(String runId) {
        return cancelRunDetailed(runId).confirmedSessions();
    }

    public ToolCancelSummary cancelRunDetailed(String runId) {
        java.util.Set<ExecutionSession> registered = sessionsByRun.get(runId);
        if (registered == null) return new ToolCancelSummary(0, 0, 0);
        List<ExecutionSession> sessions = List.copyOf(registered);
        sessions.forEach(session -> session.discard(false));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        int confirmed = 0;
        for (ExecutionSession session : sessions) {
            if (session.awaitStopped(deadline)) confirmed++;
        }
        return new ToolCancelSummary(sessions.size(), confirmed, sessions.size() - confirmed);
    }

    public record ToolCancelSummary(int foundSessions, int confirmedSessions, int unconfirmedSessions) {
        public boolean allTerminated() { return unconfirmedSessions == 0; }
    }

    /**
     * 执行会话 — 非单例，每次 runTools 调用创建一个。
     */
    public class ExecutionSession {

        private final Queue<TrackedTool> queue = new ConcurrentLinkedQueue<>();
        private final List<TrackedTool> tracked = new CopyOnWriteArrayList<>();
        private final AtomicInteger active = new AtomicInteger(0);
        private volatile boolean sessionDiscarded = false;
        // Queue ownership and result transitions share a lock; never wait or run tools under it.
        private final Object stateLock = new Object();
        // Context modifiers are committed under stateLock before result publication.
        private final AtomicReference<ToolUseContext> currentContext;
        private final String ownerRunId;
        // Lock order: registrationLock -> stateLock. Registry calls never hold stateLock.
        private final Object registrationLock = new Object();
        private RunExecutionRegistry.WorkLease workLease;
        private boolean sessionRegistered;
        private volatile int pendingSubmissions;

        // ★ 条件变量，替代固定 50ms 轮询
        private final Object completionLock = new Object();

        // ★ 各工具声明的最大执行时间（毫秒），默认 10 分钟
        private final AtomicLong maxExpectedDurationMs = new AtomicLong(600_000L);

        // ★ 有参构造器，接收初始 context
        public ExecutionSession(ToolUseContext initialContext) {
            this.currentContext = new AtomicReference<>(initialContext);
            this.ownerRunId = initialContext == null ? null : initialContext.currentRunId();
        }

        /** 添加工具到执行队列 */
        public void addTool(Tool tool, ToolInput input, String toolUseId, ToolUseContext context) {
            TrackedTool tt = new TrackedTool(toolUseId, tool, input, currentContext.get());
            synchronized (registrationLock) {
                pendingSubmissions++;
                try {
                    if (!sessionDiscarded) ensureRegistered();
                } catch (RuntimeException | Error registrationFailure) {
                    pendingSubmissions--;
                    deregister();
                    throw registrationFailure;
                }
            }
            try {
                if (!sessionDiscarded) prepareTool(tt);
                synchronized (stateLock) {
                    tracked.add(tt);
                    if (sessionDiscarded) {
                        completeOnce(tt, ToolResult.cancelled("TOOL_DISCARDED",
                                "Tool execution discarded", ToolResult.EffectState.NOT_STARTED), null);
                        tt.executionExited = true;
                    } else {
                        queue.add(tt);
                    }
                }
            } finally {
                synchronized (registrationLock) {
                    pendingSubmissions--;
                    deregister();
                }
            }
            notifyCompletion();
            processQueue();
        }

        private void prepareTool(TrackedTool tt) {
            // Classification is covered by the submission lease but holds no session lock.
            try {
                try {
                    tt.concurrencySafe = tt.tool.isConcurrencySafe(tt.input);
                } catch (RuntimeException classificationFailure) {
                    // Invalid input still reaches pipeline validation, but never runs concurrently.
                    tt.concurrencySafe = false;
                }
                try {
                    tt.highRisk = tt.tool.isHighRisk();
                } catch (RuntimeException riskFailure) {
                    tt.preparationFailure = riskFailure;
                }
                observeSafely(() -> registerExpectedDuration(tt.tool.getMaxExecutionTimeMs()));
            } catch (Error preparationError) {
                // Settle the admitted call in the worker's finally, then propagate the Error there.
                tt.preparationFailure = preparationError;
            }
        }

        /** 仅为确实包含可执行工具任务的会话注册 Run 工作租约。 */
        private void ensureRegistered() {
            if (ownerRunId == null || sessionRegistered) return;
            RunExecutionRegistry.WorkLease acquired = runExecutions == null ? null
                    : runExecutions.acquireWork(ownerRunId, "tool-session",
                            java.util.UUID.randomUUID().toString());
            workLease = acquired;
            sessionsByRun.compute(ownerRunId, (ignored, sessions) -> {
                if (sessions == null) sessions = ConcurrentHashMap.newKeySet();
                sessions.add(this);
                return sessions;
            });
            sessionRegistered = true;
            if (acquired != null) acquired.onCancel(() -> discard(false));
        }

        private boolean canExecute(TrackedTool tt) {
            if (active.get() == 0) return true;
            if (!tt.concurrencySafe) return false;
            return tracked.stream()
                    .filter(t -> !t.executionExited && t.state != ToolState.QUEUED)
                    .allMatch(t -> t.concurrencySafe);
        }

        private void processQueue() {
            for (;;) {
                final TrackedTool next;
                synchronized (stateLock) {
                    TrackedTool head = queue.peek();
                    if (head == null) return;
                    if (head.state != ToolState.QUEUED) {
                        queue.poll();
                        continue;
                    }
                    if (!canExecute(head)) return;
                    next = queue.poll();
                    next.state = ToolState.EXECUTING;
                    active.incrementAndGet();
                }
                try {
                    observeSafely(() -> log.debug(
                            "processQueue: launching virtual thread for tool={}, toolUseId={}",
                            next.tool.getName(), next.toolUseId));
                    Thread.ofVirtual().name("zhiku-tool-" + next.tool.getName()).start(() -> runTool(next));
                } catch (RuntimeException | Error startFailure) {
                    if (startFailure instanceof Error) {
                        synchronized (stateLock) {
                            // Thread admission itself failed: do not attempt more worker starts.
                            discardQueuedTools();
                        }
                    }
                    try {
                        publishResult(next, ToolResult.internalError("TOOL_EXECUTION_START_FAILED",
                                "Tool execution could not start: " + startFailure.getMessage(),
                                ToolResult.EffectState.NOT_STARTED), null, true);
                    } finally {
                        synchronized (stateLock) {
                            active.decrementAndGet();
                            next.executionExited = true;
                        }
                        notifyCompletion();
                        deregister();
                    }
                    if (startFailure instanceof Error error) throw error;
                    observeSafely(() -> log.warn("Tool worker could not start: toolUseId={}",
                            next.toolUseId, startFailure));
                }
            }
        }

        private void runTool(TrackedTool next) {
            activeVirtualThreads.incrementAndGet();
            Timer.Sample sample = null;
            boolean pipelineStarted = false;
            try (MdcScope ignoredMdc = MdcScope.open(next.diagnosticContext);
                 MdcScope ignoredTool = MdcScope.open(
                         java.util.Collections.singletonMap("toolUseId", next.toolUseId))) {
                synchronized (stateLock) {
                    next.executionThread = Thread.currentThread();
                }
                observeSafely(() -> log.debug("virtual thread started: tool={}, threadName={}",
                        next.tool.getName(), Thread.currentThread().getName()));
                try {
                    sample = Timer.start(meterRegistry);
                } catch (RuntimeException ignoredMetric) {
                    // Observability must not decide whether the tool can execute.
                }
                if (sessionDiscarded || Thread.currentThread().isInterrupted()) {
                    publishDiscarded(next);
                    return;
                }
                // Unknown risk is rejected before execution, without making a failed classifier safe.
                if (next.preparationFailure instanceof Error error) throw error;
                if (next.preparationFailure instanceof RuntimeException failure) throw failure;
                ToolUseContext executionContext = currentContext.get().withToolUseId(next.toolUseId);
                // Preparation and logging may block: never reuse an earlier cancellation snapshot.
                if (sessionDiscarded || Thread.currentThread().isInterrupted()) {
                    publishDiscarded(next);
                    return;
                }
                pipelineStarted = true;
                ToolExecutionResult execution = pipeline.execute(next.tool, next.input,
                        executionContext, executionContext.permissionNotifier());
                publishResult(next, java.util.Objects.requireNonNull(execution.result()),
                        execution.updatedContext(), false);
            } catch (Exception failure) {
                publishResult(next, ToolResult.internalError("TOOL_EXECUTION_EXCEPTION",
                        "<tool_use_error>Execution error: " + failure.getMessage() + "</tool_use_error>",
                        pipelineStarted ? ToolResult.EffectState.UNKNOWN : ToolResult.EffectState.NOT_STARTED),
                        null, true);
            } finally {
                try {
                    // Error still propagates, but no exited worker may leave an EXECUTING record.
                    if (next.state == ToolState.EXECUTING) {
                        publishResult(next, ToolResult.internalError("TOOL_EXECUTION_TERMINATED_WITHOUT_RESULT",
                                "Tool execution terminated without a result; execution outcome unknown. "
                                        + "Side effects may have occurred; verify before retrying.",
                                pipelineStarted ? ToolResult.EffectState.UNKNOWN : ToolResult.EffectState.NOT_STARTED),
                                null, true);
                    }
                } finally {
                    try {
                        if (sample != null) {
                            sample.stop(Timer.builder("zhiku.tool.execution_time")
                                    .tag("tool", next.tool.getName())
                                    .description("Tool execution time").register(meterRegistry));
                        }
                    } catch (RuntimeException ignoredMetric) {
                        // A timing failure cannot prevent worker/lease cleanup.
                    } finally {
                        activeVirtualThreads.decrementAndGet();
                        synchronized (stateLock) {
                            active.decrementAndGet();
                            next.executionThread = null;
                            next.executionExited = true;
                        }
                        notifyCompletion();
                        try {
                            processQueue();
                        } finally {
                            deregister();
                        }
                    }
                }
            }
        }

        private void publishDiscarded(TrackedTool next) {
            publishResult(next, ToolResult.cancelled("TOOL_DISCARDED", "Tool execution discarded",
                    ToolResult.EffectState.NOT_STARTED), null, false);
        }

        private void publishResult(TrackedTool tool, ToolResult result, ToolUseContext updatedContext,
                                   boolean executionError) {
            if (!completeOnce(tool, result, updatedContext)) return;
            notifyCompletion();
            observeSafely(toolExecutionTotal::increment);
            if (executionError) observeSafely(toolExecutionErrors::increment);
            if (tool.cascaded) {
                observeSafely(cascadeAbortCounter::increment);
                observeSafely(() -> log.warn(
                        "[TOOL-CASCADE] High-risk tool failed; queued siblings will be discarded. "
                                + "toolUseId={}, failureType={}, failureCode={}",
                        tool.toolUseId, result.failureType(), result.failureCode()));
            }
            if (updatedContext != null && tool.concurrencySafe) {
                observeSafely(() -> log.warn(
                        "Concurrency-safe tool returned an ignored contextModifier: toolUseId={}", tool.toolUseId));
            }
        }

        /** Context and cancellation become visible before the immutable result is published. */
        private boolean completeOnce(TrackedTool tool, ToolResult result, ToolUseContext updatedContext) {
            synchronized (stateLock) {
                if (tool.state == ToolState.COMPLETED || tool.state == ToolState.YIELDED) return false;
                if (updatedContext != null && !tool.concurrencySafe) currentContext.set(updatedContext);
                if (tool.tool != null && tool.highRisk && result.isError()
                        && result.failureType() != ToolResult.ToolFailureType.PERMISSION
                        && result.failureType() != ToolResult.ToolFailureType.VALIDATION
                        && !sessionDiscarded) {
                    sessionDiscarded = true;
                    tool.cascaded = true;
                }
                tool.result = result;
                tool.updatedContext = updatedContext;
                tool.state = ToolState.COMPLETED;
                return true;
            }
        }

        private void observeSafely(Runnable observation) {
            try {
                observation.run();
            } catch (RuntimeException ignoredObservation) {
                // Logging and metrics are optional; do not retry or republish a business result.
            }
        }

        /** 按原始顺序 yield 已完成的结果 */
        public List<TrackedTool> yieldCompleted() {
            List<TrackedTool> yielded = new ArrayList<>();
            synchronized (stateLock) {
                for (TrackedTool t : tracked) {
                    if (t.state == ToolState.YIELDED) continue;
                    if (t.state != ToolState.COMPLETED) break;
                    t.state = ToolState.YIELDED;
                    yielded.add(t);
                }
            }
            return yielded;
        }

        /** Read completed results without ordered yielding hiding later completions. */
        public Map<String, ToolResult> completedResultsSnapshot() {
            Map<String, ToolResult> results = new java.util.LinkedHashMap<>();
            for (TrackedTool t : tracked) {
                ToolState state = t.state;
                if ((state == ToolState.COMPLETED || state == ToolState.YIELDED) && t.result != null) {
                    results.put(t.toolUseId, t.result);
                }
            }
            return Map.copyOf(results);
        }

        /** 是否所有工具都已完成 */
        public boolean isAllCompleted() {
            boolean completed = tracked.stream().allMatch(t ->
                    t.state == ToolState.COMPLETED || t.state == ToolState.YIELDED);
            if (completed) deregister();
            return completed;
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
            discard(true);
        }

        private void discard(boolean cancelOwnedProcesses) {
            final List<Thread> threads;
            synchronized (stateLock) {
                discardQueuedTools();
                threads = tracked.stream().filter(t -> t.state == ToolState.EXECUTING)
                        .map(t -> t.executionThread).filter(java.util.Objects::nonNull).toList();
            }
            ToolUseContext context = currentContext.get();
            try {
                if (cancelOwnedProcesses && processRunner != null && context != null && context.currentRunId() != null) {
                    processRunner.cancelRun(context.currentRunId());
                }
            } finally {
                threads.forEach(Thread::interrupt);
                notifyCompletion();
                deregister();
            }
        }

        /** Caller holds stateLock; running tools retain their ownership until they exit. */
        private void discardQueuedTools() {
            sessionDiscarded = true;
            queue.clear();
            for (TrackedTool tool : tracked) {
                if (tool.state == ToolState.QUEUED) {
                    completeOnce(tool, ToolResult.cancelled("TOOL_NOT_STARTED", "Cancelled before execution",
                            ToolResult.EffectState.NOT_STARTED), null);
                    tool.executionExited = true;
                }
            }
        }

        private boolean awaitStopped(long deadlineNanos) {
            synchronized (completionLock) {
                while (active.get() > 0 || pendingSubmissions != 0) {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0) return false;
                    try {
                        TimeUnit.NANOSECONDS.timedWait(completionLock, remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return active.get() == 0 && pendingSubmissions == 0;
                    }
                }
                return true;
            }
        }

        /** 是否已被丢弃 */
        public boolean isDiscarded() {
            return sessionDiscarded;
        }

        private void deregister() {
            synchronized (registrationLock) {
                if (!sessionRegistered || pendingSubmissions != 0) return;
                synchronized (stateLock) {
                    if (active.get() != 0 || !queue.isEmpty()) return;
                }
                // addTool reserves admission under registrationLock, so this generation is idle.
                sessionsByRun.computeIfPresent(ownerRunId, (ignored, sessions) -> {
                    sessions.remove(this);
                    return sessions.isEmpty() ? null : sessions;
                });
                RunExecutionRegistry.WorkLease retired = workLease;
                workLease = null;
                sessionRegistered = false;
                // Registry calls must remain outside stateLock, including synchronous cancellation.
                if (retired != null) retired.close();
            }
        }

        /**
         * 直接添加一个已完成的 error result（工具未找到等场景）。
                 */
        public void addErrorResult(String toolUseId, String errorContent) {
            TrackedTool tt = new TrackedTool(toolUseId, null, null, null);
            synchronized (stateLock) {
                completeOnce(tt, ToolResult.internalError("TOOL_SIBLING_ABORTED", errorContent,
                        ToolResult.EffectState.NOT_STARTED), null);
                tt.executionExited = true;
                tracked.add(tt);
            }
            notifyCompletion();
        }

        /** 是否有未完成的工具 */
        public boolean hasUnfinishedTools() {
            return tracked.stream().anyMatch(t ->
                    t.state == ToolState.QUEUED || t.state == ToolState.EXECUTING);
        }

        /**
         * 获取当前会话级上下文（供 QueryEngine 在工具全部完成后获取最新 context）。
         */
        public ToolUseContext getCurrentContext() {
            return currentContext.get();
        }

        /** 等待任意工具完成（条件等待，替代 Thread.sleep） */
        public void awaitAnyCompletion(long timeout, TimeUnit unit) {
            synchronized (completionLock) {
                try {
                    completionLock.wait(unit.toMillis(timeout));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /** 工具完成时通知等待线程 */
        private void notifyCompletion() {
            synchronized (completionLock) {
                completionLock.notifyAll();
            }
        }

        /** 注册工具预期执行时间 */
        public void registerExpectedDuration(long durationMs) {
            maxExpectedDurationMs.updateAndGet(current -> Math.max(current, durationMs));
        }

        /** 获取 session 中最长工具的预期执行时间 */
        public long getMaxExpectedDurationMs() {
            return maxExpectedDurationMs.get();
        }

        /** 获取所有未完成工具的 ID 列表（诊断用） */
        public List<String> getPendingToolIds() {
            return tracked.stream()
                    .filter(t -> t.state != ToolState.COMPLETED && t.state != ToolState.YIELDED)
                    .map(TrackedTool::getToolUseId)
                    .collect(Collectors.toList());
        }

        /** 通知 Watchdog 触发（度量统计） */
        public void notifyWatchdogFired() {
            watchdogFiredCounter.increment();
        }

        /** 通知生成了 synthetic error（度量统计） */
        public void notifySyntheticError() {
            syntheticErrorCounter.increment();
        }
    }
}
