package com.aicodeassistant.verify;

import java.util.List;

public record StepResult(
    int index,
    String action,
    boolean ok,
    long durationMs,
    String error,
    List<String> consoleErrors,
    String screenshotBase64,
    String method,
    String warning,
    String screenshotError
) {
    /** Older callers did not record interaction method or capture failures. */
    public StepResult(int index, String action, boolean ok, long durationMs, String error,
                      List<String> consoleErrors, String screenshotBase64) {
        this(index, action, ok, durationMs, error, consoleErrors, screenshotBase64, null, null, null);
    }
}
