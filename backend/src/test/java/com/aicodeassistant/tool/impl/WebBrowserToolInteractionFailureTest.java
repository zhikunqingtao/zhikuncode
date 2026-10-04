package com.aicodeassistant.tool.impl;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.service.PythonCapabilityAwareClient;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Python response mapping must preserve interaction evidence without making failures retryable. */
class WebBrowserToolInteractionFailureTest {

    @TempDir Path tempDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private PythonCapabilityAwareClient pythonClient;
    private WebBrowserTool tool;

    @BeforeEach
    void setUp() {
        pythonClient = mock(PythonCapabilityAwareClient.class);
        tool = new WebBrowserTool(pythonClient, mock(FeatureFlagService.class), mapper,
                mock(AtomicFileWriter.class));
    }

    @ParameterizedTest
    @CsvSource({"click,BROWSER_CLICK_FAILED", "type,BROWSER_TYPE_FAILED"})
    void pythonFailureKeepsInteractionFactsWithoutSerializingOtherData(String action, String code) throws Exception {
        var response = mapper.readValue("""
                {
                  "success": false,
                  "error_code": "%s",
                  "error_message": "Both native and fallback actions failed",
                  "data": {
                    "success": false,
                    "method": "both_failed",
                    "warning": "Native action had already been attempted",
                    "screenshot_base64": "must-not-enter-model-context",
                    "unrelated": {"large": "not-serialized"}
                  }
                }
                """.formatted(code), WebBrowserTool.BrowserResponse.class);
        stub(action, response);

        ToolResult result = call(action);

        assertThat(result.isError()).isTrue();
        assertThat(result.failureType()).isEqualTo(ToolResult.ToolFailureType.PROVIDER);
        assertThat(result.failureCode()).isEqualTo(code);
        assertThat(result.retryability()).isEqualTo(ToolResult.Retryability.NEVER);
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.content()).startsWith("Both native and fallback actions failed")
                .contains("method: both_failed", "warning: Native action had already been attempted")
                .doesNotContain("must-not-enter-model-context", "not-serialized", "screenshot_base64");
        assertThat(result.metadata()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "method", "both_failed", "warning", "Native action had already been attempted"));
        verifySingleInvocation(action);
    }

    @ParameterizedTest
    @ValueSource(strings = {"click", "type"})
    void legacyFailureWithoutDataKeepsOriginalContract(String action) {
        stub(action, new WebBrowserTool.BrowserResponse(false, null, "SESSION_NOT_FOUND", "Session gone"));

        ToolResult result = call(action);

        assertThat(result.failureCode()).isEqualTo("SESSION_NOT_FOUND");
        assertThat(result.content()).isEqualTo("Session gone");
        assertThat(result.retryability()).isEqualTo(ToolResult.Retryability.NEVER);
        assertThat(result.metadata()).isEmpty();
        verifySingleInvocation(action);
    }

    @Test
    void absentWarningIsNotInventedAndUnrelatedActionIsUnchanged() {
        stub("click", new WebBrowserTool.BrowserResponse(false, Map.of("method", "both_failed"),
                "BROWSER_CLICK_FAILED", "Failed"));
        ToolResult click = call("click");
        assertThat(click.content()).isEqualTo("Failed\nmethod: both_failed");
        assertThat(click.metadata()).containsExactlyInAnyOrderEntriesOf(Map.of("method", "both_failed"));
        verifySingleInvocation("click");

        stub("evaluate", new WebBrowserTool.BrowserResponse(false,
                Map.of("method", "js_fallback", "warning", "unchanged"), "JS_EVALUATION_ERROR", "Failed"));
        ToolResult evaluate = call("evaluate");
        assertThat(evaluate.content()).isEqualTo("Failed");
        assertThat(evaluate.metadata()).isEmpty();
        assertThat(evaluate.retryability()).isEqualTo(ToolResult.Retryability.NEVER);
        verifySingleInvocation("evaluate");
    }

    @ParameterizedTest
    @CsvSource({"click,playwright", "click,js_fallback", "type,playwright", "type,js_fallback"})
    void nativeAndFallbackSuccessRetainOriginalJson(String action, String method) throws Exception {
        Map<String, Object> data = Map.of("method", method, "success", true, "warning", "original warning");
        stub(action, new WebBrowserTool.BrowserResponse(true, data, null, null));

        ToolResult result = call(action);

        assertThat(result.isError()).isFalse();
        assertThat(mapper.readTree(result.content())).isEqualTo(mapper.valueToTree(data));
        assertThat(result.metadata()).isEmpty();
        verifySingleInvocation(action);
    }

    @Test
    void unavailableResponseKeepsExistingPolicy() {
        when(pythonClient.callIfAvailable(eq("BROWSER_AUTOMATION"), eq("/api/browser/click"),
                anyMap(), eq(WebBrowserTool.BrowserResponse.class))).thenReturn(Optional.empty());
        ToolResult result = call("click");
        assertThat(result.failureCode()).isEqualTo("BROWSER_AUTOMATION_UNAVAILABLE");
        assertThat(result.retryability()).isEqualTo(ToolResult.Retryability.SAFE_READ_ONLY);
        verifySingleInvocation("click");
    }

    private void stub(String action, WebBrowserTool.BrowserResponse response) {
        when(pythonClient.callIfAvailable(eq("BROWSER_AUTOMATION"), eq("/api/browser/" + action),
                anyMap(), eq(WebBrowserTool.BrowserResponse.class))).thenReturn(Optional.of(response));
    }

    private ToolResult call(String action) {
        return tool.call(ToolInput.from(Map.of("action", action, "selector", "#target", "text", "value")),
                ToolUseContext.of(tempDir.toString(), "test-session"));
    }

    private void verifySingleInvocation(String action) {
        verify(pythonClient).callIfAvailable(eq("BROWSER_AUTOMATION"), eq("/api/browser/" + action),
                anyMap(), eq(WebBrowserTool.BrowserResponse.class));
        verifyNoMoreInteractions(pythonClient);
    }
}
