package com.aicodeassistant.mcp;

import com.aicodeassistant.mcp.roots.McpRootsProvider;
import com.aicodeassistant.mcp.schema.SchemaCompressor;
import com.aicodeassistant.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "RUN_ONEKEY_LIVE_TESTS", matches = "true")
class OneKeyRegistryLiveIntegrationTest {

    @Test
    void selectedRegistryEntriesActivateFourServersAndThirteenDynamicTools() {
        String apiKey = requireApiKey();
        McpConfiguration configuration = new McpConfiguration();
        configuration.setCapabilityRegistryPath(Path.of(System.getProperty("user.dir"))
                .resolve("../configuration/mcp/mcp_capability_registry.json")
                .normalize().toString());

        McpCapabilityRegistryService completeRegistry =
                new McpCapabilityRegistryService(new ObjectMapper(), configuration);
        completeRegistry.loadRegistry();
        List<McpCapabilityDefinition> selected = completeRegistry.listEnabled().stream()
                .filter(def -> def.id().startsWith("mcp_onekey_"))
                .toList();
        assertEquals(13, selected.size());

        McpCapabilityRegistryService selectedRegistry = mock(McpCapabilityRegistryService.class);
        when(selectedRegistry.size()).thenReturn(selected.size());
        when(selectedRegistry.listEnabled()).thenReturn(selected);
        when(selectedRegistry.hasDefinitionsForServer(anyString())).thenAnswer(invocation ->
                completeRegistry.hasDefinitionsForServer(invocation.getArgument(0)));
        when(selectedRegistry.findEnabledByToolName(anyString(), anyString())).thenAnswer(invocation ->
                completeRegistry.findEnabledByToolName(invocation.getArgument(0), invocation.getArgument(1))
                        .filter(def -> def.id().startsWith("mcp_onekey_")));

        McpConfigurationResolver resolver = mock(McpConfigurationResolver.class);
        when(resolver.resolveAll()).thenReturn(List.of());
        McpApprovalService approval = mock(McpApprovalService.class);
        when(approval.isTrusted(any())).thenReturn(true);
        Environment environment = mock(Environment.class);
        when(environment.getProperty("llm.providers.dashscope.api-key")).thenReturn(apiKey);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);

        McpClientManager manager = new McpClientManager(
                configuration, resolver, toolRegistry, approval, selectedRegistry, environment,
                null, null, new SchemaCompressor(), mock(McpRootsProvider.class), null, null);
        try {
            manager.start();
            assertEquals(4, manager.listConnections().size());
            assertTrue(manager.listConnections().stream()
                    .allMatch(connection -> connection.getStatus() == McpConnectionStatus.CONNECTED));
            assertEquals(13, manager.listConnections().stream()
                    .mapToInt(connection -> connection.getTools().size()).sum());
            ArgumentCaptor<McpToolAdapter> adapters = ArgumentCaptor.forClass(McpToolAdapter.class);
            verify(toolRegistry, times(13)).registerDynamic(adapters.capture());
            Set<String> publicNames = adapters.getAllValues().stream()
                    .map(McpToolAdapter::getName)
                    .collect(Collectors.toSet());
            assertEquals(13, publicNames.size());
            assertTrue(publicNames.stream()
                    .allMatch(name -> name.length() <= 64 && name.matches("[A-Za-z0-9_-]+")));
        } finally {
            manager.shutdown();
        }
    }

    private static String requireApiKey() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("LLM_PROVIDER_DASHSCOPE_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "LLM_PROVIDER_DASHSCOPE_API_KEY or DASHSCOPE_API_KEY is required for live OneKey tests");
        }
        return apiKey;
    }
}
