package com.aicodeassistant.tool.impl;

import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.agent.WorktreeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WorktreeTool — Git Worktree 管理工具。
 * <p>
 * 封装 {@link WorktreeManager} 提供 Git Worktree 的 add / list / remove 子命令，
 * 支持代理在隔离分支中并行开发。
 * <p>
 * list 子命令直接执行 {@code git worktree list} 获取完整 worktree 列表；
 * add/remove 委托给 WorktreeManager 管理生命周期。
 */
@Component
public class WorktreeTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WorktreeTool.class);
    private static final Set<String> SUBCOMMANDS = Set.of("add", "list", "remove");

    private final WorktreeManager worktreeManager;

    public WorktreeTool(WorktreeManager worktreeManager) {
        this.worktreeManager = worktreeManager;
    }

    @Override
    public String getName() { return "Worktree"; }

    @Override
    public String getDescription() {
        return "Manage Git worktrees for parallel branch development. "
             + "Supports add, list, and remove operations.";
    }

    @Override
    public String prompt() {
        return """
                Use this tool to manage Git worktrees, enabling parallel work on multiple branches.
                
                Subcommands:
                - add: Create a new worktree for an agent (auto-generates branch name and path)
                - list: List all existing worktrees
                - remove: Remove a worktree by path
                
                Examples:
                - {"subcommand": "list"}
                - {"subcommand": "add", "agent_id": "coder-1"}
                - {"subcommand": "remove", "path": "/tmp/.claude-agent-coder-1"}
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "required", List.of("subcommand"),
                "properties", Map.of(
                        "subcommand", Map.of(
                                "type", "string",
                                "enum", List.copyOf(SUBCOMMANDS),
                                "description", "Worktree operation to perform: add, list, or remove"),
                        "agent_id", Map.of(
                                "type", "string",
                                "description", "Agent identifier for worktree creation (required for 'add')"),
                        "path", Map.of(
                                "type", "string",
                                "description", "Worktree directory path (required for 'remove')")
                )
        );
    }

    @Override
    public String getGroup() { return "git"; }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        // add/remove 修改文件系统，统一使用 ALWAYS_ASK
        return PermissionRequirement.ALWAYS_ASK;
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        String sub = input.getString("subcommand", "");
        return "list".equals(sub);
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return isReadOnly(input);
    }

    @Override
    public ValidationResult validateInput(ToolInput input, ToolUseContext context) {
        String subcommand = input.getString("subcommand", null);
        if (subcommand == null || !SUBCOMMANDS.contains(subcommand)) {
            return ValidationResult.invalid("INVALID_SUBCOMMAND",
                    "subcommand must be one of: " + SUBCOMMANDS);
        }
        if ("add".equals(subcommand)) {
            String agentId = input.getString("agent_id", null);
            if (agentId == null || agentId.isBlank()) {
                return ValidationResult.invalid("MISSING_AGENT_ID",
                        "agent_id is required for 'add' subcommand");
            }
        }
        if ("remove".equals(subcommand)) {
            String path = input.getString("path", null);
            if (path == null || path.isBlank()) {
                return ValidationResult.invalid("MISSING_PATH",
                        "path is required for 'remove' subcommand");
            }
        }
        return ValidationResult.ok();
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String subcommand = input.getString("subcommand");
        log.debug("WorktreeTool executing subcommand: {}", subcommand);

        return switch (subcommand) {
            case "list" -> handleList();
            case "add" -> handleAdd(input);
            case "remove" -> handleRemove(input);
            default -> ToolResult.error("Unknown subcommand: " + subcommand);
        };
    }

    // ==================== 子命令处理 ====================

    /**
     * 列出所有 Git worktree — 直接执行 git worktree list。
     */
    private ToolResult handleList() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "worktree", "list");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            int exitCode = p.waitFor();

            if (exitCode != 0) {
                log.warn("git worktree list failed (exit {}): {}", exitCode, output);
                return ToolResult.error("Failed to list worktrees: " + output.trim());
            }

            int activeCount = worktreeManager.getActiveCount();
            String result = "Git Worktrees:\n" + output.trim()
                    + "\n\nManaged active worktrees: " + activeCount;
            log.debug("Listed worktrees, managed count: {}", activeCount);
            return ToolResult.success(result);
        } catch (IOException | InterruptedException e) {
            log.error("Failed to execute git worktree list", e);
            return ToolResult.error("Failed to list worktrees: " + e.getMessage());
        }
    }

    /**
     * 创建新的 worktree — 委托给 WorktreeManager。
     * 自动生成分支名和路径。
     */
    private ToolResult handleAdd(ToolInput input) {
        String agentId = input.getString("agent_id");
        try {
            Path worktreePath = worktreeManager.createWorktree(agentId);
            String result = "Worktree created successfully.\n"
                    + "Path: " + worktreePath + "\n"
                    + "Agent: " + agentId;
            log.info("Created worktree for agent '{}' at {}", agentId, worktreePath);
            return ToolResult.success(result);
        } catch (RuntimeException e) {
            log.error("Failed to create worktree for agent '{}'", agentId, e);
            return ToolResult.error("Failed to create worktree: " + e.getMessage());
        }
    }

    /**
     * 移除 worktree — 委托给 WorktreeManager。
     */
    private ToolResult handleRemove(ToolInput input) {
        String pathStr = input.getString("path");
        Path worktreePath = Path.of(pathStr);
        try {
            worktreeManager.removeWorktree(worktreePath);
            log.info("Removed worktree at {}", worktreePath);
            return ToolResult.success("Worktree removed successfully: " + worktreePath);
        } catch (RuntimeException e) {
            log.error("Failed to remove worktree at {}", worktreePath, e);
            return ToolResult.error("Failed to remove worktree: " + e.getMessage());
        }
    }
}
