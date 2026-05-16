package com.aicodeassistant.tool.search;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.service.FileSearchService;
import com.aicodeassistant.service.FileSearchService.FileSearchResult;
import com.aicodeassistant.service.GitService;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SearchStrategyRouter 单元测试 — 策略选择与作用域感知搜索。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SearchStrategyRouter 单元测试")
class SearchStrategyRouterTest {

    @Mock
    private FeatureFlagService featureFlagService;
    @Mock
    private GitService gitService;
    @Mock
    private FileSearchService fileSearchService;

    private SearchStrategyRouter router;

    @BeforeEach
    void setUp() {
        router = new SearchStrategyRouter(featureFlagService, gitService, fileSearchService);
    }

    // ═══════════════ 策略选择 ═══════════════

    @Test
    @DisplayName("Feature Flag 关闭时，返回 DEFAULT 策略")
    void testSelectStrategy_FlagDisabled_ReturnsDefault() {
        // Given
        when(featureFlagService.isEnabled("SEARCH_STRATEGY_ROUTER")).thenReturn(false);
        SearchQuery query = SearchQuery.ofContent("test");
        ToolUseContext context = ToolUseContext.of("/tmp/project", "session-1");

        // When
        SearchStrategyRouter.Strategy strategy = router.selectStrategy(query, context);

        // Then
        assertThat(strategy).isEqualTo(SearchStrategyRouter.Strategy.DEFAULT);
    }

    @Test
    @DisplayName("Feature Flag 开启且有工作目录时，返回 SCOPE_AWARE 策略")
    void testSelectStrategy_FlagEnabled_WithContext_ReturnsScopeAware() {
        // Given
        when(featureFlagService.isEnabled("SEARCH_STRATEGY_ROUTER")).thenReturn(true);
        SearchQuery query = SearchQuery.ofContent("UserService");
        ToolUseContext context = ToolUseContext.of("/tmp/project", "session-1");

        // When
        SearchStrategyRouter.Strategy strategy = router.selectStrategy(query, context);

        // Then
        assertThat(strategy).isEqualTo(SearchStrategyRouter.Strategy.SCOPE_AWARE);
    }

    @Test
    @DisplayName("Feature Flag 开启但无上下文时，返回 DEFAULT 策略")
    void testSelectStrategy_FlagEnabled_NoContext_ReturnsDefault() {
        // Given
        when(featureFlagService.isEnabled("SEARCH_STRATEGY_ROUTER")).thenReturn(true);
        SearchQuery query = SearchQuery.ofContent("test");

        // When
        SearchStrategyRouter.Strategy strategy = router.selectStrategy(query, null);

        // Then
        assertThat(strategy).isEqualTo(SearchStrategyRouter.Strategy.DEFAULT);
    }

    // ═══════════════ 作用域感知搜索 ═══════════════

    @Test
    @DisplayName("scopeAwareSearch — 优先当前目录（本地结果 boost=1.0）")
    void testScopeAwareSearch_PrioritizesLocalDirectory() {
        // Given: 活跃文件在 /project/src/main/Service.java
        ScopeContext scope = new ScopeContext(
                "/project/src/main/Service.java",
                List.of(),
                List.of(),
                "/project"
        );
        SearchQuery query = SearchQuery.ofContent("Service");

        // Mock: 本地目录搜索返回结果
        when(fileSearchService.fuzzySearch(eq("Service"), eq("/project/src/main"), anyInt(), anyInt()))
                .thenReturn(List.of(
                        new FileSearchResult("UserService.java", "UserService.java", "java", 1000, 80.0),
                        new FileSearchResult("OrderService.java", "OrderService.java", "java", 800, 60.0)
                ));

        // When
        ScopedSearchResult result = router.scopeAwareSearch(query, scope);

        // Then
        assertThat(result.usedStrategy()).isEqualTo(SearchStrategyRouter.Strategy.SCOPE_AWARE);
        assertThat(result.matches()).isNotEmpty();
        // 本地结果应该有较高的相关性分数（boost 1.0）
        assertThat(result.matches().getFirst().source()).isEqualTo("local");
    }

    @Test
    @DisplayName("scopeAwareSearch — 包含最近编辑文件目录的结果")
    void testScopeAwareSearch_IncludesRecentFiles() {
        // Given: 有最近编辑的文件
        ScopeContext scope = new ScopeContext(
                null, // 无活跃文件
                List.of("/project/src/test/TestFile.java"),
                List.of(),
                "/project"
        );
        SearchQuery query = SearchQuery.ofContent("Test");

        // Mock: 最近编辑目录搜索
        when(fileSearchService.fuzzySearch(eq("Test"), eq("/project/src/test"), anyInt(), anyInt()))
                .thenReturn(List.of(
                        new FileSearchResult("TestFile.java", "TestFile.java", "java", 500, 70.0)
                ));

        // Mock: 全局搜索补充（结果不足 10 条时触发）
        when(fileSearchService.fuzzySearch(eq("Test"), eq("/project"), anyInt()))
                .thenReturn(List.of());

        // When
        ScopedSearchResult result = router.scopeAwareSearch(query, scope);

        // Then
        assertThat(result.matches()).isNotEmpty();
        assertThat(result.matches().stream().anyMatch(m -> m.source().equals("recent"))).isTrue();
    }

    @Test
    @DisplayName("scopeAwareSearch — 去重合并同一文件的多层搜索结果")
    void testScopeAwareSearch_DeduplicatesResults() {
        // Given: 同一文件出现在本地搜索和最近编辑搜索中
        ScopeContext scope = new ScopeContext(
                "/project/src/main/App.java",
                List.of("/project/src/main/Helper.java"), // 同目录下的最近编辑
                List.of(),
                "/project"
        );
        SearchQuery query = SearchQuery.ofContent("App");

        // Mock: 本地搜索返回 App.java
        when(fileSearchService.fuzzySearch(eq("App"), eq("/project/src/main"), anyInt(), anyInt()))
                .thenReturn(List.of(
                        new FileSearchResult("App.java", "App.java", "java", 1000, 90.0)
                ));
        // 最近编辑目录和本地目录相同 — alreadySearched 应跳过

        // When
        ScopedSearchResult result = router.scopeAwareSearch(query, scope);

        // Then: App.java 不应被重复
        long appCount = result.matches().stream()
                .filter(m -> m.filePath().contains("App.java"))
                .count();
        assertThat(appCount).isEqualTo(1);
    }

    @Test
    @DisplayName("scopeAwareSearch — Git 变更文件匹配查询时加入结果")
    void testScopeAwareSearch_IncludesGitChangedFiles() {
        // Given
        ScopeContext scope = new ScopeContext(
                null,
                List.of(),
                List.of("/project/src/main/ChangedFile.java"),
                "/project"
        );
        SearchQuery query = SearchQuery.ofContent("changed");

        // Mock: 全局补充
        when(fileSearchService.fuzzySearch(eq("changed"), eq("/project"), anyInt()))
                .thenReturn(List.of());

        // When
        ScopedSearchResult result = router.scopeAwareSearch(query, scope);

        // Then: Git 变更文件应该在结果中
        assertThat(result.matches().stream()
                .anyMatch(m -> m.source().equals("git-changed"))).isTrue();
    }
}
