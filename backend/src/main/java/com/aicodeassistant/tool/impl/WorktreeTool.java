package com.aicodeassistant.tool.impl;

import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.agent.WorktreeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
    public long getMaxExecutionTimeMs() {
        return 300_000L; // 5 minutes for worktree creation/checkout
    }

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
                - add: Create a worktree from the authorized project’s committed HEAD; parent uncommitted changes are not copied
                - list: List worktrees of the current authorized project
                - remove: Safely clean an inactive, registered worktree with no undelivered changes; never force-discard work. Unknown or active worktrees are rejected.
                
                Examples:
                - {"subcommand": "list"}
                - {"subcommand": "add", "agent_id": "coder-1"}
                - {"subcommand": "remove", "path": "/tmp/.zhikun-agent-coder-1"}
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
            case "list" -> handleList(context);
            case "add" -> handleAdd(input, context);
            case "remove" -> handleRemove(input, context);
            default -> ToolResult.validationError("WORKTREE_SUBCOMMAND_INVALID", "Unknown subcommand: " + subcommand);
        };
    }

    // ==================== 子命令处理 ====================

    private ToolResult handleList(ToolUseContext context) {
        try {
            return ToolResult.success(worktreeManager.listWorktrees(context));
        } catch (RuntimeException failure) {
            return ToolResult.failed(ToolResult.ToolFailureType.PROCESS, "WORKTREE_LIST_FAILED",
                    "Worktrees could not be listed: " + failure.getMessage(), ToolResult.Retryability.NEVER,
                    ToolResult.EffectState.NONE, null, Map.of());
        }
    }

    private ToolResult handleAdd(ToolInput input, ToolUseContext context) {
        try {
            var tree = worktreeManager.createWorktree(input.getString("agent_id"), context, true);
            return ToolResult.successWithEffect("Worktree created.\nPath: " + tree.path()
                    + "\nExecution directory: " + tree.workingDirectory()
                    + "\nBranch: " + tree.branch() + "\n" + tree.warning(), ToolResult.EffectState.APPLIED);
        } catch (RuntimeException failure) {
            // Creation can leave a real branch/directory; the manager includes recovery details.
            return ToolResult.failed(ToolResult.ToolFailureType.PROCESS, "WORKTREE_CREATE_FAILED",
                    failure.getMessage(), ToolResult.Retryability.NEVER,
                    ToolResult.EffectState.UNKNOWN, null, Map.of());
        }
    }

    private ToolResult handleRemove(ToolInput input, ToolUseContext context) {
        try {
            var outcome = worktreeManager.removeWorktree(Path.of(input.getString("path")), context);
            if (!outcome.success() || (outcome.cleanupWarning() != null && !outcome.cleanupWarning().isBlank())) {
                return ToolResult.failed(ToolResult.ToolFailureType.PROCESS, "WORKTREE_REMOVE_INCOMPLETE",
                        outcome.summary(), ToolResult.Retryability.NEVER,
                        ToolResult.EffectState.UNKNOWN, null, Map.of());
            }
            return ToolResult.successWithEffect(outcome.summary(), ToolResult.EffectState.APPLIED);
        } catch (RuntimeException failure) {
            return ToolResult.failed(ToolResult.ToolFailureType.PROCESS, "WORKTREE_REMOVE_FAILED",
                    "Worktree cleanup was not confirmed: " + failure.getMessage(), ToolResult.Retryability.NEVER,
                    ToolResult.EffectState.UNKNOWN, null, Map.of());
        }
    }
}
