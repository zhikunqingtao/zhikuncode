package com.aicodeassistant.tool.impl;

import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ToolSearchTool — 搜索延迟加载的工具。
 * <p>
 * 当工具池规模较大时，非核心工具标记为 shouldDefer=true，
 * 模型需先通过 ToolSearchTool 搜索到目标工具后才能调用，
 * 减少初始 prompt 的 token 占用。
 *
 * @see <a href="SPEC section 4.1.17">ToolSearchTool</a>
 */
@Component
public class ToolSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ToolSearchTool.class);

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public ToolSearchTool(@Lazy ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "ToolSearch";
    }

    @Override
    public String getDescription() {
        return "Search for available tools by keyword. Use this to discover tools " +
                "that are not loaded by default. Provide a search query to find " +
                "tools related to specific functionality (e.g., 'jupyter', 'powershell', 'lsp').";
    }

    @Override
    public String prompt() {
        return """
                Fetches full schema definitions for deferred tools so they can be called.
                
                Deferred tools appear by name in system-reminder messages. Until fetched, only \
                the name is known \u2014 there is no parameter schema, so the tool cannot be invoked. \
                This tool takes a query, matches it against the deferred tool list, and returns \
                the matched tools' complete JSONSchema definitions. Once a tool's schema appears \
                in that result, it is callable exactly like any tool defined at the top of the prompt.
                
                Query forms:
                - "select:Read,Edit,Grep" \u2014 fetch these exact tools by name
                - "notebook jupyter" \u2014 keyword search, up to max_results best matches
                - "+slack send" \u2014 require "slack" in the name, rank by remaining terms
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "Search keywords to find tools (e.g., 'jupyter', 'powershell', 'lsp')"
                        )
                ),
                "required", List.of("query")
        );
    }

    @Override
    public String getGroup() {
        return "general";
    }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.NONE;
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return true;
    }

    @Override
    public boolean shouldDefer() {
        return false; // ToolSearchTool 自身始终加载
    }

    @Override
    public boolean alwaysLoad() {
        return true;
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String query = input.getString("query");
        if (query == null || query.isBlank()) {
            return ToolResult.error("Query parameter is required");
        }

        log.debug("ToolSearch query: '{}'", query);

        List<Tool> matchedTools;

        if (query.startsWith("select:")) {
            // "select:Read,Edit,Grep" — 按名称精确匹配
            String[] names = query.substring("select:".length()).split(",");
            matchedTools = new ArrayList<>();
            for (String name : names) {
                String trimmed = name.trim();
                if (trimmed.isEmpty()) continue;
                toolRegistry.findByNameOptional(trimmed).ifPresent(matchedTools::add);
            }
        } else if (query.startsWith("+")) {
            // "+slack send" — 要求名称包含第一个词，按其余词排名
            String rest = query.substring(1).trim();
            String[] parts = rest.split("\\s+", 2);
            String requiredInName = parts[0].toLowerCase();
            matchedTools = toolRegistry.getAllTools().stream()
                    .filter(tool -> tool.getName().toLowerCase().contains(requiredInName))
                    .toList();
        } else {
            // 关键词搜索
            String queryLower = query.toLowerCase();
            matchedTools = toolRegistry.getAllTools().stream()
                    .filter(tool -> matchesQuery(tool, queryLower))
                    .toList();
        }

        if (matchedTools.isEmpty()) {
            return ToolResult.success("No tools found matching: " + query +
                    "\n\nTry different keywords or use /help to see all available commands.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(matchedTools.size()).append(" tool(s) matching '").append(query).append("':\n\n");

        for (Tool tool : matchedTools) {
            sb.append("**").append(tool.getName()).append("**");
            if (tool.shouldDefer()) {
                sb.append(" (deferred → now activated)");
            }
            sb.append("\n");
            sb.append("  Group: ").append(tool.getGroup()).append("\n");
            sb.append("  ").append(tool.getDescription()).append("\n");

            // 对于 deferred 工具，返回完整 schema 以便模型可以调用
            if (tool.shouldDefer()) {
                try {
                    String schemaJson = objectMapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(tool.toToolDefinition());
                    sb.append("  Schema:\n").append(schemaJson).append("\n");
                } catch (Exception e) {
                    log.warn("Failed to serialize schema for tool {}: {}", tool.getName(), e.getMessage());
                    sb.append("  Schema: (serialization failed)\n");
                }
            }
            sb.append("\n");
        }

        return ToolResult.success(sb.toString());
    }

    private boolean matchesQuery(Tool tool, String queryLower) {
        // 搜索工具名称
        if (tool.getName().toLowerCase().contains(queryLower)) {
            return true;
        }
        // 搜索工具描述
        if (tool.getDescription() != null &&
                tool.getDescription().toLowerCase().contains(queryLower)) {
            return true;
        }
        // 搜索工具分组
        if (tool.getGroup() != null &&
                tool.getGroup().toLowerCase().contains(queryLower)) {
            return true;
        }
        // 搜索别名
        return tool.getAliases().stream()
                .anyMatch(alias -> alias.toLowerCase().contains(queryLower));
    }

    record ToolSearchResult(String name, String description, boolean deferred, String group) {}
}
