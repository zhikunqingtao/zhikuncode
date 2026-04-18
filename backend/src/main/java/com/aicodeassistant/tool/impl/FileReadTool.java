package com.aicodeassistant.tool.impl;

import com.aicodeassistant.engine.KeyFileTracker;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.security.PathSecurityService.PathCheckResult;
import com.aicodeassistant.service.FileStateCache;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * FileReadTool — 读取文件内容。
 * <p>
 * 支持文本文件 (offset/limit 分片) 和图片文件 (base64)。
 * 200MB 文件大小上限, 60K token 输出限制。
 *
 * @see <a href="SPEC §3.2.3">FileReadTool 规范</a>
 */
@Component
public class FileReadTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileReadTool.class);

    private final PathSecurityService pathSecurity;
    private final SessionManager sessionManager;
    private final KeyFileTracker keyFileTracker;

    public FileReadTool(PathSecurityService pathSecurity, SessionManager sessionManager,
                        KeyFileTracker keyFileTracker) {
        this.pathSecurity = pathSecurity;
        this.sessionManager = sessionManager;
        this.keyFileTracker = keyFileTracker;
    }

    private static final long MAX_SIZE_BYTES = 200 * 1024 * 1024; // 200MB
    private static final int MAX_OUTPUT_LINES = 10_000; // 简化: 用行数近似 token 限制
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg");

    @Override
    public String getName() {
        return "Read";
    }

    @Override
    public String getDescription() {
        return "Read the contents of a file. Supports text files with optional line range "
                + "(offset/limit) and image files (returns base64).";
    }

    @Override
    public String prompt() {
        return """
                Reads a file from the local filesystem. You can access any file directly by using this tool.
                Assume this tool is able to read all files on the machine. If the User provides a path \
                to a file assume that path is valid. It is okay to read a file that does not exist; an \
                error will be returned.
                
                Usage:
                - The file_path parameter must be an absolute path, not a relative path
                - By default, it reads up to 2000 lines starting from the beginning of the file
                - You can optionally specify a line offset and limit (especially handy for long files), \
                but it's recommended to read the whole file by not providing these parameters
                - Results are returned using cat -n format, with line numbers starting at 1
                - This tool allows reading images (eg PNG, JPG, etc). When reading an image file the \
                contents are presented visually as a multimodal LLM.
                - This tool can read Jupyter notebooks (.ipynb files) and returns all cells with their \
                outputs, combining code, text, and visualizations.
                - This tool can only read files, not directories. To read a directory, use an ls \
                command via the Bash tool.
                - You will regularly be asked to read screenshots. If the user provides a path to a \
                screenshot, ALWAYS use this tool to view the file at the path.
                - If you read a file that exists but has empty contents you will receive a system \
                reminder warning in place of file contents.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string", "description", "Absolute path to the file"),
                        "offset", Map.of("type", "integer", "description", "Starting line number (0-based)"),
                        "limit", Map.of("type", "integer", "description", "Number of lines to read")
                ),
                "required", List.of("file_path")
        );
    }

    @Override
    public String getGroup() {
        return "read";
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        return true;
    }

    @Override
    public String searchHint(ToolInput input) {
        return input.getOptionalString("file_path").orElse(null);
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        Path resolved = pathSecurity.resolvePath(input.getString("file_path"), context.workingDirectory());
        String filePath = resolved.toString();

        // 1. PathSecurityService 统一安全检查
        PathCheckResult checkResult = pathSecurity.checkReadPermission(
                input.getString("file_path"), context.workingDirectory());
        if (!checkResult.isAllowed()) {
            return ToolResult.error(checkResult.message());
        }
        if (checkResult.needsConfirmation()) {
            log.warn("Reading sensitive file (allowed with warning): {}", filePath);
        }

        try {
            Path path = resolved;

            // 2. 检查文件存在
            if (!Files.exists(path)) {
                return ToolResult.error("File does not exist: " + filePath);
            }

            // 3. 检查文件大小
            long fileSize = Files.size(path);
            if (fileSize > MAX_SIZE_BYTES) {
                return ToolResult.error("File too large (" + fileSize + " bytes). "
                        + "Use offset and limit parameters to read in chunks.");
            }

            // 4. 图片文件处理
            String ext = getExtension(filePath).toLowerCase();
            if (IMAGE_EXTENSIONS.contains(ext)) {
                byte[] imageBytes = Files.readAllBytes(path);
                String base64 = Base64.getEncoder().encodeToString(imageBytes);
                String mimeType = detectMimeType(ext);
                return ToolResult.image(base64, mimeType, fileSize);
            }

            // 5. 文本文件处理 — 使用 BufferedReader 流式读取，避免大文件 OOM
            int rawStart = input.getOptionalInt("offset").orElse(0);
            int startLine = Math.max(0, rawStart);
            int limit = input.getOptionalInt("limit").orElse(0);

            List<String> selectedLines;
            int totalLines;
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                if (startLine > 0 || limit > 0) {
                    // 流式跳过 + 限制，不加载整个文件
                    selectedLines = reader.lines()
                            .skip(startLine)
                            .limit(limit > 0 ? limit : Long.MAX_VALUE)
                            .collect(Collectors.toList());
                    totalLines = -1; // 流式模式下不计算总行数
                } else {
                    selectedLines = reader.lines().collect(Collectors.toList());
                    totalLines = selectedLines.size();
                }
            }

            String content = String.join("\n", selectedLines);

            // ★ FileStateCache 集成 — 记录已读 (§11.5.9)
            FileStateCache cache = sessionManager.getFileStateCache(context.sessionId());
            int readOffset = startLine;
            int readLimit = limit;
            cache.markRead(filePath, content, readOffset > 0 ? readOffset : null,
                    readLimit > 0 ? readLimit : null, selectedLines.size() >= MAX_OUTPUT_LINES);

            // ★ KeyFileTracker 埋点 — 记录文件访问 ★
            keyFileTracker.trackFileReference(context.sessionId(), filePath, context.toolUseId());

            return ToolResult.text(content)
                    .withMetadata("filePath", filePath)
                    .withMetadata("numLines", selectedLines.size())
                    .withMetadata("startLine", startLine)
                    .withMetadata("totalLines", totalLines);

        } catch (IOException e) {
            log.error("Failed to read file: {}", filePath, e);
            return ToolResult.error("Failed to read file: " + e.getMessage());
        }
    }

    /** 获取文件扩展名 */
    private String getExtension(String filePath) {
        int dot = filePath.lastIndexOf('.');
        return dot >= 0 ? filePath.substring(dot) : "";
    }

    /** 检测 MIME 类型 */
    private String detectMimeType(String ext) {
        return switch (ext) {
            case ".png" -> "image/png";
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".svg" -> "image/svg+xml";
            default -> "application/octet-stream";
        };
    }
}
