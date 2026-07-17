package com.aicodeassistant.run;

import com.aicodeassistant.engine.AbortReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.time.Duration;

/**
 * Compatibility adapter for existing orchestration code. RunControlService is the only write authority.
 */
@Service
public class RunTracker {
    private static final Logger log = LoggerFactory.getLogger(RunTracker.class);
    private final RunControlService control;
    private final RunEnvelopeRepository envelopes;
    private final ApplicationEventPublisher publisher;
    private final RunTerminationCoordinator termination;
    private final RunExecutionRegistry executions;

    public RunTracker(RunControlService control, RunEnvelopeRepository envelopes,
                      ApplicationEventPublisher publisher,
                      RunTerminationCoordinator termination,
                      RunExecutionRegistry executions) {
        this.control = control;
        this.envelopes = envelopes;
        this.publisher = publisher;
        this.termination = termination;
        this.executions = executions;
    }

    public RunEnvelope startRun(String sessionId, String parentRunId, String agentType, String model) {
        return control.start(sessionId, parentRunId, agentType, model);
    }

    public RunEvent recordEvent(String runId, String eventType, Object data) {
        return control.appendEvent(runId, canonicalEventType(eventType), null, data);
    }

    public void completeRun(String runId, int totalTokens, double totalCost,
                            int ignoredToolCallCount, int turnCount) {
        executions.beginCompletion(runId);
        if (!executions.awaitQuiescence(runId, Duration.ofSeconds(2))) {
            termination.terminate(runId, RunEnvelope.RunExitReason.TOOL_TERMINATION_UNCONFIRMED,
                    "Run completed while supervised work was still active");
            return;
        }
        RunControlService.TransitionResult result = control.complete(runId, totalTokens, totalCost, turnCount);
        if (result == RunControlService.TransitionResult.APPLIED) {
            envelopes.findById(runId).ifPresent(run -> publisher.publishEvent(new RunCompletedEvent(
                    this, runId, run.sessionId(), totalTokens, totalCost,
                    run.toolCallCount(), turnCount)));
        } else {
            log.debug("Run completion ignored: run={}, result={}", runId, result);
        }
    }

    public void failRun(String runId, String errorSummary) {
        termination.terminate(runId, RunEnvelope.RunExitReason.INTERNAL_ERROR, errorSummary);
    }

    public void abortRun(String runId, AbortReason reason, String detail) {
        AbortReason effective = reason == null ? AbortReason.USER_INTERRUPT : reason;
        switch (effective) {
            case USER_INTERRUPT, SUBMIT_INTERRUPT -> termination.cancelByUser(runId, detail);
            case TIMEOUT -> termination.terminate(
                    runId, RunEnvelope.RunExitReason.DEADLINE_EXCEEDED, detail);
            case SYSTEM_SHUTDOWN -> termination.terminate(
                    runId, RunEnvelope.RunExitReason.SERVICE_RESTART, detail);
            case ERROR -> termination.terminate(
                    runId, RunEnvelope.RunExitReason.INTERNAL_ERROR, detail);
        }
    }

    public Optional<RunEnvelope> getRun(String runId) { return envelopes.findById(runId); }

    private static String canonicalEventType(String type) {
        return switch (type) {
            case "tool_call", "tool_use" -> "tool_started";
            case "tool_result" -> "tool_finished";
            default -> type;
        };
    }
}
