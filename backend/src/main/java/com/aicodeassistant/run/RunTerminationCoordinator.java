package com.aicodeassistant.run;

import com.aicodeassistant.engine.AbortReason;
import com.aicodeassistant.interaction.DurableInteractionService;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import com.aicodeassistant.tool.StreamingToolExecutor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** The only coordinator allowed to move an active Run through cancellation to a terminal state. */
@Service
public class RunTerminationCoordinator {
    private final RunControlService runs;
    private final DurableInteractionService interactions;
    private final ManagedProcessRunner processes;
    private final RunExecutionRegistry executions;
    private final StreamingToolExecutor tools;

    public RunTerminationCoordinator(RunControlService runs, DurableInteractionService interactions,
                                     ManagedProcessRunner processes, RunExecutionRegistry executions,
                                     StreamingToolExecutor tools) {
        this.runs = runs; this.interactions = interactions; this.processes = processes;
        this.executions = executions; this.tools = tools;
    }

    public Result cancelByUser(String runId, String detail) {
        return terminate(runId, RunEnvelope.RunExitReason.USER_CANCELLED, detail);
    }

    public Result terminate(String runId, RunEnvelope.RunExitReason reason, String detail) {
        DurableInteractionService.CancellationResult requested =
                interactions.beginRunTermination(runId, reason, detail == null ? reason.dbValue() : detail);
        if (requested.runTransition() != RunControlService.TransitionResult.APPLIED) {
            return new Result(requested.runTransition(), null, false);
        }

        // Close admission before touching any subsystem. Work acquired immediately
        // before this point receives a cancellation callback; work arriving later is rejected.
        executions.beginTermination(runId);
        executions.abortRun(runId, abortReason(reason));
        StreamingToolExecutor.ToolCancelSummary toolsStopped = tools.cancelRunDetailed(runId);
        ManagedProcessRunner.CancelSummary stopped = processes.cancelRunDetailed(runId);
        RunControlService.TransitionResult terminal;
        boolean quiescent = executions.awaitQuiescence(runId, java.time.Duration.ofSeconds(2));
        if (!quiescent) {
            terminal = runs.fail(runId, RunEnvelope.RunExitReason.TOOL_TERMINATION_UNCONFIRMED,
                    "Termination requested, but Run-owned work did not become quiescent");
        } else if (!stopped.allTerminated()) {
            terminal = runs.fail(runId, RunEnvelope.RunExitReason.PROCESS_TERMINATION_UNCONFIRMED,
                    "Termination requested, but " + stopped.unconfirmedCount()
                            + " supervised process(es) could not be confirmed stopped");
        } else if (!toolsStopped.allTerminated()) {
            terminal = runs.fail(runId, RunEnvelope.RunExitReason.TOOL_TERMINATION_UNCONFIRMED,
                    "Termination requested, but " + toolsStopped.unconfirmedSessions()
                            + " tool execution session(s) could not be confirmed stopped");
        } else if (reason == RunEnvelope.RunExitReason.USER_CANCELLED) {
            terminal = runs.cancel(runId);
        } else {
            terminal = runs.fail(runId, reason, detail);
        }
        return new Result(terminal, stopped,
                quiescent && stopped.allTerminated() && toolsStopped.allTerminated());
    }

    @EventListener
    public void onTerminationRequested(RunTerminationRequestedEvent event) {
        terminate(event.runId(), event.reason(), event.detail());
    }

    private static AbortReason abortReason(RunEnvelope.RunExitReason reason) {
        return switch (reason) {
            case USER_CANCELLED -> AbortReason.USER_INTERRUPT;
            case DEADLINE_EXCEEDED, INTERACTION_EXPIRED -> AbortReason.TIMEOUT;
            case SERVICE_RESTART -> AbortReason.SYSTEM_SHUTDOWN;
            default -> AbortReason.ERROR;
        };
    }

    public record Result(RunControlService.TransitionResult transition,
                         ManagedProcessRunner.CancelSummary processes,
                         boolean terminationConfirmed) { }
}
