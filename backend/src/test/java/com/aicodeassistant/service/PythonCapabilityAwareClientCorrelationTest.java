package com.aicodeassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;
import org.mockito.ArgumentCaptor;

class PythonCapabilityAwareClientCorrelationTest {
    @ParameterizedTest
    @CsvSource({"503,BROWSER_CAPACITY_REACHED", "409,BROWSER_SESSION_CONFLICT",
            "503,BROWSER_CLEANUP_PENDING", "503,BROWSER_NOT_RUNNING", "409,JOURNEY_CANCELLED",
            "499,JOURNEY_CLIENT_DISCONNECTED", "504,JOURNEY_DEADLINE_EXCEEDED"})
    void journeyRefusalsPreserveCodeWithoutRetry(int status, String code) throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn("{\"detail\":\"" + code + "\"}");
        when(http.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        var client = spy(new PythonCapabilityAwareClient("http://python.test", new ObjectMapper(), http));
        doReturn(true).when(client).isCapabilityAvailable("BROWSER_AUTOMATION");
        assertThatThrownBy(() -> client.callJourneyIfAvailable("BROWSER_AUTOMATION",
                "/api/browser/journey/run", Map.of(), String.class, Duration.ofSeconds(130)))
                .isInstanceOf(PythonCapabilityAwareClient.JourneyCallException.class).hasMessage(code);
        verify(http).send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"detail\":\"private driver failure\"}", "not json", "null", ""})
    void unknownJourneyErrorsKeepGenericFallbackWithoutRetry(String body) throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        var client = spy(new PythonCapabilityAwareClient("http://python.test", new ObjectMapper(), http));
        doReturn(true).when(client).isCapabilityAvailable("BROWSER_AUTOMATION");
        assertThat(client.callJourneyIfAvailable("BROWSER_AUTOMATION", "/api/browser/journey/run",
                Map.of(), String.class, Duration.ofSeconds(130))).isEmpty();
        verify(http).send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    @Test
    void correlationIdStaysStableAcrossRetries() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> first = mock(HttpResponse.class);
        @SuppressWarnings("unchecked") HttpResponse<String> second = mock(HttpResponse.class);
        when(first.statusCode()).thenReturn(500);
        when(first.body()).thenReturn("temporary");
        when(second.statusCode()).thenReturn(200);
        when(second.body()).thenReturn("\"ok\"");
        when(httpClient.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(first, second);

        MDC.put("runId", "run-123");
        MDC.put("sessionId", "session-456");
        PythonCapabilityAwareClient client = new PythonCapabilityAwareClient(
                "http://python.test", new ObjectMapper(), httpClient);

        assertThat(client.callWithRetry("/api/analysis/test", Map.of("safe", true), String.class))
                .contains("ok");
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(2)).send(requests.capture(),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        List<HttpRequest> sent = requests.getAllValues();
        assertThat(sent.get(0).headers().firstValue("X-Request-Id")).isPresent();
        assertThat(sent.get(0).headers().firstValue("X-Request-Id"))
                .isEqualTo(sent.get(1).headers().firstValue("X-Request-Id"));
        assertThat(sent.stream().map(request -> request.headers().firstValue("X-Attempt").orElseThrow()))
                .containsExactly("1", "2");
        assertThat(sent).allSatisfy(request -> {
            assertThat(request.headers().firstValue("X-Run-Id")).contains("run-123");
            assertThat(request.headers().firstValue("X-Session-Id")).contains("session-456");
        });
    }

    @Test
    void invalidDiagnosticContextIsOmittedWithoutChangingRequest() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("\"ok\"");
        when(httpClient.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(response);

        MDC.put("runId", "invalid run id with spaces");
        MDC.put("sessionId", "session-valid");
        PythonCapabilityAwareClient client = new PythonCapabilityAwareClient(
                "http://python.test", new ObjectMapper(), httpClient);

        assertThat(client.callWithRetry("/api/analysis/test", Map.of(), String.class))
                .contains("ok");
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(request.capture(),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertThat(request.getValue().headers().firstValue("X-Run-Id")).isEmpty();
        assertThat(request.getValue().headers().firstValue("X-Session-Id"))
                .contains("session-valid");
    }
}
