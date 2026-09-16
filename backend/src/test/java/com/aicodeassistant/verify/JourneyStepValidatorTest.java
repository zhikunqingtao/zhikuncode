package com.aicodeassistant.verify;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class JourneyStepValidatorTest {
    @Test void rejectsTheObservedIncorrectJourneyFields() {
        assertThat(JourneyStepValidator.validate(List.of(Map.of("action", "assert_status", "status", 200)), "http_api"))
                .contains("expected_code");
        assertThat(JourneyStepValidator.validate(List.of(Map.of("action", "assert_text", "text", "Title")), "http_api"))
                .contains("unsupported action");
        assertThat(JourneyStepValidator.validate(List.of(Map.of("action", "assert_text", "text", "Title")), "browser"))
                .contains("selector");
        assertThat(JourneyStepValidator.validate(List.of(Map.of("action", "wait_for", "js", "window.ready")), "browser"))
                .contains("js is unsupported");
    }

    @Test void acceptsDocumentedBrowserAndHttpJourneys() {
        assertThat(JourneyStepValidator.validate(List.of(
                Map.of("action", "navigate", "url", "/"),
                Map.of("action", "wait_for", "selector", "#loading.done", "state", "attached"),
                Map.of("action", "assert_text", "selector", "body", "expected", "Title")), "browser")).isNull();
        assertThat(JourneyStepValidator.validate(List.of(Map.of("action", "http_get", "url", "/api/health"),
                Map.of("action", "assert_status", "expected_code", 200)), "http_api")).isNull();
    }
}
