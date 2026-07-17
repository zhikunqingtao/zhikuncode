package com.aicodeassistant.tool.impl;

import com.aicodeassistant.history.FileHistoryService;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.impl.AtomicFileWriter.WriteResult;
import com.aicodeassistant.tool.impl.FileVersionTracker.ConflictCheckResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FileWriteTool — 创建或覆盖文件（全量写入）。
 * <p>
 * 安全机制: Read-before-Edit (文件必须先通过 FileReadTool 读取)
 * + mtime 竞态检测 (防止外部修改覆盖)。
 *
 */
@Component
public class FileWriteTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileWriteTool.class);

    private final FileHistoryService fileHistoryService;
    private final SessionManager sessionManager;
    private final FileVersionTracker fileVersionTracker;
    private final AtomicFileWriter atomicFileWriter;

    public FileWriteTool(FileHistoryService fileHistoryService, SessionManager sessionManager,
                         FileVersionTracker fileVersionTracker, AtomicFileWriter atomicFileWriter) {
        this.fileHistoryService = fileHistoryService;
        this.sessionManager = sessionManager;
        this.fileVersionTracker = fileVersionTracker;
        this.atomicFileWriter = atomicFileWriter;
    }

    @Override
    public String getName() {
        return "Write";
    }

    @Override
    public String getDescription() {
        return "Create or overwrite a file with the given content. "
                + "The file must have been read first using the Read tool.";
    }

    @Override
    public String prompt() {
        return """
                Writes a file to the local filesystem.
                
                Usage:
                - This tool will overwrite the existing file if there is one at the provided path.
                - If this is an existing file, you MUST use the Read tool first to read the file's \
                contents. This tool will fail if you did not read the file first.
                - Prefer the Edit tool for modifying existing files \u2014 it only sends the diff. Only \
                use this tool to create new files or for complete rewrites.
                - NEVER create documentation files (*.md) or README files unless explicitly \
                requested by the User.
                - Only use emojis if the user explicitly requests it. Avoid writing emojis to \
                files unless asked.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string", "description", "Absolute path to the file"),
                        "content", Map.of("type", "string", "description", "Complete file content")
                ),
                "required", List.of("file_path", "content")
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
        String filePath = resolvePath(input.getString("file_path"), context.workingDirectory());
        String content = input.getString("content");
        Path path = Path.of(filePath);

        try {
            String expectedOldHash = null;
            // AtomicFileWriter performs the security check before creating any
            // parent directory, so an unauthorized write has no filesystem side effect.
            boolean isCreate = !Files.exists(path);

            String originalContent = !isCreate ? Files.readString(path, StandardCharsets.UTF_8) : null;

            // ★ SHA-256 冲突检测 (Conflict-Abort 策略) — 仅对已存在文件
            if (!isCreate && originalContent != null) {
                // 记录读取时的 hash
                fileVersionTracker.recordRead(filePath);
                String expectedHash = fileVersionTracker.computeHash(originalContent);
                expectedOldHash = expectedHash;

                ConflictCheckResult conflictResult = fileVersionTracker.checkBeforeWrite(filePath, expectedHash);
                if (conflictResult.hasConflict()) {
                    log.warn("Conflict-Abort: file {} modified since last read (expected={}, current={})",
                            filePath, conflictResult.expectedHash(), conflictResult.currentHash());
                    Map<String, Object> conflictMeta = new HashMap<>();
                    conflictMeta.put("expectedHash", conflictResult.expectedHash());
                    conflictMeta.put("actualHash", conflictResult.currentHash());
                    if (conflictResult.lastEditor() != null) {
                        conflictMeta.put("lastEditor", conflictResult.lastEditor());
                    }
                    return ToolResult.failed(ToolResult.ToolFailureType.VALIDATION, "FILE_CONTENT_CONFLICT",
                            "文件自上次读取后已被修改，请重新读取文件后再编辑",
                            ToolResult.Retryability.NEVER, ToolResult.EffectState.NOT_STARTED,
                            null, conflictMeta);
                }
            }

            // 3. 原子写入（替代 Files.writeString）
            AtomicFileWriter.ExpectedOldState expectedState = isCreate
                    ? AtomicFileWriter.ExpectedOldState.absent()
                    : AtomicFileWriter.ExpectedOldState.sha256(expectedOldHash);
            WriteResult writeResult = atomicFileWriter.write(path,
                    content.getBytes(StandardCharsets.UTF_8), context.sessionId(),
                    expectedState, context.workingDirectory());
            if (!writeResult.success()) {
                return writeFailure(writeResult);
            }

            String type = isCreate ? "create" : "update";
            FileHistoryService.HistoryRecordResult history;
            String postCommitError = "";
            try {
                history = isCreate
                        ? new FileHistoryService.HistoryRecordResult(false, "HISTORY_SNAPSHOT_NOT_APPLICABLE")
                        : fileHistoryService.trackAppliedEdit(filePath, originalContent,
                            context.sessionId(), context.toolUseId(), "write");
                if (history == null) history = new FileHistoryService.HistoryRecordResult(false, "HISTORY_RESULT_UNAVAILABLE");
                sessionManager.getFileStateCache(context.sessionId()).markModified(filePath);
            } catch (RuntimeException postCommitFailure) {
                log.warn("File write applied but post-commit bookkeeping failed for {}: {}",
                        filePath, postCommitFailure.getMessage());
                history = new FileHistoryService.HistoryRecordResult(false, "POST_COMMIT_BOOKKEEPING_FAILED");
                postCommitError = "POST_COMMIT_BOOKKEEPING_FAILED";
            }

            return ToolResult.successWithEffect(type + ": " + filePath, ToolResult.EffectState.APPLIED)
                    .withMetadata("type", type)
                    .withMetadata("filePath", filePath)
                    .withMetadata("sealedHash", writeResult.newHash())
                    .withMetadata("historyRecorded", history.recorded())
                    .withMetadata("historyErrorCode", history.errorCode() == null ? "" : history.errorCode())
                    .withMetadata("postCommitErrorCode", postCommitError);

        } catch (IOException e) {
            log.error("Failed to write file: {}", filePath, e);
            return ToolResult.internalError("FILE_WRITE_IO_FAILED",
                    "Failed to write file: " + e.getMessage(), ToolResult.EffectState.NOT_STARTED);
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

    private String resolvePath(String filePath, String workingDirectory) {
        Path path = Path.of(filePath);
        if (path.isAbsolute()) return path.toString();
        return Path.of(workingDirectory).resolve(filePath).normalize().toString();
    }
}
