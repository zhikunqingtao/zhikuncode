package com.aicodeassistant.tool.impl;

import com.aicodeassistant.engine.KeyFileTracker;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.security.PathSecurityService.PathCheckResult;
import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * GrepTool — 在文件内容中搜索模式 (优先使用 ripgrep)。
 * <p>
 * 通过 ProcessBuilder 调用 rg (ripgrep)。
 * 支持 content / files_with_matches / count 三种输出模式。
 *
 */
@Component
public class GrepTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GrepTool.class);
    private static final int DEFAULT_HEAD_LIMIT = 250;
    private static final int MAX_RESULT_SIZE_CHARS = 20_000;
    private static final int MAX_OUTPUT_LINES = 10_000;
    private static final int MAX_COLUMNS = 500;
    private static final long PROCESS_TIMEOUT_MS = 300_000L; // 300秒(5分钟)，与外层Watchdog对齐
    private static final long GRACEFUL_KILL_WAIT_MS = 5_000L; // 优雅终止等待
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);
    private static final ExecutorService STREAM_READER_EXECUTOR =
            Executors.newFixedThreadPool(
                    Math.min(Runtime.getRuntime().availableProcessors(), 4),
                    r -> {
                        Thread t = new Thread(r, "grep-stream-reader-" + THREAD_COUNTER.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    });
    private static final Set<String> VCS_EXCLUDE = Set.of(
            ".git", ".svn", ".hg", ".bzr", ".jj", ".sl");

    private final KeyFileTracker keyFileTracker;
    private final PathSecurityService pathSecurity;

    public GrepTool(
            KeyFileTracker keyFileTracker,
            PathSecurityService pathSecurity) {
        this.keyFileTracker = keyFileTracker;
        this.pathSecurity = pathSecurity;
    }

    @PreDestroy
    public void shutdown() {
        log.debug("Shutting down GrepTool stream reader executor");
        STREAM_READER_EXECUTOR.shutdown();
        try {
            if (!STREAM_READER_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                STREAM_READER_EXECUTOR.shutdownNow();
                log.warn("GrepTool stream reader executor did not terminate gracefully, forced shutdown");
            }
        } catch (InterruptedException e) {
            STREAM_READER_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 是否使用 ripgrep (rg)。启动时检测一次。 */
    private static final boolean HAS_RIPGREP = detectRipgrep();

    private static boolean detectRipgrep() {
        try {
            Process p = new ProcessBuilder("rg", "--version")
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getName() {
        return "Grep";
    }

    @Override
    public long getMaxExecutionTimeMs() {
        return 180_000L; // 3 minutes for large-scale ripgrep scans
    }

    @Override
    public String getDescription() {
        return "Search file contents using regular expressions. Powered by ripgrep (rg) when available. "
                + "Supports glob/include/exclude filters, context lines, result paging "
                + "(head_limit/offset), and multiple output modes. "
                + "Multiline search and file type filtering require ripgrep.";
    }

    @Override
    public String prompt() {
        return """
                A powerful search tool built on ripgrep
                
                Usage:
                - ALWAYS use Grep for search tasks. NEVER invoke `grep` or `rg` as a Bash command. \
                The Grep tool has been optimized for correct permissions and access.
                - Supports full regex syntax (e.g., "log.*Error", "function\\s+\\w+")
                - Filter files with glob/include/exclude parameters (e.g., "*.js", "**/*.tsx")
                - Filter by file type with `type` (e.g., "js", "py") — requires ripgrep
                - Output modes: "content" shows matching lines, "files_with_matches" shows only \
                file paths (default), "count" shows match counts
                - Paginate results with `offset` (number of result lines to skip) and \
                `head_limit` (maximum result lines returned, default 250). When head_limit <= 0, \
                paging is disabled and offset is ignored; total output limits still apply.
                - When used, numeric paging/context values must be 32-bit integers. For positive head_limit, \
                offset must be non-negative and offset + head_limit + 1 must not exceed 2147483647.
                - Cross-line patterns need `multiline: true` — requires ripgrep
                - In content mode, `-C`/`-B`/`-A` add lines of context around matches \
                (around/before/after); context counts must be non-negative
                - Use Agent tool for open-ended searches requiring multiple rounds
                - Pattern syntax: Uses ripgrep (not grep) - literal braces need escaping \
                (use `interface\\{\\}` to find `interface{}` in Go code)
                - Case-insensitive search: use `-i`
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.ofEntries(
                        Map.entry("pattern", Map.of("type", "string", "description", "Regex search pattern")),
                        Map.entry("path", Map.of("type", "string", "description", "Search path (default: cwd)")),
                        Map.entry("glob", Map.of("type", "string", "description", "File filter (e.g. \"*.java\")")),
                        Map.entry("include", Map.of("type", "string", "description", "Include files matching glob (e.g. \"*.java\")")),
                        Map.entry("exclude", Map.of("type", "string", "description", "Exclude files matching glob (e.g. \"*.min.js\")")),
                        Map.entry("output_mode", Map.of("type", "string", "description", "content|files_with_matches|count")),
                        Map.entry("-i", Map.of("type", "boolean", "description", "Case-insensitive search")),
                        Map.entry("head_limit", Map.of("type", "integer", "description", "32-bit integer maximum result lines (default 250); <= 0 disables paging and ignores offset, while total output limits still apply; positive values require offset + head_limit + 1 <= 2147483647")),
                        Map.entry("offset", Map.of("type", "integer", "description", "Non-negative 32-bit integer result lines to skip when head_limit > 0; ignored when head_limit <= 0")),
                        Map.entry("multiline", Map.of("type", "boolean", "description", "Enable multiline matching (requires ripgrep)")),
                        Map.entry("type", Map.of("type", "string", "description", "Filter by file type, e.g. \"js\", \"py\" (requires ripgrep)")),
                        Map.entry("-A", Map.of("type", "integer", "description", "Non-negative 32-bit integer lines of context after matches (content mode only)")),
                        Map.entry("-B", Map.of("type", "integer", "description", "Non-negative 32-bit integer lines of context before matches (content mode only)")),
                        Map.entry("-C", Map.of("type", "integer", "description", "Non-negative 32-bit integer lines of context around matches (content mode only)"))
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
    public String searchHint(ToolInput input) {
        return input.getOptionalString("pattern").orElse(null);
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String pattern = input.getString("pattern");
        String searchPath = input.getString("path", context.workingDirectory());
        String outputMode = input.getString("output_mode", "files_with_matches");
        final int headLimit;
        final int offset;
        final int maxLinesToRead;
        try {
            headLimit = exactInt(input, "head_limit", DEFAULT_HEAD_LIMIT);
            // Disabled paging has always ignored offset; do not validate an unused value.
            offset = headLimit > 0 ? exactInt(input, "offset", 0) : 0;
            if (headLimit > 0 && offset < 0) {
                throw new IllegalArgumentException("offset must be non-negative when head_limit > 0");
            }
            long readLines = headLimit > 0 ? (long) offset + headLimit + 1 : MAX_OUTPUT_LINES;
            if (readLines > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("offset + head_limit + 1 must not exceed 2147483647");
            }
            maxLinesToRead = (int) readLines;
            if ("content".equals(outputMode)) {
                for (String key : List.of("-A", "-B", "-C")) {
                    Integer lines = exactInt(input, key, null);
                    if (lines != null && lines < 0) {
                        throw new IllegalArgumentException(key + " must be non-negative in content mode");
                    }
                }
            }
        } catch (IllegalArgumentException invalid) {
            return ToolResult.validationError("GREP_ARGUMENT_INVALID", invalid.getMessage());
        }

        try {
            var inspected = pathSecurity.inspectAuthorizedExecutionRecursiveReadRootPermission(
                    searchPath, context.workingDirectory());
            PathCheckResult pathCheck = inspected.permission();
            if (!pathCheck.isAllowed()) {
                return ToolResult.validationError(
                        "GREP_PATH_DENIED", pathCheck.message());
            }
            Path searchRoot = inspected.target();
            if (!Files.exists(searchRoot)) {
                return ToolResult.validationError(
                        "GREP_PATH_NOT_FOUND",
                        "Search path does not exist: " + searchPath);
            }
            searchPath = searchRoot.toString();

            // 检查是否使用了 rg 特有功能但 rg 不可用
            if (!HAS_RIPGREP) {
                if (input.getBoolean("multiline", false)
                        || input.getOptionalString("type").isPresent()) {
                    return ToolResult.failed(ToolResult.ToolFailureType.PROCESS, "RIPGREP_NOT_INSTALLED",
                        "This search uses ripgrep-specific features (multiline/type) but rg is not installed.\n"
                        + "Install: brew install ripgrep (macOS) | apt-get install ripgrep (Linux)",
                        ToolResult.Retryability.NEVER, ToolResult.EffectState.NOT_STARTED, null, Map.of());
                }
                log.info("Using grep fallback (ripgrep not available)");
            }

            List<String> args;
            // 目录搜索以搜索根为工作目录、对 "." 执行，排除规则保持完整：
            // 命令行目标 "." 不匹配任何被排除的目录名，因此显式根目录不会被
            // 剪掉，而根之下同名/受保护的目录仍按名字被排除。输出中的 "./…"
            // 前缀随后还原为搜索根下的绝对路径。
            boolean directorySearch = Files.isDirectory(searchRoot);
            String searchTarget = directorySearch ? "." : searchPath;
            if (HAS_RIPGREP) {
                args = buildRipgrepArgs(input, pattern, searchTarget,
                        outputMode, directorySearch);
            } else {
                log.debug("ripgrep not found, falling back to system grep");
                args = buildGrepFallbackArgs(input, pattern, searchTarget,
                        outputMode, directorySearch);
            }

            // 执行搜索命令
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            if (directorySearch) {
                pb.directory(searchRoot.toFile());
            }
            Process process = pb.start();

            // 异步消费输出流（防止缓冲区满导致死锁）
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(
                    () -> {
                        try (var reader = new BufferedReader(
                                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                            return reader.lines()
                                    .limit(maxLinesToRead)
                                    .collect(Collectors.joining("\n"));
                        } catch (IOException e) {
                            log.warn("Error reading grep output stream: {}", e.getMessage());
                            return "";
                        }
                    }, STREAM_READER_EXECUTOR);

            // 等待进程完成（带超时保护）
            boolean finished = process.waitFor(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                // 优雅终止: 先SIGTERM
                process.destroy();
                // Force close process streams to unblock any pending reads
                try { process.getInputStream().close(); } catch (IOException ignored) {}
                try { process.getErrorStream().close(); } catch (IOException ignored) {}
                boolean terminated = process.waitFor(GRACEFUL_KILL_WAIT_MS, TimeUnit.MILLISECONDS);
                if (!terminated) {
                    // 强制终止: SIGKILL
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
                log.warn("Grep process timed out after {}ms for pattern '{}'", PROCESS_TIMEOUT_MS, pattern);
                return ToolResult.timedOut("GREP_PROCESS_DEADLINE_EXCEEDED",
                        "Search timed out after " + (PROCESS_TIMEOUT_MS / 1000) + " seconds. " +
                        "Try narrowing the search with 'glob' or 'path' parameters.", 137, !process.isAlive(),
                        ToolResult.EffectState.NONE);
            }

            // 获取输出（进程已结束，给少量时间等待流读取完成）
            String rawOutput = outputFuture.get(5, TimeUnit.SECONDS);
            if (directorySearch) {
                rawOutput = restoreSearchRootPaths(rawOutput, searchPath);
            }

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

            // ★ KeyFileTracker 埋点 — 记录搜索命中文件 ★
            for (String matchedFile : matchedFiles) {
                keyFileTracker.trackFileReference(context.sessionId(), matchedFile, context.toolUseId());
            }

            // 5. 字符数截断
            String result = String.join("\n", lines);
            boolean charsTruncated = false;
            if (result.length() > MAX_RESULT_SIZE_CHARS) {
                result = result.substring(0, MAX_RESULT_SIZE_CHARS);
                charsTruncated = true;
            }

            return ToolResult.success(result
                            + (wasTruncated || charsTruncated ? "\n[Results truncated]" : ""))
                    .withMetadata("mode", outputMode)
                    .withMetadata("numFiles", matchedFiles.size())
                    .withMetadata("filenames", new ArrayList<>(matchedFiles))
                    .withMetadata("truncated", wasTruncated || charsTruncated);

        } catch (IOException e) {
            log.error("Grep failed for pattern '{}': {}", pattern, e.getMessage());
            return ToolResult.internalError("GREP_PROCESS_START_FAILED",
                    "Grep search failed: " + e.getMessage(), ToolResult.EffectState.NONE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.cancelled("GREP_INTERRUPTED", "Grep search interrupted",
                    ToolResult.EffectState.NONE);
        } catch (ExecutionException e) {
            log.error("Grep output read failed for pattern '{}': {}", pattern, e.getMessage());
            return ToolResult.internalError("GREP_OUTPUT_READ_FAILED",
                    "Grep search failed: " + e.getCause().getMessage(), ToolResult.EffectState.NONE);
        } catch (TimeoutException e) {
            log.warn("Grep output stream read timed out for pattern '{}'", pattern);
            return ToolResult.timedOut("GREP_OUTPUT_DEADLINE_EXCEEDED",
                    "Grep output read timed out. Try narrowing the search with 'glob' or 'path' parameters.",
                    null, true, ToolResult.EffectState.NONE);
        }
    }

    /** Parse before narrowing: ToolInput.getInt() otherwise wraps large Number values. */
    private static Integer exactInt(ToolInput input, String key, Integer defaultValue) {
        Object raw = input.getRawData().get(key);
        if (raw == null) return defaultValue;
        try {
            if (raw instanceof Number) return new BigDecimal(raw.toString()).intValueExact();
            if (raw instanceof String text) return Integer.parseInt(text);
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException(key + " must be a 32-bit integer");
        }
        throw new IllegalArgumentException(key + " must be a 32-bit integer");
    }

    /** 构建 ripgrep 参数列表 */
    List<String> buildRipgrepArgs(
            ToolInput input,
            String pattern,
            String searchPath,
            String outputMode,
            boolean excludeProtectedDescendants) {
        List<String> args = new ArrayList<>(List.of("rg", "--hidden"));
        for (String dir : VCS_EXCLUDE) {
            args.addAll(List.of("--glob", "!" + dir));
        }
        args.addAll(List.of("--max-columns", String.valueOf(MAX_COLUMNS)));

        if (input.getBoolean("multiline", false)) {
            args.addAll(List.of("-U", "--multiline-dotall"));
        }
        if (input.getBoolean("-i", false)) args.add("-i");

        switch (outputMode) {
            case "files_with_matches" -> args.add("-l");
            case "count" -> args.add("-c");
            case "content" -> {
                args.add("-n");
                input.getOptionalInt("-C").ifPresent(c -> args.addAll(List.of("-C", String.valueOf(c))));
                input.getOptionalInt("-B").ifPresent(b -> args.addAll(List.of("-B", String.valueOf(b))));
                input.getOptionalInt("-A").ifPresent(a -> args.addAll(List.of("-A", String.valueOf(a))));
            }
        }

        input.getOptionalString("glob").ifPresent(g -> args.addAll(List.of("--glob", g)));
        input.getOptionalString("include").ifPresent(g -> args.addAll(List.of("--glob", g)));
        input.getOptionalString("exclude").ifPresent(g -> args.addAll(List.of("--glob", "!" + g)));
        input.getOptionalString("type").ifPresent(t -> args.addAll(List.of("--type", t)));
        // Security exclusions come last so caller-provided globs cannot
        // re-include protected descendants during a broad directory search.
        // Directory searches target "." with the root as working directory,
        // so the full list — including the root's own name — never prunes
        // the explicit root while descendants stay excluded.
        if (excludeProtectedDescendants) {
            for (String file : pathSecurity.protectedFileGlobs()) {
                args.addAll(List.of(
                        "--glob", "!" + caseInsensitiveGlobLiteral(file)));
            }
            for (String dir : pathSecurity.protectedDirectoryNames()) {
                args.addAll(List.of(
                        "--glob", "!" + caseInsensitiveGlobLiteral(dir)));
            }
        }

        if (pattern.startsWith("-")) {
            args.addAll(List.of("-e", pattern));
        } else {
            args.add(pattern);
        }
        args.add(searchPath);
        return args;
    }

    /** 构建系统 grep fallback 参数列表 */
    List<String> buildGrepFallbackArgs(
            ToolInput input,
            String pattern,
            String searchPath,
            String outputMode,
            boolean excludeProtectedDescendants) {
        List<String> args = new ArrayList<>(List.of("grep", "-r", "--include=*"));

        // 排除 VCS 目录
        for (String dir : VCS_EXCLUDE) {
            args.add("--exclude-dir=" + dir);
        }
        // 也排除常见无用目录
        args.add("--exclude-dir=node_modules");
        args.add("--exclude-dir=target");

        if (input.getBoolean("-i", false)) args.add("-i");

        switch (outputMode) {
            case "files_with_matches" -> args.add("-l");
            case "count" -> args.add("-c");
            case "content" -> {
                args.add("-n");
                input.getOptionalInt("-C").ifPresent(c -> args.addAll(List.of("-C", String.valueOf(c))));
                input.getOptionalInt("-B").ifPresent(b -> args.addAll(List.of("-B", String.valueOf(b))));
                input.getOptionalInt("-A").ifPresent(a -> args.addAll(List.of("-A", String.valueOf(a))));
            }
        }

        // glob 过滤转 grep --include
        input.getOptionalString("glob").ifPresent(g -> args.addAll(List.of("--include", g)));
        input.getOptionalString("include").ifPresent(g -> args.addAll(List.of("--include", g)));
        input.getOptionalString("exclude").ifPresent(g -> args.addAll(List.of("--exclude", g)));
        if (excludeProtectedDescendants) {
            for (String file : pathSecurity.protectedFileGlobs()) {
                args.add("--exclude=" + caseInsensitiveGlobLiteral(file));
            }
            for (String dir : pathSecurity.protectedDirectoryNames()) {
                args.add("--exclude-dir=" + caseInsensitiveGlobLiteral(dir));
            }
        }

        // 使用 -E (extended regex) 以兼容 ripgrep 的正则语法
        args.add("-E");

        if (pattern.startsWith("-")) {
            args.addAll(List.of("-e", pattern));
        } else {
            args.add(pattern);
        }
        args.add(searchPath);
        return args;
    }

    /**
     * Restores absolute paths under the search root for output produced with
     * the search root as the process working directory ("./…" or Windows ".\\…" prefixes).
     */
    private static String restoreSearchRootPaths(String rawOutput, String searchRoot) {
        boolean windows = File.separatorChar == '\\';
        String prefix = searchRoot.endsWith("/") || (windows && searchRoot.endsWith("\\"))
                ? searchRoot : searchRoot + File.separator;
        return rawOutput.lines()
                .map(line -> line.startsWith("./") || (windows && line.startsWith(".\\"))
                        ? prefix + line.substring(2) : line)
                .collect(Collectors.joining("\n"));
    }

    /**
     * Converts an exact protected basename to a portable case-insensitive glob.
     * Both ripgrep and the system grep fallback understand bracket expressions,
     * so their exclusion semantics stay aligned with PathSecurityService.
     */
    static String caseInsensitiveGlobLiteral(String literal) {
        StringBuilder glob = new StringBuilder(literal.length() * 2);
        for (int index = 0; index < literal.length(); index++) {
            char current = literal.charAt(index);
            if (current >= 'a' && current <= 'z') {
                glob.append('[').append(current)
                        .append(Character.toUpperCase(current)).append(']');
            } else if (current >= 'A' && current <= 'Z') {
                glob.append('[').append(Character.toLowerCase(current))
                        .append(current).append(']');
            } else {
                glob.append(current);
            }
        }
        return glob.toString();
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
