package com.aicodeassistant.tool.impl;

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

    private static final long MAX_SIZE_BYTES = 200 * 1024 * 1024; // 200MB
    private static final int MAX_OUTPUT_LINES = 10_000; // 简化: 用行数近似 token 限制
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg");
    private static final Set<String> BLOCKED_DEVICE_PATHS = Set.of(
            "/dev/zero", "/dev/random", "/dev/urandom", "/dev/stdin", "/dev/tty");

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
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String filePath = resolvePath(input.getString("file_path"), context.workingDirectory());

        try {
            Path path = Path.of(filePath);

            // 1. 安全检查 — 阻止设备文件
            if (BLOCKED_DEVICE_PATHS.contains(filePath)) {
                return ToolResult.error("Cannot read device file: " + filePath);
            }

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

    /** 解析路径 — 相对路径转绝对路径 */
    private String resolvePath(String filePath, String workingDirectory) {
        Path path = Path.of(filePath);
        if (path.isAbsolute()) return path.toString();
        return Path.of(workingDirectory).resolve(filePath).normalize().toString();
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
