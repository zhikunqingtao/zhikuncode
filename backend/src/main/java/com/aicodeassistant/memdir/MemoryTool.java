package com.aicodeassistant.memdir;

import com.aicodeassistant.model.PermissionBehavior;
import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MemoryTool — LLM 读写自动记忆的工具。
 * <p>
 * 对应源码中的 memory 工具。
 * 允许 LLM 在交互过程中主动记录和检索记忆。
 * <p>
 * 操作:
 * - read: 读取所有持久化记忆
 * - write: 写入新记忆条目（追加模式）
 * - delete: 删除匹配的记忆条目
 *
 * @see <a href="SPEC §4.11">Memdir 自动记忆系统</a>
 */
@Component
public class MemoryTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(MemoryTool.class);

    private final MemdirService memdirService;

    public MemoryTool(MemdirService memdirService) {
        this.memdirService = memdirService;
    }

    @Override
    public String getName() {
        return "Memory";
    }

    @Override
    public String getDescription() {
        return "Read or write persistent memories that carry across sessions. " +
                "Use this to remember user preferences, project conventions, " +
                "and other important context.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of(
                                "type", "string",
                                "enum", java.util.List.of("read", "write", "delete"),
                                "description", "The action to perform: read, write, or delete"
                        ),
                        "content", Map.of(
                                "type", "string",
                                "description", "Content to write or search pattern to delete"
                        )
                ),
                "required", java.util.List.of("action")
        );
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String action = input.getString("action");
        log.debug("MemoryTool action: {}", action);

        return switch (action) {
            case "read" -> {
                String memories = memdirService.readMemories();
                yield memories.isEmpty()
                        ? ToolResult.success("No memories stored yet.")
                        : ToolResult.success(memories);
            }
            case "write" -> {
                String content = input.getString("content");
                if (content == null || content.isBlank()) {
                    yield ToolResult.error("Content is required for write action.");
                }
                memdirService.writeMemory(content, MemdirService.MemorySource.TOOL);
                yield ToolResult.success("Memory saved.");
            }
            case "delete" -> {
                String pattern = input.getString("content");
                if (pattern == null || pattern.isBlank()) {
                    yield ToolResult.error("Content (search pattern) is required for delete action.");
                }
                boolean deleted = memdirService.deleteMemory(pattern);
                yield deleted
                        ? ToolResult.success("Memory deleted.")
                        : ToolResult.error("No matching memory found.");
            }
            default -> ToolResult.error("Unknown action: " + action);
        };
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        return "read".equals(input.getString("action", "read"));
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return "read".equals(input.getString("action", "read"));
    }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.NONE;
    }

    @Override
    public String getGroup() {
        return "general";
    }
}
