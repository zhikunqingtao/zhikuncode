package com.aicodeassistant.mcp.server;

import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * MCP Server 工具处理器 — 复用内部 ToolRegistry + ToolExecutionPipeline。
 * <p>
 * 实现 MCP 协议的 tools/list 和 tools/call 方法，
 * 将 ZhikuCode 内部工具暴露为 MCP 可调用接口。
 *
 * @see <a href="SPEC §4.3">MCP 集成</a>
 */
@Service
public class McpServerToolHandler {

    private static final Logger log = LoggerFactory.getLogger(McpServerToolHandler.class);

    private final ToolRegistry toolRegistry;
    private final ToolExecutionPipeline pipeline;
    private final ObjectMapper objectMapper;

    public McpServerToolHandler(ToolRegistry toolRegistry,
                                 ToolExecutionPipeline pipeline,
                                 ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.pipeline = pipeline;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理 tools/list — 列出所有可用工具。
     *
     * @return MCP tools/list 响应的 tools 数组
     */
    public ArrayNode handleToolsList() {
        List<Tool> tools = toolRegistry.getEnabledTools();
        ArrayNode result = objectMapper.createArrayNode();

        for (Tool tool : tools) {
            ObjectNode toolNode = objectMapper.createObjectNode();
            toolNode.put("name", tool.getName());
            toolNode.put("description", tool.prompt());

            // 将 inputSchema Map 转为 JsonNode
            JsonNode schemaNode = objectMapper.valueToTree(tool.getInputSchema());
            toolNode.set("inputSchema", schemaNode);

            result.add(toolNode);
        }

        log.debug("tools/list: returning {} tools", tools.size());
        return result;
    }

    /**
     * 处理 tools/call — 执行指定工具。
     *
     * @param toolName  工具名称
     * @param arguments 工具参数（JSON object）
     * @param sessionId MCP 会话 ID
     * @return 工具执行结果（MCP 格式）
     */
    @SuppressWarnings("unchecked")
    public ObjectNode handleToolsCall(String toolName, JsonNode arguments, String sessionId) {
        ObjectNode response = objectMapper.createObjectNode();

        try {
            Tool tool = toolRegistry.findByName(toolName);

            // 构建 ToolInput
            Map<String, Object> inputMap = objectMapper.convertValue(arguments, Map.class);
            ToolInput input = ToolInput.from(inputMap);

            // 构建 ToolUseContext
            ToolUseContext context = ToolUseContext.of(
                    System.getProperty("user.dir"),
                    sessionId != null ? sessionId : "mcp-server"
            );

            // 通过 Pipeline 执行（保证权限检查一致）
            ToolExecutionResult execResult = pipeline.execute(tool, input, context);
            ToolResult toolResult = execResult.result();

            // 构建 MCP 响应
            ArrayNode content = objectMapper.createArrayNode();
            ObjectNode textContent = objectMapper.createObjectNode();
            textContent.put("type", "text");
            textContent.put("text", toolResult.content() != null ? toolResult.content() : "");
            content.add(textContent);

            response.set("content", content);
            response.put("isError", toolResult.isError());

            log.info("tools/call: tool={}, isError={}", toolName, toolResult.isError());

        } catch (IllegalArgumentException e) {
            // 工具未找到
            ArrayNode content = objectMapper.createArrayNode();
            ObjectNode errContent = objectMapper.createObjectNode();
            errContent.put("type", "text");
            errContent.put("text", "Tool not found: " + toolName);
            content.add(errContent);
            response.set("content", content);
            response.put("isError", true);
            log.warn("tools/call: tool not found: {}", toolName);

        } catch (Exception e) {
            ArrayNode content = objectMapper.createArrayNode();
            ObjectNode errContent = objectMapper.createObjectNode();
            errContent.put("type", "text");
            errContent.put("text", "Tool execution error: " + e.getMessage());
            content.add(errContent);
            response.set("content", content);
            response.put("isError", true);
            log.error("tools/call error: tool={}, error={}", toolName, e.getMessage(), e);
        }

        return response;
    }
}
