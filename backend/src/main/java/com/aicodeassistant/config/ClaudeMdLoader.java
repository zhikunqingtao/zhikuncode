package com.aicodeassistant.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CLAUDE.md 配置文件加载器 — 4 层配置加载。
 * <p>
 * 加载顺序（优先级从低到高）:
 * <ol>
 *     <li>用户级: ~/.claude/CLAUDE.md</li>
 *     <li>项目级: {cwd}/CLAUDE.md</li>
 *     <li>项目级: {cwd}/.claude/CLAUDE.md</li>
 *     <li>父目录向上遍历的 CLAUDE.md</li>
 * </ol>
 * <p>
 * 所有层的内容合并后注入系统提示词的 memory 段。
 *
 * @see <a href="SPEC section 3.1.5b.4a">记忆系统提示段</a>
 */
@Service
public class ClaudeMdLoader {

    private static final Logger log = LoggerFactory.getLogger(ClaudeMdLoader.class);

    private static final String CLAUDE_MD = "CLAUDE.md";
    private static final String CLAUDE_DIR = ".claude";

    /** 缓存: 工作目录 → 合并后的配置内容 */
    private final ConcurrentHashMap<Path, CachedConfig> cache = new ConcurrentHashMap<>();

    /** 缓存过期时间（毫秒） */
    private static final long CACHE_TTL_MS = 60_000; // 1 分钟

    /**
     * 加载并合并所有 CLAUDE.md 配置内容。
     *
     * @param workingDirectory 当前工作目录
     * @return 合并后的配置内容列表
     */
    public List<ClaudeMdSection> loadAll(Path workingDirectory) {
        if (workingDirectory == null) {
            return loadUserLevel();
        }

        Path normalizedCwd = workingDirectory.toAbsolutePath().normalize();

        // 检查缓存
        CachedConfig cached = cache.get(normalizedCwd);
        if (cached != null && !cached.isExpired()) {
            return cached.sections();
        }

        List<ClaudeMdSection> sections = new ArrayList<>();

        // Layer 1: 用户级 ~/.claude/CLAUDE.md
        sections.addAll(loadUserLevel());

        // Layer 2: 项目级 {cwd}/CLAUDE.md
        loadFile(normalizedCwd.resolve(CLAUDE_MD), "project")
                .ifPresent(sections::add);

        // Layer 3: 项目级 {cwd}/.claude/CLAUDE.md
        loadFile(normalizedCwd.resolve(CLAUDE_DIR).resolve(CLAUDE_MD), "project-local")
                .ifPresent(sections::add);

        // Layer 4: 父目录向上遍历
        Path parent = normalizedCwd.getParent();
        int maxDepth = 5; // 最多向上遍历 5 层
        int depth = 0;
        while (parent != null && depth < maxDepth) {
            loadFile(parent.resolve(CLAUDE_MD), "parent-" + depth)
                    .ifPresent(sections::add);
            parent = parent.getParent();
            depth++;
        }

        // 更新缓存
        cache.put(normalizedCwd, new CachedConfig(sections, System.currentTimeMillis()));

        log.debug("Loaded {} CLAUDE.md sections for {}", sections.size(), normalizedCwd);
        return sections;
    }

    /**
     * 加载合并后的纯文本内容（用于注入系统提示词）。
     */
    public String loadMergedContent(Path workingDirectory) {
        List<ClaudeMdSection> sections = loadAll(workingDirectory);
        if (sections.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (ClaudeMdSection section : sections) {
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

    private List<ClaudeMdSection> loadUserLevel() {
        List<ClaudeMdSection> sections = new ArrayList<>();
        Path userHome = Path.of(System.getProperty("user.home"));
        loadFile(userHome.resolve(CLAUDE_DIR).resolve(CLAUDE_MD), "user")
                .ifPresent(sections::add);
        return sections;
    }

    private java.util.Optional<ClaudeMdSection> loadFile(Path path, String label) {
        try {
            if (Files.exists(path) && Files.isRegularFile(path)) {
                String content = Files.readString(path);
                if (!content.isBlank()) {
                    log.debug("Loaded CLAUDE.md: {} ({})", path, label);
                    return java.util.Optional.of(new ClaudeMdSection(label, path.toString(), content.trim()));
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read CLAUDE.md at {}: {}", path, e.getMessage());
        }
        return java.util.Optional.empty();
    }

    // ===== 数据类型 =====

    /**
     * CLAUDE.md 配置段。
     */
    public record ClaudeMdSection(
            String label,
            String filePath,
            String content
    ) {}

    /**
     * 缓存条目。
     */
    private record CachedConfig(
            List<ClaudeMdSection> sections,
            long timestamp
    ) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }
}
