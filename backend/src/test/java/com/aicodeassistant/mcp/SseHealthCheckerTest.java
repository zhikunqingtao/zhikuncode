package com.aicodeassistant.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SseHealthCheckerTest {

    @Test
    void oneTransientPingFailureDoesNotDegradeOrReconnect() {
        McpClientManager manager = mock(McpClientManager.class);
        McpServerConnection connection = mock(McpServerConnection.class);
        when(manager.listConnections()).thenReturn(List.of(connection));
        when(connection.getStatus()).thenReturn(McpConnectionStatus.CONNECTED);
        when(connection.getName()).thenReturn("test-server");
        when(connection.sendHealthPing()).thenReturn(false);
        SseHealthChecker checker = new SseHealthChecker(manager);

        checker.performActiveHealthCheck();

        assertThat(checker.getConsecutiveFailures("test-server")).isEqualTo(1);
        verify(connection, never()).setStatus(McpConnectionStatus.DEGRADED);
        verify(manager, never()).scheduleReconnect("test-server");
    }

    @Test
    void twoConsecutiveFailuresDegradeAndScheduleOneReconnect() {
        McpClientManager manager = mock(McpClientManager.class);
        McpServerConnection connection = mock(McpServerConnection.class);
        when(manager.listConnections()).thenReturn(List.of(connection));
        when(connection.getStatus()).thenReturn(McpConnectionStatus.CONNECTED);
        when(connection.getName()).thenReturn("test-server");
        when(connection.sendHealthPing()).thenReturn(false);
        SseHealthChecker checker = new SseHealthChecker(manager);

        checker.performActiveHealthCheck();
        checker.performActiveHealthCheck();

        assertThat(checker.getConsecutiveFailures("test-server")).isEqualTo(2);
        verify(connection).setStatus(McpConnectionStatus.DEGRADED);
        verify(manager).scheduleReconnect("test-server");
    }

    @Test
    void successfulPingResetsFailureStreak() {
        McpClientManager manager = mock(McpClientManager.class);
        McpServerConnection connection = mock(McpServerConnection.class);
        when(manager.listConnections()).thenReturn(List.of(connection));
        when(connection.getStatus()).thenReturn(McpConnectionStatus.CONNECTED);
        when(connection.getName()).thenReturn("test-server");
        when(connection.sendHealthPing()).thenReturn(false, true, false);
        SseHealthChecker checker = new SseHealthChecker(manager);

        checker.performActiveHealthCheck();
        checker.performActiveHealthCheck();
        checker.performActiveHealthCheck();

        assertThat(checker.getConsecutiveFailures("test-server")).isEqualTo(1);
        assertThat(checker.getLastSuccessfulPing("test-server")).isNotNull();
        verify(manager, never()).scheduleReconnect("test-server");
    }
}
