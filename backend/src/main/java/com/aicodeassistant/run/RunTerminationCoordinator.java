package com.aicodeassistant.run;

import com.aicodeassistant.engine.AbortReason;
import com.aicodeassistant.interaction.DurableInteractionService;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import com.aicodeassistant.tool.StreamingToolExecutor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.aicodeassistant.observability.BestEffortObservabilityRecorder;

import java.util.LinkedHashMap;
import java.util.Map;

/** The only coordinator allowed to move an active Run through cancellation to a terminal state. */
@Service
public class RunTerminationCoordinator {
    private final RunControlService runs;
    private final DurableInteractionService interactions;
    private final ManagedProcessRunner processes;
    private final RunExecutionRegistry executions;
    private final StreamingToolExecutor tools;
    private volatile BestEffortObservabilityRecorder observabilityRecorder;

    public RunTerminationCoordinator(RunControlService runs, DurableInteractionService interactions,
                                     ManagedProcessRunner processes, RunExecutionRegistry executions,
                                     StreamingToolExecutor tools) {
        this.runs = runs; this.interactions = interactions; this.processes = processes;
        this.executions = executions; this.tools = tools;
    }

    @Autowired(required = false)
    void setObservabilityRecorder(BestEffortObservabilityRecorder observabilityRecorder) {
        this.observabilityRecorder = observabilityRecorder;
    }

    public Result cancelByUser(String runId, String detail) {
        return terminate(runId, RunEnvelope.RunExitReason.USER_CANCELLED, detail);
    }

    public Result terminate(String runId, RunEnvelope.RunExitReason reason, String detail) {
        DurableInteractionService.CancellationResult requested =
                interactions.beginRunTermination(runId, reason, detail == null ? reason.dbValue() : detail);
        if (requested.runTransition() != RunControlService.TransitionResult.APPLIED) {
            recordSummary(runId, reason, requested.runTransition(), null, null, false);
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
        boolean confirmed = quiescent && stopped.allTerminated() && toolsStopped.allTerminated();
        recordSummary(runId, reason, terminal, stopped, toolsStopped, quiescent);
        return new Result(terminal, stopped, confirmed);
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

    private void recordSummary(String runId, RunEnvelope.RunExitReason reason,
                               RunControlService.TransitionResult transition,
                               ManagedProcessRunner.CancelSummary stopped,
                               StreamingToolExecutor.ToolCancelSummary toolsStopped,
                               boolean quiescent) {
        BestEffortObservabilityRecorder recorder = observabilityRecorder;
        if (recorder == null) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", reason == null ? "unknown" : reason.dbValue());
        data.put("transition", transition == null ? "unknown" : transition.name().toLowerCase());
        data.put("quiescent", quiescent);
        data.put("processCount", stopped == null ? 0 : stopped.activeCount());
        data.put("processUnconfirmed", stopped == null ? 0 : stopped.unconfirmedCount());
        data.put("toolSessionCount", toolsStopped == null ? 0 : toolsStopped.foundSessions());
        data.put("toolSessionUnconfirmed", toolsStopped == null ? 0 : toolsStopped.unconfirmedSessions());
        recorder.record(runId, "run_termination_summary", null, data);
    }

    public record Result(RunControlService.TransitionResult transition,
                         ManagedProcessRunner.CancelSummary processes,
                         boolean terminationConfirmed) { }
}
