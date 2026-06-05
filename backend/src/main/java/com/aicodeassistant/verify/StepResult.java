package com.aicodeassistant.verify;

import java.util.List;

public record StepResult(
    int index,
    String action,
    boolean ok,
    long durationMs,
    String error,
    List<String> consoleErrors,
    String screenshotBase64
) {}
