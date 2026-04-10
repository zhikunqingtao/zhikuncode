package com.aicodeassistant.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP 配置属性 — 从 application.yml 加载 MCP 服务器配置。
 * <p>
 * 配置示例:
 * <pre>
 * mcp:
 *   servers:
 *     zhipu-websearch:
 *       type: SSE
 *       url: https://mcp.aliyun.com/mcp/...
 *       headers:
 *         Authorization: Bearer sk-xxx
 * </pre>
 *
 * @see McpServerConfig
 * @see McpClientManager
 */
@Configuration
@ConfigurationProperties(prefix = "mcp")
public class McpConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpConfiguration.class);

    /**
     * MCP 服务器配置映射 — key 为服务器名称
     */
    private Map<String, ServerConfig> servers = Map.of();

    /**
     * MCP 工具权限控制 — serverName → blocked tools list
     */
    private Map<String, List<String>> channelPermissions = Map.of();

    public Map<String, ServerConfig> getServers() {
        return servers;
    }

    public void setServers(Map<String, ServerConfig> servers) {
        this.servers = servers;
    }

    public Map<String, List<String>> getChannelPermissions() {
        return channelPermissions;
    }

    public void setChannelPermissions(Map<String, List<String>> channelPermissions) {
        this.channelPermissions = channelPermissions;
    }

    /**
     * 转换为 McpServerConfig 列表
     */
    public List<McpServerConfig> toMcpServerConfigs() {
        List<McpServerConfig> configs = new ArrayList<>();

        servers.forEach((name, config) -> {
            try {
                McpTransportType type = McpTransportType.valueOf(config.getType().toUpperCase());
                McpConfigScope scope = config.getScope() != null
                        ? McpConfigScope.valueOf(config.getScope().toUpperCase())
                        : McpConfigScope.USER;

                McpServerConfig mcpConfig = new McpServerConfig(
                        name,
                        type,
                        null, // command - stdio only
                        List.of(), // args - stdio only
                        Map.of(), // env
                        config.getUrl(),
                        config.getHeaders() != null ? config.getHeaders() : Map.of(),
                        scope
                );
                configs.add(mcpConfig);
                log.info("Loaded MCP server config: {} (type={}, url={})", name, type, config.getUrl());
            } catch (Exception e) {
                log.error("Failed to load MCP server config: {} - {}", name, e.getMessage());
            }
        });

        return configs;
    }

    // ===== 内部配置类 =====

    public static class ServerConfig {
        private String type = "SSE";
        private String url;
        private Map<String, String> headers = Map.of();
        private String scope = "USER";

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }
    }
}
