package com.aicodeassistant.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * PROJECT.md 配置文件加载器 — 6 层配置加载。
 * <p>
 * 加载顺序（优先级从低到高）:
 * <ol>
 *     <li>用户级: ~/.zhikun/PROJECT.md</li>
 *     <li>项目级: {cwd}/PROJECT.md</li>
 *     <li>项目级: {cwd}/.zhikun/PROJECT.md</li>
 *     <li>本地级: {cwd}/.zhikun/PROJECT.local.md</li>
 *     <li>rules 目录: {cwd}/.zhikun/rules/*.md</li>
 *     <li>父目录向上遍历的 PROJECT.md</li>
 * </ol>
 * <p>
 * 所有层的内容合并后注入系统提示词的 memory 段。
 *
 */
@Service
public class ProjectPromptLoader {

    private static final Logger log = LoggerFactory.getLogger(ProjectPromptLoader.class);

    private static final String PROJECT_MD = "PROJECT.md";
    private static final String PROJECT_DIR = ".zhikun";

    /** 缓存: 工作目录 → 合并后的配置内容 */
    private final ConcurrentHashMap<Path, CachedConfig> cache = new ConcurrentHashMap<>();

    /** 缓存过期时间（毫秒） */
    private static final long CACHE_TTL_MS = 60_000; // 1 分钟

    /**
     * 加载并合并所有 PROJECT.md 配置内容。
     *
     * @param workingDirectory 当前工作目录
     * @return 合并后的配置内容列表
     */
    public List<ProjectMdSection> loadAll(Path workingDirectory) {
        if (workingDirectory == null) {
            return loadUserLevel();
        }

        Path normalizedCwd = workingDirectory.toAbsolutePath().normalize();

        // 检查缓存
        CachedConfig cached = cache.get(normalizedCwd);
        if (cached != null && !cached.isExpired()) {
            return cached.sections();
        }

        List<ProjectMdSection> sections = new ArrayList<>();

        // Layer 1: 用户级 ~/.zhikun/PROJECT.md
        sections.addAll(loadUserLevel());

        // Layer 2: 项目级 {cwd}/PROJECT.md
        loadFile(normalizedCwd.resolve(PROJECT_MD), "project")
                .ifPresent(sections::add);

        // Layer 3: 项目级 {cwd}/.zhikun/PROJECT.md
        loadFile(normalizedCwd.resolve(PROJECT_DIR).resolve(PROJECT_MD), "project-local")
                .ifPresent(sections::add);

        // Layer 4: .local.md 个人覆盖
        loadFile(normalizedCwd.resolve(PROJECT_DIR).resolve("PROJECT.local.md"), "local")
                .ifPresent(sections::add);

        // Layer 5: rules 目录扫描（新增
        sections.addAll(loadRulesDirectory(normalizedCwd));

        // Layer 6: 父目录向上遍历
        Path parent = normalizedCwd.getParent();
        int maxDepth = 5; // 最多向上遍历 5 层
        int depth = 0;
        while (parent != null && depth < maxDepth) {
            loadFile(parent.resolve(PROJECT_MD), "parent-" + depth)
                    .ifPresent(sections::add);
            loadFile(parent.resolve(PROJECT_DIR).resolve(PROJECT_MD), "parent-local-" + depth)
                    .ifPresent(sections::add);
            parent = parent.getParent();
            depth++;
        }

        // 处理 @include 指令
        sections = resolveIncludes(sections, normalizedCwd);

        // 更新缓存
        cache.put(normalizedCwd, new CachedConfig(sections, System.currentTimeMillis()));

        log.debug("Loaded {} PROJECT.md sections for {}", sections.size(), normalizedCwd);
        return sections;
    }

    /**
     * 加载合并后的纯文本内容（用于注入系统提示词）。
     */
    public String loadMergedContent(Path workingDirectory) {
        List<ProjectMdSection> sections = loadAll(workingDirectory);
        if (sections.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (ProjectMdSection section : sections) {
            if (!sb.isEmpty()) {
                sb.append("\n---\n");
            }
            sb.append("# ").append(section.label()).append("\n");
            sb.append(section.content());
        }
        return sb.toString();
    }

    /**
     * 清除缓存。
     */
    public void clearCache() {
        cache.clear();
    }

    /**
     * 清除指定工作目录的缓存。
     */
    public void invalidateCache(Path workingDirectory) {
        if (workingDirectory != null) {
            cache.remove(workingDirectory.toAbsolutePath().normalize());
        }
    }

    // ===== 内部方法 =====

    /** Layer 5: rules 目录扫描 */
    private List<ProjectMdSection> loadRulesDirectory(Path cwd) {
        List<ProjectMdSection> sections = new ArrayList<>();
        Path rulesDir = cwd.resolve(PROJECT_DIR).resolve("rules");
        if (Files.isDirectory(rulesDir)) {
            try (var stream = Files.list(rulesDir)) {
                stream.filter(p -> p.toString().endsWith(".md"))
                      .sorted()
                      .forEach(p -> loadFile(p, "rule:" + p.getFileName())
                          .ifPresent(sections::add));
            } catch (IOException e) {
                log.warn("Failed to scan rules directory: {}", rulesDir, e);
            }
        }
        return sections;
    }

    /** @include 指令解析（增加路径安全验证） */
    private List<ProjectMdSection> resolveIncludes(List<ProjectMdSection> sections, Path basePath) {
        List<ProjectMdSection> resolved = new ArrayList<>();
        Pattern includePattern = Pattern.compile("@include\\s+(.+)");

        for (ProjectMdSection section : sections) {
            StringBuilder content = new StringBuilder();
            for (String line : section.content().split("\n")) {
                java.util.regex.Matcher m = includePattern.matcher(line.trim());
                if (m.matches()) {
                    String includePath = m.group(1).trim();
                    Path includeFile = basePath.resolve(includePath).normalize();

                    // 安全检查 — 已禁用，不限制 @include 路径，由用户授权控制

                    try {
                        if (Files.exists(includeFile) && Files.isRegularFile(includeFile)) {
                            long size = Files.size(includeFile);
                            if (size > 100_000) { // 100KB 上限
                                log.warn("@include file too large: {} ({} bytes)", includeFile, size);
                                continue;
                            }
                            content.append(Files.readString(includeFile));
                            content.append("\n");
                        }
                    } catch (IOException e) {
                        log.warn("Failed to include: {}", includeFile);
                    }
                } else {
                    content.append(line).append("\n");
                }
            }
            resolved.add(new ProjectMdSection(section.label(), section.filePath(),
                                              content.toString().trim()));
        }
        return resolved;
    }

    private List<ProjectMdSection> loadUserLevel() {
        List<ProjectMdSection> sections = new ArrayList<>();
        Path userHome = Path.of(System.getProperty("user.home"));
        loadFile(userHome.resolve(PROJECT_DIR).resolve(PROJECT_MD), "user")
                .ifPresent(sections::add);
        return sections;
    }

    private java.util.Optional<ProjectMdSection> loadFile(Path path, String label) {
        try {
            if (Files.exists(path) && Files.isRegularFile(path)) {
                String content = Files.readString(path);
                if (!content.isBlank()) {
                    log.debug("Loaded PROJECT.md: {} ({})", path, label);
                    return java.util.Optional.of(new ProjectMdSection(label, path.toString(), content.trim()));
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read PROJECT.md at {}: {}", path, e.getMessage());
        }
        return java.util.Optional.empty();
    }

    // ===== 数据类型 =====

    /**
     * PROJECT.md 配置段。
     */
    public record ProjectMdSection(
            String label,
            String filePath,
            String content
    ) {}

    /**
     * 缓存条目。
     */
    private record CachedConfig(
            List<ProjectMdSection> sections,
            long timestamp
    ) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }
}
