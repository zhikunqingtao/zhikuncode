package com.aicodeassistant.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SelectedMcpRegistryIntegrationTest {

    private McpCapabilityRegistryService registry;

    @BeforeEach
    void loadRegistry() {
        Path registryPath = Path.of(System.getProperty("user.dir"))
                .resolve("../configuration/mcp/mcp_capability_registry.json")
                .normalize();
        assertTrue(Files.isRegularFile(registryPath));
        McpConfiguration configuration = new McpConfiguration();
        configuration.setCapabilityRegistryPath(registryPath.toString());
        registry = new McpCapabilityRegistryService(new ObjectMapper(), configuration);
        registry.loadRegistry();
    }

    @Test
    void selectedGroupsHaveExpectedCountsAndDefaultStates() {
        List<McpCapabilityDefinition> all = registry.listAll();
        assertEquals(83, all.size());
        assertEquals(83, registry.enabledCount());
        assertEquals(all.size(), new HashSet<>(all.stream().map(McpCapabilityDefinition::id).toList()).size());

        assertGroup("mcp_amap_", 13, true);
        assertServer("QwenImage", 3, true);
        assertServer("WanVideo", 8, true);
        assertServer("market-cmgjmcp00074946", 11, true);
        assertServer("market-cmgjmcp00074975", 1, true);
        assertServer("market-cmgjmcp00075341", 2, true);
        assertServer("arxiv_paper", 4, true);
        assertServer("market-cmgjmcp00075018", 1, true);
        assertServer("market-cmgjmcp00074959", 2, true);
        assertServer("market-cmgjmcp00075146", 6, true);
        assertServer("market-cmgjmcp00075054", 8, true);
        assertServer("market-cmgjmcp00075060", 10, true);
        assertServerTransport("arxiv_paper", McpTransportType.SSE);
        assertServerTransport("market-cmgjmcp00074959", McpTransportType.SSE);
        assertServerTransport("market-cmgjmcp00074946", McpTransportType.HTTP);
        assertGroup("mcp_bili_", 0, false);
        assertGroup("mcp_dingtalk_", 0, false);
        assertGroup("mcp_didi_", 0, false);

        assertTrue(registry.findById("mcp_wan25_image_edit").isEmpty());
        assertTrue(registry.findById("mcp_wan25_image_gen").isEmpty());
        assertTrue(registry.findById("mcp_onekey_ip_geolocation").isEmpty());
        assertTrue(registry.findById("mcp_amap_ip_location").orElseThrow().enabled());
    }

    @Test
    void dashscopeMediaEndpointUsesConfiguredAuthorization() {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("llm.providers.dashscope.api-key"))
                .thenReturn("test-dashscope-key");
        McpClientManager manager = new McpClientManager(
                new McpConfiguration(), null, null, null, registry, environment,
                null, null, null, null, null, null);

        McpServerConfig qwen = manager.buildConfigFromRegistry(
                registry.findById("mcp_qwen_image_gen").orElseThrow());
        assertEquals("QwenImage", qwen.name());
        assertEquals("Bearer test-dashscope-key", qwen.headers().get("Authorization"));
    }

    @Test
    void registryManagedServersExposeOnlyEnabledAllowlistedTools() throws Exception {
        McpClientManager manager = new McpClientManager(
                new McpConfiguration(), null, null, null, registry, null,
                null, null, null, null, null, null);
        Method allowed = McpClientManager.class.getDeclaredMethod(
                "isToolAllowed", String.class, String.class);
        allowed.setAccessible(true);

        assertTrue((boolean) allowed.invoke(manager, "amap-maps", "maps_geo"));
        McpCapabilityDefinition defaultDisabled = registry
                .listByServerKey("market-cmgjmcp00074946").getFirst();
        assertFalse((boolean) allowed.invoke(manager,
                defaultDisabled.extractServerKey(), defaultDisabled.toolName()));
        assertFalse((boolean) allowed.invoke(manager, "amap-maps", "unknown_remote_tool"));
        assertTrue((boolean) allowed.invoke(manager, "unmanaged-server", "arbitrary_tool"));
    }

    private void assertGroup(String prefix, int count, boolean enabled) {
        List<McpCapabilityDefinition> definitions = registry.listAll().stream()
                .filter(def -> def.id().startsWith(prefix))
                .toList();
        assertEquals(count, definitions.size());
        assertTrue(definitions.stream().allMatch(def -> def.enabled() == enabled));
    }

    private void assertServer(String serverKey, int count, boolean enabled) {
        List<McpCapabilityDefinition> definitions = registry.listAll().stream()
                .filter(def -> serverKey.equals(def.extractServerKey()))
                .toList();
        assertEquals(count, definitions.size());
        assertTrue(definitions.stream().allMatch(def -> def.enabled() == enabled));
    }

    private void assertServerTransport(String serverKey, McpTransportType transportType) {
        List<McpCapabilityDefinition> definitions = registry.listAll().stream()
                .filter(def -> serverKey.equals(def.extractServerKey()))
                .toList();
        assertFalse(definitions.isEmpty());
        assertTrue(definitions.stream()
                .allMatch(def -> def.resolvedTransportType() == transportType));
    }
}
