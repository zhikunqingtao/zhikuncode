package com.aicodeassistant.engine;

import com.aicodeassistant.run.RunEnvelope;
import com.aicodeassistant.run.RunTracker;

/** Projects the already committed Run outcome; never performs another transition. */
final class RunResultProjection {
    private RunResultProjection() {}

    static QueryEngine.QueryResult resolve(QueryEngine.QueryResult proposed, RunTracker tracker,
                                           String runId, AbortReason requested) {
        if (tracker == null || runId == null) {
            return requested == null ? proposed : unconfirmed(proposed, requested);
        }
        final RunEnvelope run;
        try { run = tracker.getRun(runId).orElse(null); }
        catch (RuntimeException e) { return unconfirmed(proposed, requested); }
        if (run == null || !run.status().terminal()) return unconfirmed(proposed, requested);
        var reason = run.exitReason();
        if (reason == RunEnvelope.RunExitReason.PROCESS_TERMINATION_UNCONFIRMED
                || reason == RunEnvelope.RunExitReason.TOOL_TERMINATION_UNCONFIRMED) {
            return with(proposed, "error", reason + ": requested=" + run.requestedExitReason()
                    + (run.errorSummary() == null ? "" : "; " + run.errorSummary()));
        }
        if (run.status() == RunEnvelope.RunStatus.COMPLETED) {
            return with(proposed, "max_turns".equals(proposed.stopReason()) ? "max_turns" : "end_turn", null);
        }
        if (reason == RunEnvelope.RunExitReason.USER_CANCELLED
                || run.status() == RunEnvelope.RunStatus.CANCELLED) {
            return with(proposed, "cancelled", "USER_CANCELLED"
                    + (run.abortReason() == null || run.abortReason().isBlank() ? "" : ": " + run.abortReason()));
        }
        if (reason == RunEnvelope.RunExitReason.DEADLINE_EXCEEDED) {
            return with(proposed, "timeout", "DEADLINE_EXCEEDED");
        }
        if (reason == RunEnvelope.RunExitReason.INCOMPLETE
                && "max_turns".equals(proposed.stopReason())) {
            String error = run.errorSummary() != null
                    ? run.errorSummary() : "MAX_TURNS: maximum turn count reached";
            return with(proposed, "max_turns", error);
        }
        String error = run.errorSummary() != null ? run.errorSummary()
                : proposed.error() != null ? proposed.error() : String.valueOf(reason);
        return with(proposed, "error", error);
    }

    private static QueryEngine.QueryResult unconfirmed(QueryEngine.QueryResult result, AbortReason requested) {
        return with(result, "error", "RUN_TERMINATION_UNCONFIRMED: requested=" + requested);
    }

    private static QueryEngine.QueryResult with(QueryEngine.QueryResult result, String stop, String error) {
        return new QueryEngine.QueryResult(result.messages(), result.totalUsage(), stop, error, result.turnCount());
    }
}
