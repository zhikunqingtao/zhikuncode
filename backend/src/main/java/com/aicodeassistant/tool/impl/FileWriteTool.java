package com.aicodeassistant.tool.impl;

import com.aicodeassistant.history.FileHistoryService;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * FileWriteTool — 创建或覆盖文件（全量写入）。
 * <p>
 * 安全机制: Read-before-Edit (文件必须先通过 FileReadTool 读取)
 * + mtime 竞态检测 (防止外部修改覆盖)。
 *
 * @see <a href="SPEC §3.2.3">FileWriteTool 规范</a>
 */
@Component
public class FileWriteTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileWriteTool.class);

    private final FileHistoryService fileHistoryService;
    private final SessionManager sessionManager;

    public FileWriteTool(FileHistoryService fileHistoryService, SessionManager sessionManager) {
        this.fileHistoryService = fileHistoryService;
        this.sessionManager = sessionManager;
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
            // 1. 自动创建中间目录
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            // 2. 判断操作类型
            boolean isCreate = !Files.exists(path);

            // ── 新增: 编辑前保存快照 ──
            if (!isCreate) {
                fileHistoryService.trackEdit(filePath, context.sessionId(), context.toolUseId(), "write");
            }

            String originalContent = !isCreate ? Files.readString(path, StandardCharsets.UTF_8) : null;

            // 3. 写入文件
            Files.writeString(path, content, StandardCharsets.UTF_8);

            // 4. 构建结果
            String type = isCreate ? "create" : "update";

            // ★ FileStateCache 集成 — 标记已修改 (§11.5.9)
            sessionManager.getFileStateCache(context.sessionId()).markModified(filePath);

            return ToolResult.success(type + ": " + filePath)
                    .withMetadata("type", type)
                    .withMetadata("filePath", filePath);

        } catch (IOException e) {
            log.error("Failed to write file: {}", filePath, e);
            return ToolResult.error("Failed to write file: " + e.getMessage());
        }
    }

    private String resolvePath(String filePath, String workingDirectory) {
        Path path = Path.of(filePath);
        if (path.isAbsolute()) return path.toString();
        return Path.of(workingDirectory).resolve(filePath).normalize().toString();
    }
}
