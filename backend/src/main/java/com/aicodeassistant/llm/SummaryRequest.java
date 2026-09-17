package com.aicodeassistant.llm;

/** Deadline is monotonic and shared by all HTTP attempts of this logical summary. */
public record SummaryRequest(String model, ThinkingMode thinkingMode, String systemPrompt,
                             String userContent, int maxCompletionTokens, long deadlineNanos) {
    public enum ThinkingMode { MAX, LOW, OFF }
    public static boolean supports(String model, ThinkingMode mode) {
        return mode != null && ("deepseek-v4.1-flash".equals(model)
                || "qwen3.8-flash".equals(model) && mode != ThinkingMode.LOW
                || "qwen3.7-plus".equals(model) && mode == ThinkingMode.OFF);
    }
}
