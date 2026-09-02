package com.aicodeassistant.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServiceStateTest {

    @TempDir
    Path tempDir;

    @Test
    void selectedServicesDefaultToDisabledAndExplicitChoicePersistsSeparately() throws Exception {
        Path registryPath = tempDir.resolve("registry.json");
        Path statePath = tempDir.resolve("state/mcp-services.json");
        Files.writeString(registryPath, """
                {
                  "mcp_tools": [{
                    "id": "demo_tool",
                    "name": "Demo Tool",
                    "toolName": "demo",
                    "serverKey": "demo-server",
                    "url": "https://example.test/mcp",
                    "transportType": "HTTP",
                    "domain": "test",
                    "category": "MCP_TOOL",
                    "description": "demo",
                    "briefDescription": "demo",
                    "input": {},
                    "output": {},
                    "timeoutMs": 1000,
                    "enabled": true,
                    "videoCallEnabled": false
                  }]
                }
                """);

        McpConfiguration configuration = new McpConfiguration();
        configuration.setCapabilityRegistryPath(registryPath.toString());
        configuration.setServiceStatePath(statePath.toString());

        McpCapabilityRegistryService first =
                new McpCapabilityRegistryService(new ObjectMapper(), configuration);
        first.loadRegistry();
        assertTrue(first.isServiceEnabled("demo-server"));
        for (String serverKey : new String[] {
                "github", "context7", "alibaba-cloud-ops",
                "market-cmgjmcp00075019",
                "market-cmgjmcp00074976", "market-cmgjmcp00074980",
                "market-cmgjmcp00075121",
                "market-cmgjmcp00074946", "market-cmgjmcp00074975",
                "market-cmgjmcp00075341", "arxiv_paper",
                "market-cmgjmcp00075018", "market-cmgjmcp00074959",
                "market-cmgjmcp00075146", "market-cmgjmcp00075054",
                "market-cmgjmcp00075060"
        }) {
            assertFalse(first.isServiceEnabled(serverKey), serverKey);
        }
        assertEquals(1, first.listRuntimeEnabled().size());

        first.setServiceEnabled("demo-server", false);
        assertTrue(Files.isRegularFile(statePath));
        assertFalse(first.isServiceEnabled("demo-server"));
        assertEquals(0, first.listRuntimeEnabled().size());

        McpCapabilityRegistryService reloaded =
                new McpCapabilityRegistryService(new ObjectMapper(), configuration);
        reloaded.loadRegistry();
        assertFalse(reloaded.isServiceEnabled("demo-server"));
        assertEquals(0, reloaded.listRuntimeEnabled().size());

        reloaded.setServiceEnabled("demo-server", true);
        McpCapabilityRegistryService enabled =
                new McpCapabilityRegistryService(new ObjectMapper(), configuration);
        enabled.loadRegistry();
        assertTrue(enabled.isServiceEnabled("demo-server"));
        assertEquals(1, enabled.listRuntimeEnabled().size());
    }
}
