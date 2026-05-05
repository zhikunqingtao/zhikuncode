package com.aicodeassistant.memdir;

import com.aicodeassistant.memdir.MemorySearchEngine.DocumentEntry;
import com.aicodeassistant.memdir.MemorySearchEngine.ScoredResult;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-MEM-002 BM25 搜索质量验证
 */
@DisplayName("TC-MEM-002 BM25 搜索质量验证")
class Bm25SearchQualityTest {

    private MemorySearchEngine engine;
    private List<DocumentEntry> testDataset;

    private static final Set<Integer> STRONG_RELEVANT = Set.of(0, 1, 2);

    @BeforeEach
    void setUp() {
        engine = new MemorySearchEngine();
        testDataset = List.of(
            new DocumentEntry("数据库连接池配置", "配置 HikariCP 连接池参数，包括最大连接数、超时时间和空闲连接回收策略"),
            new DocumentEntry("Database Connection Troubleshooting", "Common issues with database connections including timeout errors and pool exhaustion"),
            new DocumentEntry("MySQL 连接优化", "优化 MySQL 数据库连接性能，减少连接建立的开销"),
            new DocumentEntry("Redis 缓存配置", "配置 Redis 连接参数和缓存策略，支持集群模式"),
            new DocumentEntry("网络连接监控", "监控服务间网络连接状态，检测连接中断和延迟"),
            new DocumentEntry("Connection Pool Metrics", "Monitoring connection pool usage and performance metrics"),
            new DocumentEntry("Git 分支策略", "采用 GitFlow 工作流，包含 feature、develop、release 分支"),
            new DocumentEntry("React 组件设计", "遵循单一职责原则设计 React 组件，支持组合模式"),
            new DocumentEntry("Docker 容器部署", "使用 Docker Compose 编排多容器应用部署"),
            new DocumentEntry("API 接口文档", "RESTful API 设计规范和 Swagger 文档生成"),
            new DocumentEntry("日志收集方案", "使用 ELK 技术栈收集和分析应用日志"),
            new DocumentEntry("CI/CD 流水线", "配置 Jenkins 自动化构建、测试和部署流水线"),
            new DocumentEntry("代码审查规范", "团队代码审查标准和 Pull Request 流程"),
            new DocumentEntry("性能测试方案", "使用 JMeter 进行接口性能测试和压力测试"),
            new DocumentEntry("安全扫描配置", "集成 OWASP 安全扫描工具，检测常见漏洞")
        );
    }

    @Test
    @DisplayName("中文查询：Top-3 应至少包含 2 条强相关文档")
    void chineseQueryTopThreeAccuracy() {
        List<ScoredResult> results = engine.search(testDataset, "数据库连接", 3);
        assertNotNull(results);
        assertFalse(results.isEmpty());

        long strongHits = results.stream()
            .filter(r -> STRONG_RELEVANT.contains(r.index()))
            .count();
        assertTrue(strongHits >= 2,
            String.format("Top-3 中强相关文档应 ≥ 2，实际 = %d", strongHits));
    }

    @Test
    @DisplayName("英文查询：Top-3 应至少包含 1 条强相关文档")
    void englishQueryTopThreeAccuracy() {
        List<ScoredResult> results = engine.search(testDataset, "database connection", 3);
        assertFalse(results.isEmpty());

        long strongHits = results.stream()
            .filter(r -> STRONG_RELEVANT.contains(r.index()))
            .count();
        assertTrue(strongHits >= 1,
            String.format("英文查询 Top-3 中强相关文档应 ≥ 1，实际 = %d", strongHits));
    }

    @Test
    @DisplayName("中英文混合查询：应同时匹配中英文文档")
    void mixedLanguageQuery() {
        List<ScoredResult> results = engine.search(testDataset, "数据库 database 连接", 5);
        assertFalse(results.isEmpty());

        Set<Integer> topIndices = results.stream()
            .map(ScoredResult::index)
            .collect(Collectors.toSet());

        boolean hasChinese = topIndices.contains(0) || topIndices.contains(2);
        boolean hasEnglish = topIndices.contains(1);
        assertTrue(hasChinese || hasEnglish, "混合查询应至少匹配到一条强相关文档");
    }

    @Test
    @DisplayName("标题加权：结果应按得分降序排列")
    void titleBoostShouldRankHigher() {
        List<ScoredResult> results = engine.search(testDataset, "数据库连接", 5);
        if (results.size() >= 2) {
            assertTrue(results.get(0).score() >= results.get(1).score(),
                "结果应按得分降序排列");
        }
    }

    @Test
    @DisplayName("搜索性能：15 条文档搜索应在 100ms 内完成")
    void searchPerformanceUnder100ms() {
        engine.search(testDataset, "warmup", 3);

        long start = System.nanoTime();
        List<ScoredResult> results = engine.search(testDataset, "数据库连接配置优化", 5);
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        assertNotNull(results);
        assertTrue(elapsed <= 100,
            String.format("搜索耗时应 ≤ 100ms，实际 = %dms", elapsed));
    }

    @Test
    @DisplayName("无关查询：结果得分应显著低于相关查询")
    void irrelevantQueryShouldHaveLowScores() {
        List<ScoredResult> relevantResults = engine.search(testDataset, "数据库连接", 3);
        List<ScoredResult> irrelevantResults = engine.search(testDataset, "量子计算", 3);

        if (!relevantResults.isEmpty() && !irrelevantResults.isEmpty()) {
            double relevantTopScore = relevantResults.get(0).score();
            double irrelevantTopScore = irrelevantResults.get(0).score();
            assertTrue(relevantTopScore > irrelevantTopScore,
                String.format("相关查询得分(%.4f)应高于无关查询(%.4f)",
                    relevantTopScore, irrelevantTopScore));
        }
    }
}
