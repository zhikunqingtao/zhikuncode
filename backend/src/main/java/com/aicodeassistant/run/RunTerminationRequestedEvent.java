package com.aicodeassistant.run;

/** Requests post-transaction termination of a Run after an authoritative interaction transition. */
public record RunTerminationRequestedEvent(String runId, RunEnvelope.RunExitReason reason, String detail) { }
