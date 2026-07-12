package com.aicodeassistant.run;

import org.springframework.context.ApplicationEvent;

public class RunCompletedEvent extends ApplicationEvent {
    private final String runId;
    private final String sessionId;
    private final int totalTokens;
    private final double totalCost;
    private final int toolCallCount;
    private final int turnCount;

    public RunCompletedEvent(Object source, String runId, String sessionId,
                             int totalTokens, double totalCost, int toolCallCount, int turnCount) {
        super(source);
        this.runId = runId;
        this.sessionId = sessionId;
        this.totalTokens = totalTokens;
        this.totalCost = totalCost;
        this.toolCallCount = toolCallCount;
        this.turnCount = turnCount;
    }

    public String getRunId() { return runId; }
    public String getSessionId() { return sessionId; }
    public int getTotalTokens() { return totalTokens; }
    public double getTotalCost() { return totalCost; }
    public int getToolCallCount() { return toolCallCount; }
    public int getTurnCount() { return turnCount; }
}
