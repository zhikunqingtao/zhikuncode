package com.aicodeassistant.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MdcScopeTest {
    @AfterEach
    void clear() { MDC.clear(); }

    @Test
    void restoresPreviousContextAfterSuccess() {
        MDC.put("runId", "outer");
        try (MdcScope ignored = MdcScope.open(Map.of("runId", "inner", "toolUseId", "tool-1"))) {
            assertThat(MDC.get("runId")).isEqualTo("inner");
            assertThat(MDC.get("toolUseId")).isEqualTo("tool-1");
        }
        assertThat(MDC.get("runId")).isEqualTo("outer");
        assertThat(MDC.get("toolUseId")).isNull();
    }

    @Test
    void restoresPreviousContextAfterException() {
        MDC.put("sessionId", "session-1");
        try {
            try (MdcScope ignored = MdcScope.open(Map.of("runId", "run-1"))) {
                throw new IllegalStateException("business failure");
            }
        } catch (IllegalStateException expected) {
            assertThat(expected.getMessage()).isEqualTo("business failure");
        }
        assertThat(MDC.get("sessionId")).isEqualTo("session-1");
        assertThat(MDC.get("runId")).isNull();
    }
}
