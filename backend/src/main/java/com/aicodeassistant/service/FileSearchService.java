package com.aicodeassistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 文件模糊搜索服务 — 支持 @文件附件功能。
 * <p>
 * 技术栈适配说明:
 * 1. Files.walk 不使用 FOLLOW_LINKS — 符号链接循环会导致无限递归
 * 2. Files.walk 可能抛出 UncheckedIOException，在 stream 内部处理
 * 3. 原版 TS 使用 ripgrep (rg --files)，Java 版用 Files.walk 替代
 *
 * @see <a href="SPEC §4.3">@文件附件功能</a>
 */
@Service
public class FileSearchService {

    private static final Logger log = LoggerFactory.getLogger(FileSearchService.class);

    private static final Set<String> IGNORED_DIRS = Set.of(
            "node_modules", ".git", "target", "dist", "build", ".idea", "__pycache__",
            ".gradle", ".mvn", ".next", ".nuxt", "coverage", ".vscode"
    );

    public record FileSearchResult(
            String path,
            String name,
            String type,
            long size,
            double score
    ) {}

    /**
     * 模糊搜索文件。
     *
     * @param query    搜索查询
     * @param rootDir  根目录
     * @param limit    最大结果数
     * @param maxDepth 遍历深度
     * @return 匹配结果列表（按分数降序）
     */
    public List<FileSearchResult> fuzzySearch(String query, String rootDir, int limit, int maxDepth) {
        if (query == null || query.isBlank()) return List.of();
        Path root = Path.of(rootDir);
        if (!Files.isDirectory(root)) return List.of();

        try (Stream<Path> paths = Files.walk(root, maxDepth)) {
            return paths
                    .filter(p -> !isIgnored(p, root))
                    .map(p -> {
                        try {
                            String relative = root.relativize(p).toString();
                            if (relative.isEmpty()) return null;
                            double score = fuzzyScore(query, relative);
                            return new FileSearchResult(
                                    relative,
                                    p.getFileName().toString(),
                                    Files.isDirectory(p) ? "directory" : "file",
                                    safeSize(p),
                                    score);
                        } catch (UncheckedIOException e) {
                            return null;
                        }
                    })
                    .filter(r -> r != null && r.score() > 0)
                    .sorted(Comparator.comparingDouble(FileSearchResult::score).reversed())
                    .limit(limit)
                    .toList();
        } catch (IOException e) {
            log.warn("File search failed for root={}: {}", rootDir, e.getMessage());
            return List.of();
        }
    }

    public List<FileSearchResult> fuzzySearch(String query, String rootDir, int limit) {
        return fuzzySearch(query, rootDir, limit, 12);
    }

    private long safeSize(Path p) {
        try { return Files.isRegularFile(p) ? Files.size(p) : 0L; }
        catch (IOException e) { return 0L; }
    }

    private double fuzzyScore(String query, String target) {
        String lq = query.toLowerCase(), lt = target.toLowerCase();
        int qi = 0, score = 0, consecutive = 0;
        for (int ti = 0; ti < lt.length() && qi < lq.length(); ti++) {
            if (lt.charAt(ti) == lq.charAt(qi)) {
                qi++;
                consecutive++;
                score += consecutive * 2;
                if (ti == 0 || target.charAt(ti - 1) == '/' || target.charAt(ti - 1) == '.')
                    score += 5;
            } else {
                consecutive = 0;
            }
        }
        return qi == lq.length() ? score : 0;
    }

    private boolean isIgnored(Path path, Path root) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            if (IGNORED_DIRS.contains(part.toString())) return true;
        }
        return false;
    }
}
