package com.aicodeassistant.tool.impl;

import com.aicodeassistant.engine.KeyFileTracker;
import com.aicodeassistant.history.FileHistoryService;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.security.PathSecurityService.PathCheckResult;
import com.aicodeassistant.service.FileStateCache;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.impl.AtomicFileWriter.WriteResult;
import com.aicodeassistant.tool.impl.FileVersionTracker.ConflictCheckResult;
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
import java.util.stream.Collectors;

/**
 * FileEditTool — 对文件进行部分修改（字符串替换）。
 * <p>
 * 使用 java-diff-utils 生成 unified diff。
 * 支持 5 策略 fuzzy matching: 精确匹配 → 引号归一化 → 行尾空白归一化 → 换行符归一化 → Tab/空格归一化。
 *
 */
@Component
public class FileEditTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileEditTool.class);
    private static final long MAX_EDIT_FILE_SIZE = 1024L * 1024 * 1024; // 1GB

    private final FileHistoryService fileHistoryService;
    private final PathSecurityService pathSecurity;
    private final SessionManager sessionManager;
    private final KeyFileTracker keyFileTracker;
    private final FileVersionTracker fileVersionTracker;
    private final AtomicFileWriter atomicFileWriter;

    public FileEditTool(FileHistoryService fileHistoryService, PathSecurityService pathSecurity,
                        SessionManager sessionManager, KeyFileTracker keyFileTracker,
                        FileVersionTracker fileVersionTracker, AtomicFileWriter atomicFileWriter) {
        this.fileHistoryService = fileHistoryService;
        this.pathSecurity = pathSecurity;
        this.sessionManager = sessionManager;
        this.keyFileTracker = keyFileTracker;
        this.fileVersionTracker = fileVersionTracker;
        this.atomicFileWriter = atomicFileWriter;
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
            return ToolResult.validationError("FILE_WRITE_PATH_DENIED", checkResult.message());
        }
        if (checkResult.needsConfirmation()) {
            log.warn("Writing to sensitive path (allowed with warning): {}", filePath);
        }

        try {
            // 1. 基础验证
            if (oldString.equals(newString)) {
                return ToolResult.validationError("FILE_EDIT_NO_CHANGE", "old_string and new_string are identical. No changes to make.");
            }

            // ★ FileStateCache 前置检查 — Read-before-Edit + 过期检测 (§11.5.9)
            FileStateCache cache = sessionManager.getFileStateCache(context.sessionId());
            if (!oldString.isEmpty() && !cache.hasBeenRead(filePath)) {
                return ToolResult.validationError("FILE_READ_REQUIRED", "请先使用 Read 工具读取文件内容");
            }
            if (!oldString.isEmpty() && cache.isStale(filePath)) {
                return ToolResult.validationError("FILE_READ_STATE_STALE", "文件已被外部修改，请重新 Read");
            }

            Path path = resolved;

            // 2. 文件不存在 + old_string 为空 = 创建新文件
            if (!Files.exists(path)) {
                if (oldString.isEmpty()) {
                    WriteResult createResult = atomicFileWriter.write(path,
                            newString.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            context.sessionId(), AtomicFileWriter.ExpectedOldState.absent(),
                            context.workingDirectory());
                    if (!createResult.success()) {
                        return writeFailure(createResult);
                    }
                    String postCommitError = "";
                    try { sessionManager.getFileStateCache(context.sessionId()).markModified(filePath); }
                    catch (RuntimeException failure) {
                        postCommitError = "POST_COMMIT_CACHE_UPDATE_FAILED";
                        log.warn("File create applied but cache update failed for {}: {}", filePath, failure.getMessage());
                    }
                    return ToolResult.successWithEffect("Created: " + filePath, ToolResult.EffectState.APPLIED)
                            .withMetadata("type", "create")
                            .withMetadata("filePath", filePath)
                            .withMetadata("sealedHash", createResult.newHash())
                            .withMetadata("postCommitErrorCode", postCommitError);
                }
                return ToolResult.validationError("FILE_NOT_FOUND", "File does not exist: " + filePath);
            }

            // 3. 文件大小检查
            if (Files.size(path) > MAX_EDIT_FILE_SIZE) {
                return ToolResult.validationError("FILE_EDIT_SIZE_LIMIT", "File too large (>1GB). Cannot edit.");
            }

            String fileContent = Files.readString(path, StandardCharsets.UTF_8);

            // ★ FileVersionTracker — 记录读取时的文件 hash
            fileVersionTracker.recordRead(filePath);
            String expectedHash = fileVersionTracker.computeHash(fileContent);

            // 4. 查找 old_string (5 策略 fuzzy matching)
            String actualOldString = findActualString(fileContent, oldString);
            if (actualOldString == null) {
                return ToolResult.validationError("FILE_EDIT_MATCH_NOT_FOUND",
                        "No match found for the specified old_string in " + filePath + ".\n"
                                + "Attempted strategies: exact → quote-normalization → trailing-whitespace → newline-normalization → tab/space-normalization\n"
                                + "Suggestions:\n"
                                + "  1. Use Read tool to re-read the file and verify the content\n"
                                + "  2. Ensure old_string matches exactly (including whitespace/indentation)\n"
                                + "  3. Try a smaller, more unique substring");
            }

            // 5. 验证唯一性
            int matchCount = countMatches(fileContent, actualOldString);
            if (matchCount > 1 && !replaceAll) {
                return ToolResult.validationError("FILE_EDIT_MATCH_AMBIGUOUS", "Found " + matchCount + " matches. "
                        + "Set replace_all=true or provide more context to uniquely identify.");
            }

            // 6. 执行替换
            String newContent = replaceAll
                    ? fileContent.replace(actualOldString, newString)
                    : fileContent.replaceFirst(
                    Pattern.quote(actualOldString),
                    Matcher.quoteReplacement(newString));

            // 7. SHA-256 冲突检测 (Conflict-Abort 策略)
            ConflictCheckResult conflictResult = fileVersionTracker.checkBeforeWrite(filePath, expectedHash);
            if (conflictResult.hasConflict()) {
                log.warn("Conflict-Abort: file {} modified since last read (expected={}, current={})",
                        filePath, conflictResult.expectedHash(), conflictResult.currentHash());
                return ToolResult.validationError("FILE_CONFLICT",
                        "文件自上次读取后已被修改，请重新读取文件后再编辑。\n"
                        + "Expected hash: " + conflictResult.expectedHash() + "\n"
                        + "Current hash: " + conflictResult.currentHash()
                        + (conflictResult.lastEditor() != null
                                ? "\nLast editor: " + conflictResult.lastEditor() : ""));
            }

            // 8. 原子写入（替代 Files.writeString）
            String expectedOldHash = fileVersionTracker.computeHash(fileContent);
            WriteResult writeResult = atomicFileWriter.write(path,
                    newContent.getBytes(java.nio.charset.StandardCharsets.UTF_8), context.sessionId(),
                    AtomicFileWriter.ExpectedOldState.sha256(expectedOldHash),
                    context.workingDirectory());
            if (!writeResult.success()) {
                return writeFailure(writeResult);
            }
            FileHistoryService.HistoryRecordResult history;
            String diffText = "";
            String postCommitError = "";
            try {
                history = fileHistoryService.trackAppliedEdit(filePath, fileContent, context.sessionId(),
                        context.toolUseId(), "edit");
                if (history == null) history = new FileHistoryService.HistoryRecordResult(false, "HISTORY_RESULT_UNAVAILABLE");
                sessionManager.getFileStateCache(context.sessionId()).markModified(filePath);
                List<String> originalLines = Arrays.asList(fileContent.split("\n", -1));
                List<String> newLines = Arrays.asList(newContent.split("\n", -1));
                Patch<String> patch = DiffUtils.diff(originalLines, newLines);
                diffText = String.join("\n", UnifiedDiffUtils.generateUnifiedDiff(
                        filePath, filePath, originalLines, patch, 3));
            } catch (RuntimeException postCommitFailure) {
                log.warn("File edit applied but post-commit bookkeeping failed for {}: {}",
                        filePath, postCommitFailure.getMessage());
                history = new FileHistoryService.HistoryRecordResult(false, "POST_COMMIT_BOOKKEEPING_FAILED");
                postCommitError = "POST_COMMIT_BOOKKEEPING_FAILED";
            }

            return ToolResult.successWithEffect("Edited: " + filePath, ToolResult.EffectState.APPLIED)
                    .withMetadata("type", "update")
                    .withMetadata("filePath", filePath)
                    .withMetadata("diff", diffText)
                    .withMetadata("sealedHash", writeResult.newHash())
                    .withMetadata("historyRecorded", history.recorded())
                    .withMetadata("historyErrorCode", history.errorCode() == null ? "" : history.errorCode())
                    .withMetadata("postCommitErrorCode", postCommitError)
                    .withMetadata("matchCount", replaceAll ? matchCount : 1);

        } catch (IOException e) {
            log.error("Failed to edit file: {}", filePath, e);
            return ToolResult.internalError("FILE_EDIT_IO_FAILED",
                    "Failed to edit file: " + e.getMessage(), ToolResult.EffectState.NOT_STARTED);
        } finally {
            // ★ KeyFileTracker 埋点 — 记录文件编辑 ★
            try { keyFileTracker.trackFileReference(context.sessionId(), filePath, context.toolUseId()); }
            catch (RuntimeException trackingFailure) {
                log.warn("File reference tracking failed for {}: {}", filePath, trackingFailure.getMessage());
            }
        }
    }

    private static ToolResult writeFailure(WriteResult result) {
        ToolResult.EffectState effect = switch (result.effect()) {
            case NOT_STARTED -> ToolResult.EffectState.NOT_STARTED;
            case APPLIED -> ToolResult.EffectState.APPLIED;
            case UNKNOWN -> ToolResult.EffectState.UNKNOWN;
        };
        return ToolResult.failed(ToolResult.ToolFailureType.INTERNAL, "ATOMIC_WRITE_FAILED",
                "原子写入失败: " + result.error(), ToolResult.Retryability.NEVER,
                effect, null, Map.of("sealedHash", result.newHash() == null ? "" : result.newHash()));
    }

    /**
     * 5 策略 fuzzy matching:
     * 1. 精确匹配
     * 2. 引号归一化 (smart quotes -> ASCII quotes)
     * 3. 行尾空白归一化
     * 4. 换行符归一化 (\r\n -> \n)
     * 5. Tab/空格归一化 (tab <-> 4 spaces)
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

        // Strategy 3: 行尾空白归一化
        String trimmedSearch = trimTrailingWhitespace(searchString);
        if (!trimmedSearch.equals(searchString)) {
            String[] fileLines = fileContent.split("\n", -1);
            String[] searchLines = trimmedSearch.split("\n", -1);
            int searchLineCount = searchLines.length;

            for (int i = 0; i <= fileLines.length - searchLineCount; i++) {
                boolean match = true;
                for (int j = 0; j < searchLineCount; j++) {
                    if (!fileLines[i + j].replaceAll("\\s+$", "").equals(searchLines[j])) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    StringBuilder sb = new StringBuilder();
                    for (int j = 0; j < searchLineCount; j++) {
                        if (j > 0) sb.append("\n");
                        sb.append(fileLines[i + j]);
                    }
                    String candidate = sb.toString();
                    if (fileContent.contains(candidate)) {
                        return candidate;
                    }
                }
            }
        }

        // Strategy 4: 换行符归一化 (\r\n -> \n)
        String lfSearch = searchString.replace("\r\n", "\n");
        if (!lfSearch.equals(searchString) && fileContent.contains(lfSearch)) {
            return lfSearch;
        }

        // Strategy 5: Tab/空格归一化 (tab <-> 4 spaces)
        String spacifiedSearch = searchString.replace("\t", "    ");
        if (!spacifiedSearch.equals(searchString) && fileContent.contains(spacifiedSearch)) {
            return spacifiedSearch;
        }
        String tabbedSearch = searchString.replace("    ", "\t");
        if (!tabbedSearch.equals(searchString) && fileContent.contains(tabbedSearch)) {
            return tabbedSearch;
        }

        return null;
    }

    /** 行尾空白归一化 — 去除每行末尾的空白字符 */
    private static String trimTrailingWhitespace(String text) {
        return Arrays.stream(text.split("\n", -1))
                .map(line -> line.replaceAll("\\s+$", ""))
                .collect(Collectors.joining("\n"));
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
