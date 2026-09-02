package com.aicodeassistant.mcp;

import com.aicodeassistant.engine.QueryEngine;
import com.aicodeassistant.mcp.progress.McpProgressTracker;
import com.aicodeassistant.mcp.roots.McpRootsProvider;
import com.aicodeassistant.mcp.schema.SchemaCompressor;
import com.aicodeassistant.tool.ToolRegistry;
import com.aicodeassistant.websocket.WebSocketSessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class McpClientManagerLifecycleTest {
    @Test
    void disabledMcpToolsNeverEnterTheToolRegistry() throws Exception {
        ToolRegistry tools = mock(ToolRegistry.class);
        McpCapabilityRegistryService registry = mock(McpCapabilityRegistryService.class);
        when(registry.isServiceEnabled("disabled-server")).thenReturn(false);
        McpConfiguration configuration = new McpConfiguration();
        McpClientManager manager = new McpClientManager(
                configuration, mock(McpConfigurationResolver.class), tools,
                mock(McpApprovalService.class), registry, mock(Environment.class),
                mock(SimpMessagingTemplate.class), mock(WebSocketSessionManager.class),
                mock(SchemaCompressor.class), mock(McpRootsProvider.class),
                mock(McpProgressTracker.class), mock(QueryEngine.class));
        McpServerConnection connection = mock(McpServerConnection.class);
        when(connection.getName()).thenReturn("disabled-server");
        when(connection.getTools()).thenReturn(List.of(
                new McpServerConnection.McpToolDefinition(
                        "hidden_tool", "must not reach model context", Map.of())));

        Method register = McpClientManager.class.getDeclaredMethod(
                "registerToolsFromConnection", McpServerConnection.class);
        register.setAccessible(true);
        register.invoke(manager, connection);

        verify(tools, never()).registerDynamic(any());
        manager.shutdown();
    }

    @Test
    void configuredServersRespectAnExplicitDisabledPreference() {
        McpConfiguration configuration = new McpConfiguration();
        McpConfigurationResolver resolver = mock(McpConfigurationResolver.class);
        McpServerConfig context7 = McpServerConfig.http(
                "context7", "https://mcp.context7.com/mcp");
        when(resolver.resolveAll()).thenReturn(List.of(context7));
        McpCapabilityRegistryService registry = mock(McpCapabilityRegistryService.class);
        when(registry.isServiceEnabled("context7")).thenReturn(false);

        McpClientManager manager = new McpClientManager(
                configuration, resolver, mock(ToolRegistry.class), mock(McpApprovalService.class),
                registry, mock(Environment.class), mock(SimpMessagingTemplate.class),
                mock(WebSocketSessionManager.class), mock(SchemaCompressor.class),
                mock(McpRootsProvider.class), mock(McpProgressTracker.class), mock(QueryEngine.class));

        manager.start();

        assertEquals(0, manager.connectionCount());
        assertEquals(List.of("context7"), manager.listConfiguredServers().stream()
                .map(McpServerConfig::name).toList());
        manager.shutdown();
    }

    @Test
    void removedConnectionCannotReconnectOrTouchReplacementWithSameName() throws Exception {
        ToolRegistry tools = mock(ToolRegistry.class);
        McpClientManager manager = new McpClientManager(
                mock(McpConfiguration.class), mock(McpConfigurationResolver.class), tools,
                mock(McpApprovalService.class), mock(McpCapabilityRegistryService.class),
                mock(Environment.class), mock(SimpMessagingTemplate.class),
                mock(WebSocketSessionManager.class), mock(SchemaCompressor.class),
                mock(McpRootsProvider.class), mock(McpProgressTracker.class), mock(QueryEngine.class));
        setRunning(manager, true);

        McpServerConnection removed = mock(McpServerConnection.class);
        when(removed.getReconnectAttempts()).thenReturn(0);
        connections(manager).put("same-name", removed);
        scheduleDelayed(manager, "same-name", removed);

        manager.removeServer("same-name");
        McpServerConnection replacement = mock(McpServerConnection.class);
        connections(manager).put("same-name", replacement);
        Thread.sleep(1_300);

        verify(removed, never()).connect();
        verify(replacement, never()).connect();
        verify(removed).close();
        verify(tools).unregisterByPrefix("mcp__same-name__");
        manager.shutdown();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, McpServerConnection> connections(McpClientManager manager) throws Exception {
        Field field = McpClientManager.class.getDeclaredField("connections");
        field.setAccessible(true);
        return (Map<String, McpServerConnection>) field.get(manager);
    }

    private static void setRunning(McpClientManager manager, boolean running) throws Exception {
        Field field = McpClientManager.class.getDeclaredField("running");
        field.setAccessible(true);
        field.set(manager, running);
    }

    private static void scheduleDelayed(McpClientManager manager, String id,
                                        McpServerConnection connection) throws Exception {
        Method method = McpClientManager.class.getDeclaredMethod(
                "scheduleDelayedReconnect", String.class, McpServerConnection.class);
        method.setAccessible(true);
        method.invoke(manager, id, connection);
    }
}
