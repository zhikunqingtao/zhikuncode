package com.aicodeassistant.session;

import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.model.SessionSummary;
import com.aicodeassistant.run.RunExecutionRegistry;
import com.aicodeassistant.run.RunTerminationCoordinator;
import com.aicodeassistant.state.AppStateStore;
import com.aicodeassistant.tool.agent.BackgroundAgentTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 服务端会话搜索（§7.5 面板搜索框）— listSessionsPaginated 的 query 参数：
 * 按「会话标题」或「首条用户消息解码后的文本块」做 LIKE 匹配。
 * <p>
 * 使用真实 SQLite 内存库验证 SQL 行为（与 WorkbenchProjectionServiceTest 同模式）。
 */
class SessionManagerSearchTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private SessionManager sessionManager;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new SingleConnectionDataSource(
                DriverManager.getConnection("jdbc:sqlite::memory:"), true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE sessions (
                    id TEXT PRIMARY KEY, title TEXT, model TEXT NOT NULL, working_dir TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'active', total_cost_usd REAL DEFAULT 0.0,
                    metadata_json TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE messages (
                    id TEXT PRIMARY KEY, session_id TEXT NOT NULL, role TEXT NOT NULL,
                    content_json TEXT NOT NULL, created_at TEXT NOT NULL, seq_num INTEGER NOT NULL)
                """);
        sessionManager = new SessionManager(jdbc, objectMapper, mock(SqliteConfig.class),
                mock(AppStateStore.class), mock(HookService.class), mock(SessionSnapshotService.class),
                mock(BackgroundAgentTracker.class), mock(RunExecutionRegistry.class),
                mock(RunTerminationCoordinator.class));
    }

    private void insertSession(String id, String title, String updatedAt) {
        insertSession(id, title, null, updatedAt);
    }

    private void insertSession(String id, String title, String metadataJson, String updatedAt) {
        jdbc.update("INSERT INTO sessions (id, title, model, working_dir, status, metadata_json, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                id, title, "test-model", "/workspace", "active", metadataJson, updatedAt, updatedAt);
    }

    private void insertUserMessage(String id, String sessionId, int seq, String text) throws Exception {
        // 与生产序列化一致：Jackson 默认输出原始 UTF-8，中文不做 Unicode 转义
        String contentJson = objectMapper.writeValueAsString(List.of(Map.of("type", "text", "text", text)));
        jdbc.update("INSERT INTO messages (id, session_id, role, content_json, created_at, seq_num)"
                        + " VALUES (?,?,?,?,?,?)",
                id, sessionId, "user", contentJson, "2026-08-12T00:00:00Z", seq);
    }

    @Test
    void matchesKeywordBeyond80CharGoalPreviewTruncation() throws Exception {
        // 首条用户消息 > 80 字符，关键词位于 80 字符之后：goalPreview 看不到，SQL 全文必须命中
        String text = "a".repeat(120) + " 深夜排查支付回调超时问题";
        insertSession("s1", null, "2026-08-12T01:00:00Z");
        insertUserMessage("m1", "s1", 1, text);
        insertSession("s2", null, "2026-08-12T02:00:00Z");
        insertUserMessage("m2", "s2", 1, "无关的短消息");

        SessionPage page = sessionManager.listSessionsPaginated(true, null, 20, "支付回调");

        assertThat(page.sessions()).extracting(SessionSummary::id).containsExactly("s1");
        // goalPreview 被截断在 80 字符处（不含关键词），证明命中来自 SQL 全文而非预览字段
        assertThat(page.sessions().getFirst().goalPreview()).doesNotContain("支付回调");
    }

    @Test
    void matchesSessionTitleKeyword() {
        insertSession("s1", "修复登录页样式", "2026-08-12T01:00:00Z");
        insertSession("s2", "其他会话", "2026-08-12T02:00:00Z");

        SessionPage page = sessionManager.listSessionsPaginated(true, null, 20, "登录页");

        assertThat(page.sessions()).extracting(SessionSummary::id).containsExactly("s1");
    }

    @Test
    void returnsEmptyWhenKeywordMatchesNothing() throws Exception {
        insertSession("s1", "普通标题", "2026-08-12T01:00:00Z");
        insertUserMessage("m1", "s1", 1, "普通消息内容");

        SessionPage page = sessionManager.listSessionsPaginated(true, null, 20, "绝不存在的关键词xyz");

        assertThat(page.sessions()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void nullOrBlankQueryBehavesLikeUnfilteredListing() throws Exception {
        insertSession("s1", "标题甲", "2026-08-12T01:00:00Z");
        insertUserMessage("m1", "s1", 1, "消息甲");
        insertSession("s2", "标题乙", "2026-08-12T02:00:00Z");

        SessionPage viaOverload = sessionManager.listSessionsPaginated(true, null, 20);
        SessionPage nullQuery = sessionManager.listSessionsPaginated(true, null, 20, null);
        SessionPage blankQuery = sessionManager.listSessionsPaginated(true, null, 20, "   ");

        // 三种调用均返回全部会话，按 updated_at DESC
        assertThat(viaOverload.sessions()).extracting(SessionSummary::id).containsExactly("s2", "s1");
        assertThat(nullQuery.sessions()).extracting(SessionSummary::id).containsExactly("s2", "s1");
        assertThat(blankQuery.sessions()).extracting(SessionSummary::id).containsExactly("s2", "s1");
    }

    @Test
    void matchesChineseKeywordInFirstUserMessage() throws Exception {
        insertSession("s1", null, "2026-08-12T01:00:00Z");
        insertUserMessage("m1", "s1", 1, "帮我分析一下订单超时的原因");

        // 前置验证：content_json 中中文必须是原始 UTF-8 存储，而非 Unicode 转义形态，
        // 否则 LIKE 无法命中中文关键词
        String stored = jdbc.queryForObject(
                "SELECT content_json FROM messages WHERE id = 'm1'", String.class);
        assertThat(stored).contains("订单超时").doesNotContain("\\u");

        SessionPage page = sessionManager.listSessionsPaginated(true, null, 20, "订单超时");

        assertThat(page.sessions()).extracting(SessionSummary::id).containsExactly("s1");
    }

    @Test
    void searchStillExcludesSubagentSessions() {
        insertSession("s1", "登录页修复", "2026-08-12T01:00:00Z");
        insertSession("sub-x", "登录页子代理",
                "{\"type\":\"subagent\",\"parent_session_id\":\"s1\"}", "2026-08-12T02:00:00Z");

        SessionPage page = sessionManager.listSessionsPaginated(true, null, 20, "登录页");

        assertThat(page.sessions()).extracting(SessionSummary::id).containsExactly("s1");
    }

    @Test
    void percentAndUnderscoreInQueryAreEscapedNotWildcards() {
        insertSession("s1", "进度 100% 完成", "2026-08-12T01:00:00Z");
        insertSession("s2", "进度 1000 完成", "2026-08-12T02:00:00Z");

        // "%" 应作为字面量匹配；若被当作通配符，s2 也会命中
        SessionPage page = sessionManager.listSessionsPaginated(true, null, 20, "100%");

        assertThat(page.sessions()).extracting(SessionSummary::id).containsExactly("s1");
    }

    @Test
    void searchAlsoAppliesOnCursorPaginationBranch() {
        insertSession("s1", "关键词目标一", "2026-08-12T01:00:00Z");
        insertSession("s2", "关键词目标二", "2026-08-12T02:00:00Z");
        insertSession("s3", "不相关会话", "2026-08-12T03:00:00Z");

        SessionPage first = sessionManager.listSessionsPaginated(true, null, 1, "关键词");
        assertThat(first.sessions()).extracting(SessionSummary::id).containsExactly("s2");
        assertThat(first.hasMore()).isTrue();

        // 游标翻页分支（beforeId 非空）同样应应用搜索条件：只能看到更早的 s1，看不到 s3
        SessionPage second = sessionManager.listSessionsPaginated(false, first.oldestId(), 10, "关键词");
        assertThat(second.sessions()).extracting(SessionSummary::id).containsExactly("s1");
    }
    @Test
    void searchesDecodedTextWithoutMatchingJsonKeys() throws Exception {
        insertSession("s1", "ordinary", "2026-08-12T01:00:00Z");
        insertUserMessage("m1", "s1", 1, "Inspect C:\\temp and \"quoted\" 100% a_b");
        for (String query : List.of("C:\\temp", "\"quoted\"", "100%", "a_b")) {
            assertThat(sessionManager.listSessionsPaginated(true, null, 10, query).sessions())
                    .extracting(SessionSummary::id).containsExactly("s1");
        }
        for (String query : List.of("type", "text", "1000", "axb")) {
            assertThat(sessionManager.listSessionsPaginated(true, null, 10, query).sessions()).isEmpty();
        }
    }

    @Test
    void searchesAllTextBlocksBeforePaginationButNotOtherContentOrLaterMessages() throws Exception {
        for (int n = 1; n <= 3; n++) {
            insertSession("s" + n, "ordinary", "2026-08-12T0" + n + ":00:00Z");
            insertUserMessage("m" + n, "s" + n, 1, "unrelated");
        }
        String blocks = objectMapper.writeValueAsString(List.of(
                Map.of("type", "text", "text", "first"),
                Map.of("type", "image", "data", "hidden-image"),
                Map.of("type", "text", "text", "needle C:\\temp")));
        jdbc.update("UPDATE messages SET content_json = ? WHERE session_id IN ('s1', 's2')", blocks);
        insertUserMessage("later", "s3", 2, "needle");
        var first = sessionManager.listSessionsPaginated(true, null, 1, "C:\\temp");
        assertThat(first.sessions()).extracting(SessionSummary::id).containsExactly("s2");
        assertThat(first.hasMore()).isTrue();
        var second = sessionManager.listSessionsPaginated(false, first.oldestId(), 1, "C:\\temp");
        assertThat(second.sessions()).extracting(SessionSummary::id).containsExactly("s1");
        assertThat(second.hasMore()).isFalse();
        assertThat(sessionManager.listSessionsPaginated(true, null, 10, "hidden-image").sessions()).isEmpty();
        assertThat(sessionManager.listSessionsPaginated(true, null, 10, "needle").sessions())
                .extracting(SessionSummary::id).containsExactly("s2", "s1");
    }

    @Test
    void malformedAndNonArrayHistoryDoesNotBreakSearchOrTitleMatching() throws Exception {
        int n = 0;
        for (String content : List.of("{broken", "null", "123", "\"needle\"", "{}",
                "[\"needle\",null,42,{\"type\":\"text\",\"text\":123}]")) {
            String id = "bad" + n++;
            insertSession(id, "title-match", "2026-08-12T01:00:00Z");
            insertUserMessage(id, id, 1, "placeholder");
            jdbc.update("UPDATE messages SET content_json = ? WHERE id = ?", content, id);
        }
        assertThat(sessionManager.listSessionsPaginated(true, null, 20, "needle").sessions()).isEmpty();
        assertThat(sessionManager.listSessionsPaginated(true, null, 20, "title-match").sessions()).hasSize(n);
    }

}
