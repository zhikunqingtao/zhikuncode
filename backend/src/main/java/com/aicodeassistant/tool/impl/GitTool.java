package com.aicodeassistant.tool.impl;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.service.PythonCapabilityAwareClient;
import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * GitTool — Git 增强工具（Java→Python→gitpython 架构）。
 * <p>
 * 通过 Python gitpython 提供语义化 diff、结构化日志、逐行 blame 等能力。
 * 双重门控：feature flag {@code GIT_ENHANCED_TOOL} + Python {@code GIT_ENHANCED} 能力域。
 * <p>
 * 桥接模式参照 {@link WebBrowserTool} 实现。
 *
 * @see WebBrowserTool
 * @see PythonCapabilityAwareClient
 */
@Component
public class GitTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GitTool.class);

    private final PythonCapabilityAwareClient pythonClient;
    private final FeatureFlagService featureFlags;
    private final ObjectMapper objectMapper;
    private static final String CAPABILITY = "GIT_ENHANCED";
    private static final Set<String> ALLOWED_ACTIONS = Set.of("diff", "log", "blame");

    public GitTool(PythonCapabilityAwareClient pythonClient,
                   FeatureFlagService featureFlags,
                   ObjectMapper objectMapper) {
        this.pythonClient = pythonClient;
        this.featureFlags = featureFlags;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() { return "Git"; }

    @Override
    public String getDescription() {
        return "Git enhanced analysis tool for semantic diff, structured commit log, "
             + "and line-by-line blame. Provides richer output than raw git commands.";
    }

    @Override
    public String prompt() {
        return """
                Git enhanced analysis tool powered by gitpython.
                Use this tool when you need structured Git analysis beyond raw git commands:
                - "diff": Semantic diff analysis with file-level change statistics
                - "log": Structured commit log with per-commit file lists
                - "blame": Line-by-line attribution for a specific file
                
                The repo_path parameter should be an absolute path to the git repository.
                For diff/log, you can specify git refs (branches, tags, commit SHAs).
                
                Prefer BashTool for simple git operations (status, add, commit, push).
                Use this tool only for analysis operations that benefit from structured output.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "required", List.of("action", "repo_path"),
                "properties", Map.ofEntries(
                        Map.entry("action", Map.of(
                                "type", "string",
                                "enum", List.copyOf(ALLOWED_ACTIONS),
                                "description", "Git action: diff, log, or blame")),
                        Map.entry("repo_path", Map.of(
                                "type", "string",
                                "description", "Absolute path to the git repository")),
                        Map.entry("ref1", Map.of(
                                "type", "string",
                                "description", "Start reference for diff (default: HEAD~1)")),
                        Map.entry("ref2", Map.of(
                                "type", "string",
                                "description", "End reference for diff (default: HEAD)")),
                        Map.entry("file_path", Map.of(
                                "type", "string",
                                "description", "File path for blame (relative to repo root)")),
                        Map.entry("ref", Map.of(
                                "type", "string",
                                "description", "Git ref for blame (branch/tag/SHA, default: HEAD)")),
                        Map.entry("max_count", Map.of(
                                "type", "integer",
                                "description", "Max entries for log (default: 20, max: 100)")),
                        Map.entry("branch", Map.of(
                                "type", "string",
                                "description", "Branch name for log (default: current HEAD)"))
                )
        );
    }

    @Override
    public String getGroup() { return "read"; }

    @Override
    public boolean isEnabled() {
        return featureFlags.isEnabled("GIT_ENHANCED_TOOL")
                && pythonClient.isCapabilityAvailable(CAPABILITY);
    }

    @Override
    public boolean isReadOnly(ToolInput input) { return true; }

    @Override
    public boolean isConcurrencySafe(ToolInput input) { return true; }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.NONE;  // 只读工具无需权限确认
    }

    @Override
    public ValidationResult validateInput(ToolInput input, ToolUseContext context) {
        String action = input.getString("action", null);
        if (action == null || !ALLOWED_ACTIONS.contains(action)) {
            return ValidationResult.invalid("INVALID_ACTION",
                    "Action must be one of: " + ALLOWED_ACTIONS);
        }
        String repoPath = input.getString("repo_path", null);
        if (repoPath == null || repoPath.isBlank()) {
            return ValidationResult.invalid("MISSING_REPO_PATH",
                    "repo_path is required");
        }
        if ("blame".equals(action)) {
            String filePath = input.getString("file_path", null);
            if (filePath == null || filePath.isBlank()) {
                return ValidationResult.invalid("MISSING_FILE_PATH",
                        "file_path is required for blame action");
            }
        }
        return ValidationResult.ok();
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String action = input.getString("action");
        Map<String, Object> body = new HashMap<>(input.getRawData());

        Optional<JsonNode> resp = pythonClient.callIfAvailable(
                CAPABILITY, "/api/git/" + action, body, JsonNode.class);

        if (resp.isEmpty()) {
            return ToolResult.error(
                    "Git enhanced analysis unavailable. "
                    + "Ensure gitpython is installed and GIT_ENHANCED capability is active.");
        }
        try {
            JsonNode node = resp.get();
            // 统一错误返回协议：区分能力缺失(empty)与业务错误(success==false)
            JsonNode successField = node.get("success");
            if (successField != null && !successField.asBoolean(true)) {
                String errorCode = node.has("error_code") ? node.get("error_code").asText() : "UNKNOWN";
                String errorMsg  = node.has("error_message") ? node.get("error_message").asText() : "Unknown error";
                return ToolResult.error(errorCode + ": " + errorMsg);
            }
            // 成功时：将 data 字段序列化为输出（若 data 为 null 则输出整个节点）
            JsonNode data = node.get("data");
            return ToolResult.success(objectMapper.writeValueAsString(data != null ? data : node));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return ToolResult.error("Failed to serialize git response: " + e.getMessage());
        }
    }
}
