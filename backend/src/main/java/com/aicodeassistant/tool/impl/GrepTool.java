package com.aicodeassistant.tool.impl;

import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * GrepTool — 在文件内容中搜索模式 (优先使用 ripgrep)。
 * <p>
 * 通过 ProcessBuilder 调用 rg (ripgrep)。
 * 支持 content / files_with_matches / count 三种输出模式。
 *
 * @see <a href="SPEC §3.2.3">GrepTool 规范</a>
 */
@Component
public class GrepTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GrepTool.class);
    private static final int DEFAULT_HEAD_LIMIT = 250;
    private static final int MAX_COLUMNS = 500;
    private static final Set<String> VCS_EXCLUDE = Set.of(
            ".git", ".svn", ".hg", ".bzr", ".jj", ".sl");

    @Override
    public String getName() {
        return "Grep";
    }

    @Override
    public String getDescription() {
        return "Search file contents using regular expressions. Powered by ripgrep (rg). "
                + "Supports file type filtering, context lines, and multiple output modes.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of("type", "string", "description", "Regex search pattern"),
                        "path", Map.of("type", "string", "description", "Search path (default: cwd)"),
                        "glob", Map.of("type", "string", "description", "File filter (e.g. \"*.java\")"),
                        "output_mode", Map.of("type", "string", "description", "content|files_with_matches|count"),
                        "-i", Map.of("type", "boolean", "description", "Case-insensitive search")
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
        String outputMode = input.getString("output_mode", "files_with_matches");
        int headLimit = input.getInt("head_limit", DEFAULT_HEAD_LIMIT);
        int offset = input.getInt("offset", 0);

        try {
            // 1. 构建 ripgrep 参数
            List<String> args = new ArrayList<>(List.of("rg", "--hidden"));
            for (String dir : VCS_EXCLUDE) {
                args.addAll(List.of("--glob", "!" + dir));
            }
            args.addAll(List.of("--max-columns", String.valueOf(MAX_COLUMNS)));

            // 多行模式
            if (input.getBoolean("multiline", false)) {
                args.addAll(List.of("-U", "--multiline-dotall"));
            }

            // 大小写
            if (input.getBoolean("-i", false)) args.add("-i");

            // 输出模式
            switch (outputMode) {
                case "files_with_matches" -> args.add("-l");
                case "count" -> args.add("-c");
                case "content" -> {
                    args.add("-n"); // 行号
                    input.getOptionalInt("-C").ifPresent(c -> args.addAll(List.of("-C", String.valueOf(c))));
                    input.getOptionalInt("-B").ifPresent(b -> args.addAll(List.of("-B", String.valueOf(b))));
                    input.getOptionalInt("-A").ifPresent(a -> args.addAll(List.of("-A", String.valueOf(a))));
                }
            }

            // 文件类型过滤
            input.getOptionalString("glob").ifPresent(g -> args.addAll(List.of("--glob", g)));
            input.getOptionalString("type").ifPresent(t -> args.addAll(List.of("--type", t)));

            // pattern 以 - 开头时使用 -e 防止误解析
            if (pattern.startsWith("-")) {
                args.addAll(List.of("-e", pattern));
            } else {
                args.add(pattern);
            }
            args.add(searchPath);

            // 2. 执行 ripgrep
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String rawOutput;
            try (var reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                rawOutput = reader.lines().collect(Collectors.joining("\n"));
            }

            process.waitFor();

            // 3. 应用 head_limit + offset 分页
            List<String> lines = new ArrayList<>(rawOutput.lines().toList());
            boolean wasTruncated = false;
            if (headLimit > 0 && !lines.isEmpty()) {
                int start = Math.min(offset, lines.size());
                int end = Math.min(start + headLimit, lines.size());
                wasTruncated = (lines.size() - start) > headLimit;
                lines = lines.subList(start, end);
            }

            // 4. 提取匹配文件列表
            Set<String> matchedFiles = extractFileNames(lines, outputMode);

            return ToolResult.success(String.join("\n", lines))
                    .withMetadata("mode", outputMode)
                    .withMetadata("numFiles", matchedFiles.size())
                    .withMetadata("filenames", new ArrayList<>(matchedFiles))
                    .withMetadata("truncated", wasTruncated);

        } catch (IOException e) {
            log.error("Grep failed for pattern '{}': {}", pattern, e.getMessage());
            return ToolResult.error("Grep search failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Grep search interrupted");
        }
    }

    /** 从输出行中提取文件名 */
    private Set<String> extractFileNames(List<String> lines, String outputMode) {
        Set<String> files = new LinkedHashSet<>();
        for (String line : lines) {
            if (line.isBlank()) continue;
            if ("files_with_matches".equals(outputMode)) {
                files.add(line.trim());
            } else {
                // content/count 模式: 文件名在冒号前
                int colonIdx = line.indexOf(':');
                if (colonIdx > 0) {
                    files.add(line.substring(0, colonIdx));
                }
            }
        }
        return files;
    }
}
