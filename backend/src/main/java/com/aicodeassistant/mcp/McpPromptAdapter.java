package com.aicodeassistant.mcp;

import com.aicodeassistant.mcp.McpServerConnection.McpPromptArgument;
import com.aicodeassistant.mcp.McpServerConnection.McpPromptDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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

    /** 参数值最大长度限制 */
    private static final int MAX_ARGUMENT_VALUE_LENGTH = 10000;

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
     * 验证执行参数 — 检查必需参数、类型匹配、长度限制。
     *
     * @param arguments 用户提供的参数
     * @return 验证错误列表（空列表表示验证通过）
     */
    public List<String> validateArguments(Map<String, String> arguments) {
        List<String> errors = new ArrayList<>();
        Map<String, String> args = arguments != null ? arguments : Map.of();

        for (McpPromptArgument argDef : getArguments()) {
            String value = args.get(argDef.name());
            // 必需参数检查
            if (argDef.required() && (value == null || value.isBlank())) {
                errors.add("Required argument '" + argDef.name() + "' is missing or empty");
                continue;
            }
            // 长度限制检查
            if (value != null && value.length() > MAX_ARGUMENT_VALUE_LENGTH) {
                errors.add("Argument '" + argDef.name() + "' exceeds maximum length of " + MAX_ARGUMENT_VALUE_LENGTH);
            }
        }
        return errors;
    }

    /**
     * 执行 prompt — 发送 prompts/get 请求并返回渲染后的消息。
     * 包含完整参数验证、执行日志记录和详细错误信息。
     *
     * @param arguments 用户提供的参数 (name → value)
     * @return 渲染后的 prompt 消息列表（每条消息为 {role, content} 格式）
     */
    public List<Map<String, String>> execute(Map<String, String> arguments) {
        long startTime = System.currentTimeMillis();
        String promptName = promptDefinition.name();

        // 参数验证
        List<String> validationErrors = validateArguments(arguments);
        if (!validationErrors.isEmpty()) {
            log.warn("Parameter validation failed for prompt '{}' on server '{}': {}",
                    promptName, serverName, validationErrors);
            String errorDetail = String.join("; ", validationErrors);
            return List.of(Map.of("role", "system", "content",
                    "Parameter validation failed: " + errorDetail));
        }

        log.info("Executing MCP prompt '{}' on server '{}' with {} arguments",
                promptName, serverName, arguments != null ? arguments.size() : 0);

        try {
            Map<String, Object> params = Map.of(
                    "name", promptName,
                    "arguments", arguments != null ? arguments : Map.of()
            );

            Object result = connection.request("prompts/get", params);
            if (result instanceof com.fasterxml.jackson.databind.JsonNode jsonNode) {
                com.fasterxml.jackson.databind.JsonNode messagesArray = jsonNode.path("messages");
                if (!messagesArray.isArray()) {
                    log.warn("Prompt '{}' returned non-array messages from server '{}'",
                            promptName, serverName);
                    return List.of();
                }

                List<Map<String, String>> messages = java.util.stream.StreamSupport
                        .stream(messagesArray.spliterator(), false)
                        .map(m -> Map.of(
                                "role", m.path("role").asText("user"),
                                "content", extractContent(m.path("content"))
                        ))
                        .toList();

                long duration = System.currentTimeMillis() - startTime;
                log.info("Successfully executed prompt '{}' on server '{}' — {} messages, {}ms",
                        promptName, serverName, messages.size(), duration);
                return messages;
            }

            long duration = System.currentTimeMillis() - startTime;
            log.warn("Prompt '{}' returned unexpected result type from server '{}', {}ms",
                    promptName, serverName, duration);
            return List.of();
        } catch (McpProtocolException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Failed to execute MCP prompt '{}' on server '{}' after {}ms: {}",
                    promptName, serverName, duration, e.getMessage());
            return List.of(Map.of("role", "system", "content",
                    "Error executing prompt '" + promptName + "': " + e.getMessage()));
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Unexpected error executing MCP prompt '{}' on server '{}' after {}ms",
                    promptName, serverName, duration, e);
            return List.of(Map.of("role", "system", "content",
                    "Unexpected error executing prompt '" + promptName + "': " + e.getMessage()));
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
