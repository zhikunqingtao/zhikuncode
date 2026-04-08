package com.aicodeassistant.tool.impl;

import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

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

        String queryLower = query.toLowerCase();
        log.debug("ToolSearch query: '{}'", query);

        // 搜索所有工具（包括延迟加载和已加载的）
        List<ToolSearchResult> matches = toolRegistry.getAllTools().stream()
                .filter(tool -> matchesQuery(tool, queryLower))
                .map(tool -> new ToolSearchResult(
                        tool.getName(),
                        tool.getDescription(),
                        tool.shouldDefer(),
                        tool.getGroup()
                ))
                .toList();

        if (matches.isEmpty()) {
            return ToolResult.success("No tools found matching: " + query +
                    "\n\nTry different keywords or use /help to see all available commands.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(matches.size()).append(" tool(s) matching '").append(query).append("':\n\n");

        for (ToolSearchResult match : matches) {
            sb.append("**").append(match.name()).append("**");
            if (match.deferred()) {
                sb.append(" (deferred)");
            }
            sb.append("\n");
            sb.append("  Group: ").append(match.group()).append("\n");
            sb.append("  ").append(match.description()).append("\n\n");
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
