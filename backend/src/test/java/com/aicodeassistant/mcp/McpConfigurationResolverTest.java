package com.aicodeassistant.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpConfigurationResolverTest {

    @Test
    void resolvesSecretsFromEnvironmentWithoutEmbeddingThemInMcpJson() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("CONTEXT7_API_KEY", "test-context7-key")
                .withProperty("ALIBABA_CLOUD_ACCESS_KEY_ID", "test-access-key-id")
                .withProperty("GITHUB_TOOLS", "get_me,get_file_contents")
                .withProperty("ZHIKUN_MCP_SERVERS", """
                        {
                          "github": {
                            "command": "zhikun-github-mcp",
                            "env": {"GITHUB_TOOLS": "${GITHUB_TOOLS}"}
                          },
                          "context7": {
                            "type": "HTTP",
                            "url": "https://mcp.context7.com/mcp",
                            "headers": {"Authorization": "Bearer ${CONTEXT7_API_KEY}"}
                          },
                          "alibaba-cloud-ops": {
                            "command": "zhikun-alibaba-cloud-ops-mcp",
                            "args": ["--visible-tools", "${VISIBLE_TOOLS:DescribeInstances}"],
                            "env": {"ALIBABA_CLOUD_ACCESS_KEY_ID": "${ALIBABA_CLOUD_ACCESS_KEY_ID}"}
                          }
                        }
                        """);

        McpConfigurationResolver resolver =
                new McpConfigurationResolver(new ObjectMapper(), environment);

        List<McpServerConfig> configs = resolver.resolveAll();

        assertEquals(3, configs.size());
        McpServerConfig github = configs.stream()
                .filter(config -> "github".equals(config.name()))
                .findFirst().orElseThrow();
        assertEquals("get_me,get_file_contents", github.env().get("GITHUB_TOOLS"));
        McpServerConfig context7 = configs.stream()
                .filter(config -> "context7".equals(config.name()))
                .findFirst().orElseThrow();
        assertEquals("Bearer test-context7-key", context7.headers().get("Authorization"));

        McpServerConfig cloudOps = configs.stream()
                .filter(config -> "alibaba-cloud-ops".equals(config.name()))
                .findFirst().orElseThrow();
        assertEquals("test-access-key-id", cloudOps.env().get("ALIBABA_CLOUD_ACCESS_KEY_ID"));
        assertEquals(List.of("--visible-tools", "DescribeInstances"), cloudOps.args());
    }
}
