package com.aicodeassistant.tool.task;

import com.aicodeassistant.model.TaskStatus;

/** Execution outcome; callers must finish owned child execution before returning it. */
public record TaskExecutionResult(TaskStatus status, String output, String error) {
    public TaskExecutionResult {
        if (status != TaskStatus.COMPLETED && status != TaskStatus.FAILED
                && status != TaskStatus.CANCELLED) {
            throw new IllegalArgumentException("Task execution must return a terminal outcome");
        }
    }

    public static TaskExecutionResult completed(String output) {
        return new TaskExecutionResult(TaskStatus.COMPLETED, output, null);
    }

    public static TaskExecutionResult failed(String output, String error) {
        return new TaskExecutionResult(TaskStatus.FAILED, output, error);
    }

    public static TaskExecutionResult cancelled(String output, String error) {
        return new TaskExecutionResult(TaskStatus.CANCELLED, output, error);
    }
}
