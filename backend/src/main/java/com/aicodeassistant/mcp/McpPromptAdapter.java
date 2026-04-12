package com.aicodeassistant.mcp;

import com.aicodeassistant.mcp.McpServerConnection.McpPromptArgument;
import com.aicodeassistant.mcp.McpServerConnection.McpPromptDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP Prompt 适配器 — 将 MCP prompt 模板包装为内部可调用的 slash 命令。
 * <p>
 * 每个 MCP prompt 模板映射为一个 slash 命令，格式为 /mcp-{serverName}-{promptName}。
 * 执行时通过 McpServerConnection 发送 prompts/get 请求获取渲染后的消息。
 *
 * @see <a href="SPEC §11">MCP prompts/list 支持</a>
 */
public class McpPromptAdapter {

    private static final Logger log = LoggerFactory.getLogger(McpPromptAdapter.class);

    private final String serverName;
    private final McpPromptDefinition promptDefinition;
    private final McpServerConnection connection;

    public McpPromptAdapter(String serverName, McpPromptDefinition promptDefinition,
                            McpServerConnection connection) {
        this.serverName = serverName;
        this.promptDefinition = promptDefinition;
        this.connection = connection;
    }

    /**
     * 获取 slash 命令名称。
     * 格式: /mcp-{serverName}-{promptName}
     */
    public String getCommandName() {
        return "/mcp-" + serverName + "-" + promptDefinition.name();
    }

    /**
     * 获取命令描述。
     */
    public String getDescription() {
        return promptDefinition.description() != null
                ? promptDefinition.description()
                : "MCP prompt from " + serverName + ": " + promptDefinition.name();
    }

    /**
     * 获取参数定义列表。
     */
    public List<McpPromptArgument> getArguments() {
        return promptDefinition.arguments() != null
                ? promptDefinition.arguments()
                : List.of();
    }

    /**
     * 执行 prompt — 发送 prompts/get 请求并返回渲染后的消息。
     *
     * @param arguments 用户提供的参数 (name → value)
     * @return 渲染后的 prompt 消息列表（每条消息为 {role, content} 格式）
     */
    public List<Map<String, String>> execute(Map<String, String> arguments) {
        try {
            Map<String, Object> params = Map.of(
                    "name", promptDefinition.name(),
                    "arguments", arguments != null ? arguments : Map.of()
            );

            Object result = connection.request("prompts/get", params);
            if (result instanceof com.fasterxml.jackson.databind.JsonNode jsonNode) {
                com.fasterxml.jackson.databind.JsonNode messagesArray = jsonNode.path("messages");
                if (!messagesArray.isArray()) return List.of();

                return java.util.stream.StreamSupport.stream(messagesArray.spliterator(), false)
                        .map(m -> Map.of(
                                "role", m.path("role").asText("user"),
                                "content", extractContent(m.path("content"))
                        ))
                        .toList();
            }
            return List.of();
        } catch (McpProtocolException e) {
            log.error("Failed to execute MCP prompt '{}' on server '{}': {}",
                    promptDefinition.name(), serverName, e.getMessage());
            return List.of(Map.of("role", "system", "content",
                    "Error executing MCP prompt: " + e.getMessage()));
        }
    }

    /**
     * 获取参数使用说明。
     */
    public String getUsageHint() {
        if (getArguments().isEmpty()) {
            return getCommandName();
        }
        String argsStr = getArguments().stream()
                .map(a -> (a.required() ? "<" + a.name() + ">" : "[" + a.name() + "]"))
                .collect(Collectors.joining(" "));
        return getCommandName() + " " + argsStr;
    }

    // ── Getters ──────────────────────────────────────────────

    public String getServerName() { return serverName; }
    public McpPromptDefinition getPromptDefinition() { return promptDefinition; }

    // ── 内部方法 ──────────────────────────────────────────────

    /**
     * 提取 content 字段 — 支持 string 或 {type, text} 对象。
     */
    private String extractContent(com.fasterxml.jackson.databind.JsonNode contentNode) {
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isObject()) {
            return contentNode.path("text").asText("");
        }
        return contentNode.toString();
    }
}
