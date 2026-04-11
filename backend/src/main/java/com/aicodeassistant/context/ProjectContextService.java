package com.aicodeassistant.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * ProjectContextService — 项目上下文索引收集与持久化。
 * <p>
 * 收集项目的 git 信息、文件树、构建类型等上下文，
 * 持久化到 SQLite 以避免每次会话重复执行 git 命令。
 * 通过 git HEAD SHA 判断缓存是否过期。
 *
 * @see <a href="SPEC §2.2">项目上下文</a>
 */
@Service
public class ProjectContextService {

    private static final Logger log = LoggerFactory.getLogger(ProjectContextService.class);

    /** git 命令超时（秒） */
    private static final int GIT_TIMEOUT_SECONDS = 10;
    /** 文件树最大条目数 */
    private static final int MAX_FILE_TREE_ENTRIES = 500;
    /** 最近提交数 */
    private static final int RECENT_COMMITS_COUNT = 20;

    private final ProjectContextRepository repository;

    /** 内存缓存 — workingDirHash → snapshot */
    private final ConcurrentHashMap<String, ProjectContextSnapshot> memoryCache = new ConcurrentHashMap<>();

    public ProjectContextService(ProjectContextRepository repository) {
        this.repository = repository;
    }

    /**
     * 获取项目上下文（带多级缓存）。
     *
     * @param workingDir 项目工作目录
     * @return 项目上下文快照，失败返回 null
     */
    public ProjectContextSnapshot getContext(Path workingDir) {
        if (workingDir == null || !Files.isDirectory(workingDir)) {
            return null;
        }

        String hash = computeHash(workingDir.toString());

        // Level 1: 内存缓存
        ProjectContextSnapshot cached = memoryCache.get(hash);
        if (cached != null) {
            return cached;
        }

        // Level 2: SQLite 缓存
        String currentHeadSha = getGitHeadSha(workingDir);
        Optional<ProjectContextRepository.CachedContext> dbCached = repository.findByWorkingDirHash(hash);
        if (dbCached.isPresent() && currentHeadSha != null
                && currentHeadSha.equals(dbCached.get().gitHeadSha())) {
            ProjectContextSnapshot snapshot = dbCached.get().snapshot();
            memoryCache.put(hash, snapshot);
            return snapshot;
        }

        // Level 3: 全量收集
        try {
            ProjectContextSnapshot snapshot = collect(workingDir, currentHeadSha);
            memoryCache.put(hash, snapshot);
            // 持久化到 SQLite
            repository.save(hash, snapshot, currentHeadSha);
            log.info("Project context collected and cached: {}", workingDir);
            return snapshot;
        } catch (Exception e) {
            log.warn("Failed to collect project context for {}: {}", workingDir, e.getMessage());
            return null;
        }
    }

    /**
     * 异步预加载项目上下文（会话启动时调用）。
     */
    @Async
    public void ensureContext(Path workingDir) {
        getContext(workingDir);
    }

    /**
     * 格式化项目上下文为 system prompt 文本段。
     */
    public String formatProjectContext(ProjectContextSnapshot snapshot) {
        if (snapshot == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("<project_context>\n");

        if (snapshot.projectType() != null) {
            sb.append("Project type: ").append(snapshot.projectType()).append("\n");
        }
        if (snapshot.gitRoot() != null) {
            sb.append("Git root: ").append(snapshot.gitRoot()).append("\n");
        }
        if (snapshot.branch() != null) {
            sb.append("Branch: ").append(snapshot.branch()).append("\n");
        }

        if (snapshot.recentCommits() != null && !snapshot.recentCommits().isEmpty()) {
            sb.append("\nRecent commits:\n");
            for (String commit : snapshot.recentCommits()) {
                sb.append("  ").append(commit).append("\n");
            }
        }

        if (snapshot.fileTree() != null && !snapshot.fileTree().isEmpty()) {
            sb.append("\nFile tree (").append(snapshot.fileTree().size()).append(" entries):\n");
            for (String entry : snapshot.fileTree()) {
                sb.append("  ").append(entry).append("\n");
            }
        }

        sb.append("</project_context>");
        return sb.toString();
    }

    // ==================== 内部方法 ====================

    /**
     * 全量收集项目上下文。
     */
    private ProjectContextSnapshot collect(Path workingDir, String gitHeadSha) {
        String gitRoot = runGit(workingDir, "rev-parse", "--show-toplevel");
        String branch = runGit(workingDir, "rev-parse", "--abbrev-ref", "HEAD");

        // 文件树 — git ls-tree
        List<String> fileTree = new ArrayList<>();
        String treeOutput = runGit(workingDir, "ls-tree", "-r", "--name-only", "HEAD");
        if (treeOutput != null) {
            String[] lines = treeOutput.split("\n");
            for (int i = 0; i < Math.min(lines.length, MAX_FILE_TREE_ENTRIES); i++) {
                if (!lines[i].isBlank()) {
                    fileTree.add(lines[i].strip());
                }
            }
        }

        // 最近提交
        List<String> recentCommits = new ArrayList<>();
        String logOutput = runGit(workingDir, "log", "--oneline", "-n",
                String.valueOf(RECENT_COMMITS_COUNT));
        if (logOutput != null) {
            for (String line : logOutput.split("\n")) {
                if (!line.isBlank()) {
                    recentCommits.add(line.strip());
                }
            }
        }

        // 项目类型检测
        String projectType = detectProjectType(workingDir);

        return new ProjectContextSnapshot(
                gitRoot != null ? gitRoot.strip() : workingDir.toString(),
                branch != null ? branch.strip() : null,
                fileTree,
                recentCommits,
                projectType,
                Instant.now()
        );
    }

    /**
     * 检测项目类型 — 基于构建文件存在性。
     */
    private String detectProjectType(Path workingDir) {
        List<String> types = new ArrayList<>();
        if (Files.exists(workingDir.resolve("pom.xml"))) types.add("Maven/Java");
        if (Files.exists(workingDir.resolve("build.gradle")) ||
            Files.exists(workingDir.resolve("build.gradle.kts"))) types.add("Gradle/Java");
        if (Files.exists(workingDir.resolve("package.json"))) types.add("Node.js");
        if (Files.exists(workingDir.resolve("requirements.txt")) ||
            Files.exists(workingDir.resolve("pyproject.toml"))) types.add("Python");
        if (Files.exists(workingDir.resolve("go.mod"))) types.add("Go");
        if (Files.exists(workingDir.resolve("Cargo.toml"))) types.add("Rust");
        if (Files.exists(workingDir.resolve("Gemfile"))) types.add("Ruby");
        return types.isEmpty() ? "Unknown" : String.join(", ", types);
    }

    /**
     * 获取当前 git HEAD SHA。
     */
    private String getGitHeadSha(Path workingDir) {
        String sha = runGit(workingDir, "rev-parse", "HEAD");
        return sha != null ? sha.strip() : null;
    }

    /**
     * 运行 git 命令并返回标准输出。
     */
    private String runGit(Path workingDir, String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(List.of(args));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Git command timed out: git {}", String.join(" ", args));
                return null;
            }

            return process.exitValue() == 0 ? output.toString().stripTrailing() : null;
        } catch (Exception e) {
            log.debug("Git command failed: git {}: {}", String.join(" ", args), e.getMessage());
            return null;
        }
    }

    /**
     * 计算字符串的 SHA-256 哈希。
     */
    private String computeHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // SHA-256 always available in Java
            throw new RuntimeException(e);
        }
    }

    // ==================== DTO ====================

    /**
     * 项目上下文快照 — 不可变数据对象。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectContextSnapshot(
            String gitRoot,
            String branch,
            List<String> fileTree,
            List<String> recentCommits,
            String projectType,
            Instant collectedAt
    ) {}
}
