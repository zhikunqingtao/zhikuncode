package com.aicodeassistant.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * MCP 能力定义 — 对应 mcp_capability_registry.json 中的单条工具定义。
 * <p>
 * 提供工具完整元数据: SSE 端点、API Key、超时、描述、输入输出 Schema 等。
 *
 * @see McpCapabilityRegistryService
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpCapabilityDefinition(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("toolName") String toolName,
        @JsonProperty("sseUrl") String sseUrl,
        @JsonProperty("apiKeyConfig") String apiKeyConfig,
        @JsonProperty("apiKeyDefault") String apiKeyDefault,
        @JsonProperty("domain") String domain,
        @JsonProperty("category") String category,
        @JsonProperty("briefDescription") String briefDescription,
        @JsonProperty("videoCallSummary") String videoCallSummary,
        @JsonProperty("description") String description,
        @JsonProperty("input") Map<String, Object> input,
        @JsonProperty("output") Map<String, Object> output,
        @JsonProperty("timeoutMs") int timeoutMs,
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("videoCallEnabled") boolean videoCallEnabled
) {
    /** 快捷构造: 切换 enabled 状态 */
    public McpCapabilityDefinition withEnabled(boolean newEnabled) {
        return new McpCapabilityDefinition(
                id, name, toolName, sseUrl, apiKeyConfig, apiKeyDefault,
                domain, category, briefDescription, videoCallSummary, description,
                input, output, timeoutMs, newEnabled, videoCallEnabled);
    }

    /** 从 sseUrl 提取服务器 key (URL path 倒数第二段) */
    public String extractServerKey() {
        if (sseUrl == null) return id;
        try {
            String path = java.net.URI.create(sseUrl).getPath();
            String[] segments = java.util.Arrays.stream(path.split("/"))
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
            return segments.length >= 2 ? segments[segments.length - 2] : id;
        } catch (Exception e) {
            return id;
        }
    }
}
