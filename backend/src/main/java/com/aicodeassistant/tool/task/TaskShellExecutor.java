package com.aicodeassistant.tool.task;

import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.engine.AbortReason;
import com.aicodeassistant.run.RunControlService;
import com.aicodeassistant.run.RunEnvelope;
import com.aicodeassistant.run.RunExecutionRegistry;
import com.aicodeassistant.run.RunTerminationCoordinator;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.bash.ShellStateManager;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/** One Shell Task owns one child Run; the creating conversation turn may finish independently. */
@Service
public class TaskShellExecutor {
    private static final Logger log = LoggerFactory.getLogger(TaskShellExecutor.class);
    private final TaskCoordinator tasks;
    private final StreamingToolExecutor tools;
    private final RunControlService runs;
    private final RunExecutionRegistry executions;
    private final RunTerminationCoordinator termination;
    private final ManagedProcessRunner processes;
    private final SessionManager sessions;
    private final ShellStateManager shells;

    public TaskShellExecutor(TaskCoordinator tasks, @Lazy StreamingToolExecutor tools,
                             RunControlService runs, RunExecutionRegistry executions,
                             @Lazy RunTerminationCoordinator termination, ManagedProcessRunner processes,
                             SessionManager sessions, ShellStateManager shells) {
        this.tasks = tasks;
        this.tools = tools;
        this.runs = runs;
        this.executions = executions;
        this.termination = termination;
        this.processes = processes;
        this.sessions = sessions;
        this.shells = shells;
    }

    public ToolExecutionResult execute(Tool tool, ToolInput input, String taskId, ToolUseContext parent) {
        if (parent.currentRunId() == null || parent.currentRunId().isBlank()) {
            return ToolExecutionResult.of(ToolResult.internalError("TASK_EXECUTION_OWNERSHIP_MISSING",
                    "Shell tasks require a persisted parent Run", ToolResult.EffectState.NOT_STARTED));
        }
        Scope scope = new Scope(taskId);
        tasks.registerCancellationCleanup(taskId, scope::cancelAndAwait);
        tasks.registerCancellationRequest(taskId, timedOut -> scope.requestCancellation(
                timedOut ? AbortReason.TIMEOUT : AbortReason.USER_INTERRUPT));
        try (var parentLease = sessions.acquireBackgroundLease(parent.sessionId())) {
            try {
                if (!scope.open(parent)) {
                    return ToolExecutionResult.of(ToolResult.cancelled("TOOL_NOT_STARTED",
                            "Task cancelled before shell execution", ToolResult.EffectState.NOT_STARTED));
                }
                ToolExecutionResult execution = tools.executeTaskDetached(tool, input,
                        scope.context.toolUseId(), scope.context, scope::requestCancellation);
                if (Thread.currentThread().isInterrupted() || scope.cancelled
                        || (execution != null && execution.result() != null
                        && execution.result().executionStatus() == ToolResult.ExecutionStatus.CANCELLED)) {
                    scope.cancelAndAwait();
                } else {
                    scope.awaitExit(false);
                    ToolResult result = execution == null ? null : execution.result();
                    if (scope.cancelled || Thread.currentThread().isInterrupted()) {
                        scope.cancelAndAwait();
                    } else if (result != null && result.executionStatus() == ToolResult.ExecutionStatus.SUCCEEDED) {
                        runs.complete(scope.runId, 0, 0, 0);
                    } else {
                        runs.fail(scope.runId, result != null && result.executionStatus() == ToolResult.ExecutionStatus.TIMED_OUT
                                        ? RunEnvelope.RunExitReason.DEADLINE_EXCEEDED : RunEnvelope.RunExitReason.INTERNAL_ERROR,
                                result == null ? "Shell task returned no result" : result.failureCode());
                    }
                }
                return execution;
            } finally {
                // Exceptions and partial startup must not close a session while its worker is live.
                try {
                    if (scope.registered && (!scope.exited || scope.cancelled
                            || Thread.currentThread().isInterrupted())) scope.cancelAndAwait();
                } finally {
                    if (!scope.registered || scope.exited) scope.close();
                }
            }
        }
    }

    private final class Scope {
        private final String childSession;
        private final AbortContext cancellation = new AbortContext();
        private volatile boolean cancelled;
        private volatile boolean cancellationPersisted;
        private AbortReason cancellationReason;
        private String runId;
        private ToolUseContext context;
        private boolean sessionCreated;
        private boolean registered;
        private boolean exited;

        private Scope(String taskId) {
            childSession = "task-shell-" + taskId + "-" + UUID.randomUUID();
        }

        private synchronized boolean open(ToolUseContext parent) {
            if (cancelled || Thread.currentThread().isInterrupted()) return false;
            String cwd = shells.resolveWorkingDirectory(parent.sessionId(), parent.workingDirectory());
            sessions.registerSubAgentSession(childSession, cwd, parent.sessionId());
            sessionCreated = true;
            runId = runs.start(childSession, parent.currentRunId(), "task-shell",
                    parent.parentModel() == null ? "shell" : parent.parentModel()).id();
            context = new ToolUseContext(cwd, childSession, "shell-" + UUID.randomUUID(), parent.onProgress(),
                    parent.additionalDirs(), parent.userModified(), parent.nestingDepth() + 1, null,
                    parent.sessionId(), parent.agentHierarchy(), parent.permissionWaitMs(),
                    parent.permissionNotifier(), parent.parentModel(), runId);
            executions.register(runId, childSession, cancellation);
            registered = true;
            return true;
        }

        private void requestCancellation() {
            requestCancellation(AbortReason.USER_INTERRUPT);
        }

        private void requestCancellation(AbortReason reason) {
            String ownedRun;
            AbortReason acceptedReason;
            synchronized (this) {
                cancelled = true;
                if (cancellationReason == null) cancellationReason = reason;
                acceptedReason = cancellationReason;
                ownedRun = registered ? runId : null;
                if (ownedRun != null) executions.beginCompletion(ownedRun);
                cancellation.abort(acceptedReason);
            }
            if (ownedRun != null && !cancellationPersisted) {
                // This Run belongs only to the Task. Never terminate the creating parent Run.
                RunTerminationCoordinator.Result result = termination.terminate(ownedRun,
                        acceptedReason == AbortReason.TIMEOUT ? RunEnvelope.RunExitReason.DEADLINE_EXCEEDED
                                : RunEnvelope.RunExitReason.USER_CANCELLED, "Shell task cancellation requested");
                if (result.transition() == RunControlService.TransitionResult.APPLIED
                        || result.transition() == RunControlService.TransitionResult.ALREADY_TERMINAL
                        // This scope creates RUNNING Runs, never QUEUED. A rejected active-state
                        // transition on retry means its earlier cancellation already committed.
                        || result.transition() == RunControlService.TransitionResult.INVALID_TRANSITION) {
                    cancellationPersisted = true;
                }
            }
        }

        private void cancelAndAwait() {
            boolean interrupted = Thread.interrupted();
            int failures = 0;
            try {
                for (;;) {
                    try {
                        requestCancellation();
                        if (!registered) return;
                        if (cancellationPersisted) break;
                    } catch (RuntimeException unavailable) {
                        if (failures++ % 100 == 0) {
                            log.warn("Shell task cancellation remains pending: run={}", runId, unavailable);
                        }
                    }
                    try { Thread.sleep(100); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
                awaitExit(true);
                // A previous coordinator call may have failed after persisting CANCELLING.
                // Only actual exit permits retrying the final transition here.
                if (cancellationReason == AbortReason.TIMEOUT) {
                    runs.fail(runId, RunEnvelope.RunExitReason.DEADLINE_EXCEEDED, "Shell task timed out");
                } else {
                    runs.cancel(runId);
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private void awaitExit(boolean cancel) {
            boolean interrupted = Thread.interrupted();
            int failures = 0;
            try {
                for (;;) {
                    try {
                        cancel |= interrupted || cancelled;
                        executions.beginCompletion(runId);
                        if (cancel) {
                            requestCancellation();
                            tools.finishTaskCancellation(context);
                            processes.cancelSessionBackground(childSession);
                        }
                        if (executions.awaitQuiescence(runId, Duration.ZERO)
                                && processes.currentRunTermination(runId).allTerminated()
                                && (!cancel || processes.currentSessionBackground(childSession).allTerminated())) {
                            exited = true;
                            return;
                        }
                    } catch (RuntimeException unavailable) {
                        if (failures++ % 100 == 0) {
                            log.warn("Shell task exit remains unconfirmed: run={}", runId, unavailable);
                        }
                    }
                    try { Thread.sleep(100); }
                    catch (InterruptedException ignored) {
                        interrupted = true;
                        cancel = true;
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private void close() {
            try {
                if (registered) executions.unregister(runId);
                else if (runId != null) runs.fail(runId, RunEnvelope.RunExitReason.INTERNAL_ERROR,
                        "Shell task Run registration failed before execution");
            } finally {
                if (sessionCreated) sessions.closeSubAgentSession(childSession);
            }
        }
    }
}
