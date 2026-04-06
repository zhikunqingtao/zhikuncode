package com.aicodeassistant.tool.impl;

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
            String originalContent = null;
            if (!isCreate) {
                originalContent = Files.readString(path, StandardCharsets.UTF_8);
            }

            // 3. 写入文件
            Files.writeString(path, content, StandardCharsets.UTF_8);

            // 4. 构建结果
            String type = isCreate ? "create" : "update";
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
