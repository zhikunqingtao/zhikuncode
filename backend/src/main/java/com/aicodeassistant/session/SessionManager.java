package com.aicodeassistant.session;

import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.SessionStatus;
import com.aicodeassistant.model.SessionSummary;
import com.aicodeassistant.model.Usage;
import com.aicodeassistant.state.AppStateStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话管理器 — 会话的完整生命周期管理。
 * <p>
 * 功能: 创建/恢复/列表/分页/删除会话，消息持久化。
 * 并发安全: 写入通过 SqliteConfig.executeWrite() 串行化。
 * 持久化: SQLite data.db (§7.1-§7.2)，不使用 JSONL。
 *
 * @see <a href="SPEC §3.6">会话持久化</a>
 */
@Service
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SqliteConfig sqliteConfig;
    private final AppStateStore appStateStore;

    public SessionManager(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbcTemplate,
                          ObjectMapper objectMapper,
                          SqliteConfig sqliteConfig,
                          AppStateStore appStateStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.sqliteConfig = sqliteConfig;
        this.appStateStore = appStateStore;
    }

    // ───── RowMapper ─────

    private final RowMapper<SessionSummary> summaryMapper = (rs, rowNum) ->
            new SessionSummary(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("model"),
                    rs.getString("working_dir"),
                    rs.getInt("message_count"),
                    rs.getDouble("total_cost_usd"),
                    Instant.parse(rs.getString("created_at")),
                    Instant.parse(rs.getString("updated_at"))
            );

    // ───── 创建会话 ─────

    /**
     * 创建新会话 — 返回会话 ID。
     */
    public String createSession(String model, String workingDir) {
        String sessionId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        Path dbPath = Path.of(workingDir).resolve(".ai-code-assistant/data.db");
        sqliteConfig.executeWriteVoid(dbPath, () ->
                jdbcTemplate.update(
                        """
                        INSERT INTO sessions (id, model, working_dir, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        sessionId, model, workingDir,
                        SessionStatus.ACTIVE.name().toLowerCase(), now, now
                )
        );

        log.info("Session created: {} (model={})", sessionId, model);

        // 更新 AppState
        appStateStore.setState(state ->
                state.withSession(s -> s.withSessionId(sessionId).withCurrentModel(model)
                        .withWorkingDirectory(workingDir))
        );

        return sessionId;
    }

    // ───── 加载会话 ─────

    /**
     * 加载完整会话数据（含消息历史）。
     */
    public Optional<SessionData> loadSession(String sessionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM sessions WHERE id = ?", sessionId
        );
        if (rows.isEmpty()) return Optional.empty();

        Map<String, Object> row = rows.getFirst();

        // 加载消息
        List<Map<String, Object>> msgRows = jdbcTemplate.queryForList(
                "SELECT * FROM messages WHERE session_id = ? ORDER BY seq_num ASC",
                sessionId
        );

        List<Message> messages = msgRows.stream()
                .map(this::mapRowToMessage)
                .flatMap(Optional::stream)
                .toList();

        // 解析 metadata_json
        Map<String, Object> config = parseJsonMap((String) row.get("metadata_json"));

        Usage usage = new Usage(
                toInt(row.get("total_input_tokens")),
                toInt(row.get("total_output_tokens")),
                toInt(row.get("total_cache_read")),
                toInt(row.get("total_cache_create"))
        );

        return Optional.of(new SessionData(
                (String) row.get("id"),
                (String) row.get("model"),
                (String) row.get("working_dir"),
                (String) row.get("title"),
                (String) row.get("status"),
                messages,
                config,
                usage,
                toDouble(row.get("total_cost_usd")),
                (String) row.get("summary"),
                Instant.parse((String) row.get("created_at")),
                Instant.parse((String) row.get("updated_at"))
        ));
    }

    // ───── 保存会话 ─────

    /**
     * 保存/更新会话元数据。
     */
    public void saveSession(SessionData data) {
        String now = Instant.now().toString();
        String metadataJson = toJsonString(data.config());

        Path dbPath = Path.of(data.workingDir()).resolve(".ai-code-assistant/data.db");
        sqliteConfig.executeWriteVoid(dbPath, () ->
                jdbcTemplate.update(
                        """
                        UPDATE sessions SET
                            title = ?, model = ?, status = ?,
                            total_input_tokens = ?, total_output_tokens = ?,
                            total_cache_read = ?, total_cache_create = ?,
                            total_cost_usd = ?, summary = ?,
                            metadata_json = ?, updated_at = ?
                        WHERE id = ?
                        """,
                        data.title(), data.model(), data.status(),
                        data.totalUsage().inputTokens(), data.totalUsage().outputTokens(),
                        data.totalUsage().cacheReadInputTokens(), data.totalUsage().cacheCreationInputTokens(),
                        data.totalCostUsd(), data.summary(),
                        metadataJson, now,
                        data.sessionId()
                )
        );
    }

    // ───── 添加消息 ─────

    /**
     * 向会话追加消息并持久化。
     */
    public String addMessage(String sessionId, String role, Object content,
                             String stopReason, int inputTokens, int outputTokens) {
        String msgId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        String contentJson = toJsonString(content);

        // 获取下一个 seq_num
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq_num), 0) FROM messages WHERE session_id = ?",
                Integer.class, sessionId
        );
        int seqNum = (maxSeq != null ? maxSeq : 0) + 1;

        jdbcTemplate.update(
                """
                INSERT INTO messages (id, session_id, role, content_json, stop_reason,
                    input_tokens, output_tokens, created_at, seq_num)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                msgId, sessionId, role, contentJson, stopReason,
                inputTokens, outputTokens, now, seqNum
        );

        // 更新会话的 updated_at
        jdbcTemplate.update(
                "UPDATE sessions SET updated_at = ? WHERE id = ?", now, sessionId
        );

        return msgId;
    }

    // ───── 列表查询 ─────

    /**
     * 列出最近的会话摘要。
     */
    public List<SessionSummary> listSessions(int limit) {
        return jdbcTemplate.query(
                """
                SELECT s.*, (SELECT COUNT(*) FROM messages m WHERE m.session_id = s.id) AS message_count
                FROM sessions s ORDER BY s.updated_at DESC LIMIT ?
                """,
                summaryMapper, limit
        );
    }

    /**
     * 游标分页查询 — 对齐 v1.9.0 分页 API。
     */
    public SessionPage listSessionsPaginated(boolean anchorToLatest, String beforeId, int limit) {
        List<SessionSummary> sessions;

        if (anchorToLatest || beforeId == null) {
            // 首次加载：从最新开始，多取 1 条用于判断 hasMore
            sessions = jdbcTemplate.query(
                    """
                    SELECT s.*, (SELECT COUNT(*) FROM messages m WHERE m.session_id = s.id) AS message_count
                    FROM sessions s ORDER BY s.updated_at DESC LIMIT ?
                    """,
                    summaryMapper, limit + 1
            );
        } else {
            // 游标翻页：获取 beforeId 的 updated_at，然后查询更早的记录
            String cursorTime = jdbcTemplate.queryForObject(
                    "SELECT updated_at FROM sessions WHERE id = ?",
                    String.class, beforeId
            );
            sessions = jdbcTemplate.query(
                    """
                    SELECT s.*, (SELECT COUNT(*) FROM messages m WHERE m.session_id = s.id) AS message_count
                    FROM sessions s WHERE s.updated_at < ? ORDER BY s.updated_at DESC LIMIT ?
                    """,
                    summaryMapper, cursorTime, limit + 1
            );
        }

        boolean hasMore = sessions.size() > limit;
        if (hasMore) {
            sessions = sessions.subList(0, limit);
        }

        String oldestId = sessions.isEmpty() ? null : sessions.getLast().id();

        return new SessionPage(sessions, hasMore, oldestId);
    }

    // ───── 删除会话 ─────

    /**
     * 删除会话（级联删除消息、文件快照、任务）。
     */
    public void deleteSession(String sessionId) {
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);
        log.info("Session deleted: {}", sessionId);
    }

    // ───── 会话恢复 ─────

    /**
     * 恢复会话 — 加载会话数据并更新 AppState。
     */
    public Optional<SessionData> resumeSession(String sessionId) {
        Optional<SessionData> dataOpt = loadSession(sessionId);
        dataOpt.ifPresent(data -> {
            appStateStore.setState(state ->
                    state.withSession(s -> s
                            .withSessionId(data.sessionId())
                            .withCurrentModel(data.model())
                            .withWorkingDirectory(data.workingDir())
                            .withMessages(data.messages())
                    )
            );
            log.info("Session resumed: {} ({} messages)", sessionId, data.messages().size());
        });
        return dataOpt;
    }

    // ───── 内部工具方法 ─────

    private Optional<Message> mapRowToMessage(Map<String, Object> row) {
        // 消息暂以 Map 形式返回，后续 Round 会完善反序列化
        // 这里构造 UserMessage 作为占位
        try {
            String role = (String) row.get("role");
            String contentJson = (String) row.get("content_json");
            String id = (String) row.get("id");
            Instant createdAt = Instant.parse((String) row.get("created_at"));

            return Optional.of((Message) new Message.UserMessage(
                    id, createdAt, List.of(), contentJson, null
            ));
        } catch (Exception e) {
            log.warn("Failed to deserialize message: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse JSON map: {}", e.getMessage());
            return Map.of();
        }
    }

    private String toJsonString(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    private int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(value.toString());
    }

    private double toDouble(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number n) return n.doubleValue();
        return Double.parseDouble(value.toString());
    }
}
