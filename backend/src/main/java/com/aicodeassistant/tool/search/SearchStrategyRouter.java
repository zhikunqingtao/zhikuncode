package com.aicodeassistant.tool.search;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.service.FileSearchService;
import com.aicodeassistant.service.FileSearchService.FileSearchResult;
import com.aicodeassistant.service.GitService;
import com.aicodeassistant.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 搜索策略路由器 — 根据上下文智能选择搜索策略。
 * <p>
 * 支持两种策略:
 * <ul>
 *   <li>DEFAULT: 传统全局搜索</li>
 *   <li>SCOPE_AWARE: 作用域感知搜索，按相关性分层</li>
 * </ul>
 * <p>
 * SCOPE_AWARE 优先级:
 * 当前目录 (boost 1.0) → 最近编辑目录 (boost 0.8) → Git 变更文件 (boost 0.6) → 全局补充
 */
@Service
public class SearchStrategyRouter {

    private static final Logger log = LoggerFactory.getLogger(SearchStrategyRouter.class);

    private static final String FEATURE_FLAG_KEY = "SEARCH_STRATEGY_ROUTER";
    private static final int MAX_RESULTS = 20;
    private static final int MIN_RESULTS_BEFORE_GLOBAL = 10;

    public enum Strategy { DEFAULT, SCOPE_AWARE }

    private final FeatureFlagService featureFlagService;
    private final GitService gitService;
    private final FileSearchService fileSearchService;

    public SearchStrategyRouter(FeatureFlagService featureFlagService,
                                GitService gitService,
                                FileSearchService fileSearchService) {
        this.featureFlagService = featureFlagService;
        this.gitService = gitService;
        this.fileSearchService = fileSearchService;
    }

    /**
     * 根据上下文选择搜索策略。
     *
     * @param query   搜索查询
     * @param context 工具使用上下文
     * @return 选择的策略
     */
    public Strategy selectStrategy(SearchQuery query, ToolUseContext context) {
        if (!featureFlagService.isEnabled(FEATURE_FLAG_KEY)) {
            return Strategy.DEFAULT;
        }
        // 有活跃文件路径或工作目录时，使用 SCOPE_AWARE
        if (context != null && context.workingDirectory() != null) {
            return Strategy.SCOPE_AWARE;
        }
        return Strategy.DEFAULT;
    }

    /**
     * 作用域感知搜索：按相关性分层搜索。
     * <p>
     * 优先级: 当前目录 → 最近编辑 → Git 变更 → 全局
     *
     * @param query 搜索查询
     * @param scope 作用域上下文
     * @return 搜索结果
     */
    public ScopedSearchResult scopeAwareSearch(SearchQuery query, ScopeContext scope) {
        List<SearchMatch> allMatches = new ArrayList<>();

        // Layer 1: 当前文件所在目录 (boost 1.0)
        if (scope.activeFilePath() != null && !scope.activeFilePath().isBlank()) {
            Path parent = Path.of(scope.activeFilePath()).getParent();
            if (parent != null) {
                String dir = parent.toString();
                List<SearchMatch> localMatches = searchInDirectory(query, dir, 1.0);
                allMatches.addAll(localMatches);
                log.debug("Layer 1 (local dir): {} matches in {}", localMatches.size(), dir);
            }
        }

        // Layer 2: 最近编辑的文件目录 (boost 0.8)
        if (scope.recentFiles() != null && !scope.recentFiles().isEmpty()) {
            Set<String> dirs = scope.recentFiles().stream()
                    .map(f -> Path.of(f).getParent())
                    .filter(Objects::nonNull)
                    .map(Path::toString)
                    .collect(Collectors.toSet());
            for (String dir : dirs) {
                if (!alreadySearched(dir, allMatches)) {
                    List<SearchMatch> recentMatches = searchInDirectory(query, dir, 0.8);
                    allMatches.addAll(recentMatches);
                }
            }
            log.debug("Layer 2 (recent dirs): cumulative {} matches", allMatches.size());
        }

        // Layer 3: Git 变更文件 (boost 0.6)
        if (scope.gitChangedFiles() != null && !scope.gitChangedFiles().isEmpty()) {
            for (String file : scope.gitChangedFiles()) {
                if (matchesQuery(file, query)) {
                    allMatches.add(new SearchMatch(file, 0.6, "git-changed"));
                }
            }
            log.debug("Layer 3 (git changed): cumulative {} matches", allMatches.size());
        }

        // Layer 4: 全局搜索（补充结果）
        if (allMatches.size() < MIN_RESULTS_BEFORE_GLOBAL && scope.workingDirectory() != null) {
            List<SearchMatch> globalMatches = searchGlobal(query, scope.workingDirectory());
            allMatches.addAll(globalMatches);
            log.debug("Layer 4 (global): cumulative {} matches", allMatches.size());
        }

        // 去重 + 按 relevance 排序
        List<SearchMatch> deduplicated = deduplicateAndSort(allMatches);

        return new ScopedSearchResult(deduplicated, Strategy.SCOPE_AWARE, deduplicated.size());
    }

    // ═══════════════════════════════════════════════════════════════
    // 私有辅助方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 在指定目录内搜索，结果附加 boost 权重。
     */
    private List<SearchMatch> searchInDirectory(SearchQuery query, String dir, double boost) {
        try {
            List<FileSearchResult> results = fileSearchService.fuzzySearch(
                    query.pattern(), dir, MAX_RESULTS, 5);
            return results.stream()
                    .map(r -> new SearchMatch(
                            Path.of(dir, r.path()).toString(),
                            normalizeScore(r.score()) * boost,
                            boost >= 1.0 ? "local" : "recent"))
                    .toList();
        } catch (Exception e) {
            log.warn("searchInDirectory failed for dir={}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    /**
     * 全局搜索 — 工作目录为根。
     */
    private List<SearchMatch> searchGlobal(SearchQuery query, String workingDir) {
        try {
            List<FileSearchResult> results = fileSearchService.fuzzySearch(
                    query.pattern(), workingDir, MAX_RESULTS);
            return results.stream()
                    .map(r -> new SearchMatch(
                            Path.of(workingDir, r.path()).toString(),
                            normalizeScore(r.score()) * 0.4,
                            "global"))
                    .toList();
        } catch (Exception e) {
            log.warn("searchGlobal failed for workingDir={}: {}", workingDir, e.getMessage());
            return List.of();
        }
    }

    /**
     * 判断文件路径是否匹配查询。
     */
    private boolean matchesQuery(String filePath, SearchQuery query) {
        if (query.pattern() == null || query.pattern().isBlank()) return false;
        String lowerPath = filePath.toLowerCase();
        String lowerQuery = query.pattern().toLowerCase();
        return lowerPath.contains(lowerQuery);
    }

    /**
     * 检查目录是否已被搜索过（存在来自该目录的结果）。
     */
    private boolean alreadySearched(String dir, List<SearchMatch> matches) {
        return matches.stream().anyMatch(m -> m.filePath().startsWith(dir));
    }

    /**
     * 去重并按相关性降序排序。
     */
    private List<SearchMatch> deduplicateAndSort(List<SearchMatch> matches) {
        // 按 filePath 去重，保留最高 relevance 的条目
        Map<String, SearchMatch> best = new LinkedHashMap<>();
        for (SearchMatch match : matches) {
            best.merge(match.filePath(), match,
                    (existing, incoming) -> existing.relevance() >= incoming.relevance()
                            ? existing : incoming);
        }
        return best.values().stream()
                .sorted(Comparator.comparingDouble(SearchMatch::relevance).reversed())
                .limit(MAX_RESULTS)
                .toList();
    }

    /**
     * 将 fuzzySearch 的原始分数归一化到 0-1 范围。
     */
    private double normalizeScore(double rawScore) {
        // fuzzyScore 理论最大值较难预估，使用 sigmoid 归一化
        return rawScore / (rawScore + 50.0);
    }
}
