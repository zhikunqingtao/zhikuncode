package com.aicodeassistant.tool.impl;

import com.aicodeassistant.history.FileHistoryService;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.security.PathSecurityService.PathCheckResult;
import com.aicodeassistant.service.FileStateCache;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.*;
import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FileEditTool — 对文件进行部分修改（字符串替换）。
 * <p>
 * 使用 java-diff-utils 生成 unified diff。
 * 支持 3 策略 fuzzy matching: 精确匹配 → 引号归一化 → 空白归一化。
 *
 * @see <a href="SPEC §3.2.3">FileEditTool 规范</a>
 */
@Component
public class FileEditTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileEditTool.class);
    private static final long MAX_EDIT_FILE_SIZE = 1024L * 1024 * 1024; // 1GB

    private final FileHistoryService fileHistoryService;
    private final PathSecurityService pathSecurity;
    private final SessionManager sessionManager;

    public FileEditTool(FileHistoryService fileHistoryService, PathSecurityService pathSecurity,
                        SessionManager sessionManager) {
        this.fileHistoryService = fileHistoryService;
        this.pathSecurity = pathSecurity;
        this.sessionManager = sessionManager;
    }

    @Override
    public String getName() {
        return "Edit";
    }

    @Override
    public String getDescription() {
        return "Make a targeted edit to a file by replacing a specific string with a new string. "
                + "Requires the file to have been read first.";
    }

    @Override
    public String prompt() {
        return """
                Performs exact string replacements in files.
                
                Usage:
                - You must use your `Read` tool at least once in the conversation before editing. \
                This tool will error if you attempt an edit without reading the file.
                - When editing text from Read tool output, ensure you preserve the exact indentation \
                (tabs/spaces) as it appears AFTER the line number prefix. The line number prefix \
                format is: spaces + line number + arrow. Everything after that is the actual file \
                content to match. Never include any part of the line number prefix in the old_string \
                or new_string.
                - ALWAYS prefer editing existing files in the codebase. NEVER write new files \
                unless explicitly required.
                - Only use emojis if the user explicitly requests it. Avoid adding emojis to \
                files unless asked.
                - The edit will FAIL if `old_string` is not unique in the file. Either provide a \
                larger string with more surrounding context to make it unique or use `replace_all` \
                to change every instance of `old_string`.
                - Use `replace_all` for replacing and renaming strings across the file. This \
                parameter is useful if you want to rename a variable for instance.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string", "description", "Absolute path to the file"),
                        "old_string", Map.of("type", "string", "description", "The string to replace"),
                        "new_string", Map.of("type", "string", "description", "The replacement string"),
                        "replace_all", Map.of("type", "boolean", "description", "Replace all occurrences (default false)")
                ),
                "required", List.of("file_path", "old_string", "new_string")
        );
    }

    @Override
    public String getGroup() {
        return "edit";
    }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.ALWAYS_ASK;
    }

    @Override
    public String getPath(ToolInput input) {
        return input.has("file_path") ? input.getString("file_path") : null;
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        Path resolved = pathSecurity.resolvePath(input.getString("file_path"), context.workingDirectory());
        String filePath = resolved.toString();
        String oldString = input.getString("old_string");
        String newString = input.getString("new_string");
        boolean replaceAll = input.getBoolean("replace_all", false);

        // 0. PathSecurityService 统一写入安全检查
        PathCheckResult checkResult = pathSecurity.checkWritePermission(
                input.getString("file_path"), context.workingDirectory());
        if (!checkResult.isAllowed()) {
            return ToolResult.error(checkResult.message());
        }
        if (checkResult.needsConfirmation()) {
            log.warn("Writing to sensitive path (allowed with warning): {}", filePath);
        }

        try {
            // 1. 基础验证
            if (oldString.equals(newString)) {
                return ToolResult.error("old_string and new_string are identical. No changes to make.");
            }

            // ★ FileStateCache 前置检查 — Read-before-Edit + 过期检测 (§11.5.9)
            FileStateCache cache = sessionManager.getFileStateCache(context.sessionId());
            if (!oldString.isEmpty() && !cache.hasBeenRead(filePath)) {
                return ToolResult.error("请先使用 Read 工具读取文件内容");
            }
            if (!oldString.isEmpty() && cache.isStale(filePath)) {
                return ToolResult.error("文件已被外部修改，请重新 Read");
            }

            Path path = resolved;

            // 2. 文件不存在 + old_string 为空 = 创建新文件
            if (!Files.exists(path)) {
                if (oldString.isEmpty()) {
                    if (path.getParent() != null) {
                        Files.createDirectories(path.getParent());
                    }
                    Files.writeString(path, newString, StandardCharsets.UTF_8);
                    return ToolResult.success("Created: " + filePath)
                            .withMetadata("type", "create")
                            .withMetadata("filePath", filePath);
                }
                return ToolResult.error("File does not exist: " + filePath);
            }

            // 3. 文件大小检查
            if (Files.size(path) > MAX_EDIT_FILE_SIZE) {
                return ToolResult.error("File too large (>1GB). Cannot edit.");
            }

            String fileContent = Files.readString(path, StandardCharsets.UTF_8);

            // ── 新增: 编辑前保存快照 ──
            fileHistoryService.trackEdit(filePath, context.sessionId(), context.toolUseId(), "edit");

            // 4. 查找 old_string (3 策略 fuzzy matching)
            String actualOldString = findActualString(fileContent, oldString);
            if (actualOldString == null) {
                return ToolResult.error(
                        "No match found for the specified old_string in " + filePath + ".\n"
                                + "Attempted strategies: exact → quote-normalization\n"
                                + "Suggestions:\n"
                                + "  1. Use Read tool to re-read the file and verify the content\n"
                                + "  2. Ensure old_string matches exactly (including whitespace/indentation)\n"
                                + "  3. Try a smaller, more unique substring");
            }

            // 5. 验证唯一性
            int matchCount = countMatches(fileContent, actualOldString);
            if (matchCount > 1 && !replaceAll) {
                return ToolResult.error("Found " + matchCount + " matches. "
                        + "Set replace_all=true or provide more context to uniquely identify.");
            }

            // 6. 执行替换
            String newContent = replaceAll
                    ? fileContent.replace(actualOldString, newString)
                    : fileContent.replaceFirst(
                    Pattern.quote(actualOldString),
                    Matcher.quoteReplacement(newString));

            // 7. 写入文件
            Files.writeString(path, newContent, StandardCharsets.UTF_8);

            // 8. 生成 unified diff
            List<String> originalLines = Arrays.asList(fileContent.split("\n", -1));
            List<String> newLines = Arrays.asList(newContent.split("\n", -1));
            Patch<String> patch = DiffUtils.diff(originalLines, newLines);
            List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(
                    filePath, filePath, originalLines, patch, 3);

            String diffText = String.join("\n", unifiedDiff);

            return ToolResult.success("Edited: " + filePath)
                    .withMetadata("type", "update")
                    .withMetadata("filePath", filePath)
                    .withMetadata("diff", diffText)
                    .withMetadata("matchCount", replaceAll ? matchCount : 1);

        } catch (IOException e) {
            log.error("Failed to edit file: {}", filePath, e);
            return ToolResult.error("Failed to edit file: " + e.getMessage());
        }
    }

    /**
     * 3 策略 fuzzy matching:
     * 1. 精确匹配
     * 2. 引号归一化 (smart quotes → ASCII quotes)
     */
    private String findActualString(String fileContent, String searchString) {
        // Strategy 1: 精确匹配
        if (fileContent.contains(searchString)) {
            return searchString;
        }

        // Strategy 2: 引号归一化
        String normalized = normalizeQuotes(searchString);
        if (!normalized.equals(searchString) && fileContent.contains(normalized)) {
            return normalized;
        }

        // 也尝试对文件内容做归一化后匹配
        String normalizedFile = normalizeQuotes(fileContent);
        if (normalizedFile.contains(normalizeQuotes(searchString))) {
            // 找到归一化后的位置，提取原始内容
            int idx = normalizedFile.indexOf(normalizeQuotes(searchString));
            if (idx >= 0) {
                String originalSubstring = fileContent.substring(idx, idx + searchString.length());
                if (fileContent.contains(originalSubstring)) {
                    return originalSubstring;
                }
            }
        }

        return null;
    }

    /** 引号归一化 — 弯引号 → ASCII 引号 */
    private String normalizeQuotes(String text) {
        return text
                .replace('\u201C', '"')  // 左弯双引号
                .replace('\u201D', '"')  // 右弯双引号
                .replace('\u2018', '\'') // 左弯单引号
                .replace('\u2019', '\'');// 右弯单引号
    }

    /** 统计匹配次数 */
    private int countMatches(String text, String search) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(search, idx)) != -1) {
            count++;
            idx += search.length();
        }
        return count;
    }
}