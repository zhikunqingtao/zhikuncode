package com.aicodeassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class PythonCapabilityAwareClientCorrelationTest {
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
