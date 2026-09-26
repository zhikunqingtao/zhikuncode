package com.aicodeassistant.engine;

import com.aicodeassistant.llm.SummaryRequest;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.Locale;

/** Parse optional capability configuration locally: bad input cannot prevent service startup. */
@Component
public class CompactConfiguration {
    private final Environment environment;
    public CompactConfiguration(Environment environment) { this.environment = environment; }
    public record Settings(boolean enabled, String provider, String model, SummaryRequest.ThinkingMode mode,
                           int completionTokens, int summaryTokens, long timeoutMs, String unavailable) {}
    private String value(String key, String fallback) {
        return environment == null ? fallback : environment.getProperty("app.compact." + key, fallback).trim();
    }
    public Settings settings() {
        try {
            String enabled = value("llm-enabled", "true");
            if ("false".equalsIgnoreCase(enabled)) return unavailable("summary_disabled");
            if (!"true".equalsIgnoreCase(enabled)) return unavailable("invalid_summary_configuration");
            String provider = value("provider", "deepseek");
            String model = value("model", "deepseek-flash");
            var mode = SummaryRequest.ThinkingMode.valueOf(value("thinking-mode", "max").toUpperCase(Locale.ROOT));
            int completion = Integer.parseInt(value("max-completion-tokens", "8192"));
            int summary = Integer.parseInt(value("max-summary-tokens", "4096"));
            long timeout = Long.parseLong(value("timeout-ms", "90000"));
            if (provider.isBlank() || !SummaryRequest.supports(model, mode) || completion <= 0
                    || summary <= 0 || summary > 4096 || summary > completion || timeout <= 0
                    || timeout > Long.MAX_VALUE / 1_000_000L) return unavailable("invalid_summary_configuration");
            return new Settings(true, provider, model, mode, completion, summary, timeout, null);
        } catch (RuntimeException e) { return unavailable("invalid_summary_configuration"); }
    }
    private static Settings unavailable(String reason) {
        return new Settings(false, null, null, null, 0, 4096, 90000, reason);
    }
}
