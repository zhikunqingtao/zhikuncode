package com.aicodeassistant.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfEnvironmentVariable(named = "RUN_EXTERNAL_MCP_LIVE_TESTS", matches = "true")
class ExternalMcpLiveSmokeTest {

    @Test
    void context7AndAlibabaCloudOpsCompleteTheZhikunCodeHandshake() {
        McpConfigurationResolver resolver =
                new McpConfigurationResolver(new ObjectMapper(), new StandardEnvironment());

        for (String name : List.of("context7", "alibaba-cloud-ops")) {
            McpServerConfig config = resolver.resolveAll().stream()
                    .filter(candidate -> name.equals(candidate.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing MCP config: " + name));
            McpServerConnection connection = new McpServerConnection(config);
            try {
                connection.connect();
                assertEquals(McpConnectionStatus.CONNECTED, connection.getStatus(), name);
                Set<String> tools = connection.getTools().stream()
                        .map(McpServerConnection.McpToolDefinition::name)
                        .collect(Collectors.toSet());
                if ("context7".equals(name)) {
                    assertEquals(Set.of("resolve-library-id", "query-docs"), tools);
                } else {
                    assertEquals(Set.of(
                            "ECS_DescribeInstances",
                            "ECS_DescribeRegions",
                            "ECS_DescribeSecurityGroups",
                            "OSS_ListBuckets",
                            "OSS_ListObjects"), tools);
                }
            } finally {
                connection.close();
            }
        }
    }
}
