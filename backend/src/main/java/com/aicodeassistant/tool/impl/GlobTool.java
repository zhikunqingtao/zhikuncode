package com.aicodeassistant.tool.impl;

import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.StreamSupport;

/**
 * GlobTool — 按文件名模式搜索文件。
 * <p>
 * 使用 FileSystem.getPathMatcher + FileVisitor 遍历文件树。
 * 自动排除 VCS 目录 (.git, .svn 等)。
 *
 * @see <a href="SPEC §3.2.3">GlobTool 规范</a>
 */
@Component
public class GlobTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GlobTool.class);
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final Set<String> VCS_EXCLUDE = Set.of(
            ".git", ".svn", ".hg", ".bzr", ".jj", ".sl");

    @Override
    public String getName() {
        return "Glob";
    }

    @Override
    public String getDescription() {
        return "Search for files matching a glob pattern. Fast file discovery "
                + "for when you know the filename pattern you're looking for.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of("type", "string", "description", "Glob pattern (e.g. \"**/*.java\")"),
                        "path", Map.of("type", "string", "description", "Search directory (default: cwd)"),
                        "max_results", Map.of("type", "integer", "description", "Maximum number of results to return (default: 200)")
                ),
                "required", List.of("pattern")
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
        String pattern = input.getString("pattern");
        String searchPath = input.getString("path", context.workingDirectory());
        int maxResults = input.getInt("max_results", 200);
        Path basePath = Path.of(searchPath);

        if (!Files.isDirectory(basePath)) {
            return ToolResult.error("Directory does not exist: " + searchPath);
        }

        long start = System.currentTimeMillis();
        List<String> results = new ArrayList<>();
        boolean truncated = false;

        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

            Files.walkFileTree(basePath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (VCS_EXCLUDE.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (results.size() >= maxResults) {
                        return FileVisitResult.TERMINATE;
                    }
                    Path relativePath = basePath.relativize(file);
                    if (matcher.matches(relativePath)) {
                        results.add(relativePath.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE; // 跳过无法访问的文件
                }
            });

            truncated = results.size() >= maxResults;

        } catch (IOException e) {
            log.error("Glob search failed for pattern '{}' in '{}'", pattern, searchPath, e);
            return ToolResult.error("Glob search failed: " + e.getMessage());
        }

        long durationMs = System.currentTimeMillis() - start;
        return ToolResult.success(String.join("\n", results))
                .withMetadata("filenames", results)
                .withMetadata("numFiles", results.size())
                .withMetadata("durationMs", durationMs)
                .withMetadata("truncated", truncated);
    }
}
