package com.aicodeassistant.tool.agent;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 子代理检查点服务 — 管理检查点的保存、查询和清理。
 * <p>
 * 检查点策略:
 * <ul>
 *   <li>每 5 轮或每 10 次工具调用触发一次检查点</li>
 *   <li>每个 run 最多保留 10 个检查点（FIFO 淘汰）</li>
 *   <li>messagesJson 超过 5MB 时自动裁剪保留最近 20 条消息</li>
 * </ul>
 */
@Service
public class CheckpointService {

    private static final Logger log = LoggerFactory.getLogger(CheckpointService.class);

    private final JdbcTemplate projectJdbcTemplate;
    private final SqliteConfig sqliteConfig;
    private final Path dbPath;
    private final ObjectMapper objectMapper;

    static final int CHECKPOINT_INTERVAL_TURNS = 5;
    static final int CHECKPOINT_INTERVAL_TOOLS = 10;
    static final int MAX_CHECKPOINTS_PER_RUN = 10;
    static final long MAX_MESSAGES_JSON_BYTES = 5L * 1024 * 1024; // 5MB

    public CheckpointService(@Qualifier("projectJdbcTemplate") JdbcTemplate projectJdbcTemplate,
                             SqliteConfig sqliteConfig,
                             DatabaseResolver databaseResolver,
                             ObjectMapper objectMapper) {
        this.projectJdbcTemplate = projectJdbcTemplate;
        this.sqliteConfig = sqliteConfig;
        this.dbPath = databaseResolver.getProjectDbPath(Path.of(System.getProperty("user.dir")));
        this.objectMapper = objectMapper;
    }

    /**
     * 判断是否应该保存检查点。
     *
     * @param turnCount          当前轮次
     * @param toolCallCount      累计工具调用数
     * @param lastCheckpointTurn 上次检查点时的轮次
     * @return true 表示应该保存
     */
    public boolean shouldCheckpoint(int turnCount, int toolCallCount, int lastCheckpointTurn) {
        return (turnCount - lastCheckpointTurn >= CHECKPOINT_INTERVAL_TURNS)
            || (toolCallCount > 0 && toolCallCount % CHECKPOINT_INTERVAL_TOOLS == 0);
    }

    /**
     * 保存检查点 — 写入串行化保护，超大 JSON 自动裁剪。
     */
    public void save(AgentCheckpoint checkpoint) {
        String messagesJson = checkpoint.messagesJson();

        if (messagesJson != null && messagesJson.length() > MAX_MESSAGES_JSON_BYTES) {
            // Trim messages instead of skipping entirely
            log.error("Checkpoint messagesJson too large ({} bytes) for run {}, trimming to recent messages",
                    messagesJson.length(), checkpoint.runId());
            messagesJson = trimMessagesJson(messagesJson);
            // Create a new checkpoint with trimmed messages
            checkpoint = new AgentCheckpoint(
                checkpoint.id(), checkpoint.runId(), checkpoint.sessionId(),
                checkpoint.agentId(), checkpoint.seq(),
                messagesJson, checkpoint.fileStateJson(),
                checkpoint.toolCallCount(), checkpoint.turnCount(),
                checkpoint.tokensConsumed(), checkpoint.workingDir(),
                checkpoint.createdAt()
            );
        }

        final AgentCheckpoint finalCheckpoint = checkpoint;
        sqliteConfig.executeWriteVoid(dbPath, () -> {
            projectJdbcTemplate.update("""
                INSERT OR REPLACE INTO agent_checkpoints
                (id, run_id, session_id, agent_id, seq, messages_json, file_state_json,
                 tool_call_count, turn_count, tokens_consumed, working_dir, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                finalCheckpoint.id(), finalCheckpoint.runId(), finalCheckpoint.sessionId(),
                finalCheckpoint.agentId(), finalCheckpoint.seq(),
                finalCheckpoint.messagesJson(), finalCheckpoint.fileStateJson(),
                finalCheckpoint.toolCallCount(), finalCheckpoint.turnCount(),
                finalCheckpoint.tokensConsumed(), finalCheckpoint.workingDir(),
                finalCheckpoint.createdAt().toString()
            );
            cleanupOldCheckpoints(finalCheckpoint.runId());
        });
        log.debug("Checkpoint saved: run={}, seq={}, turn={}", 
                finalCheckpoint.runId(), finalCheckpoint.seq(), finalCheckpoint.turnCount());
    }

    /**
     * 裁剪消息 JSON — 保留最近 20 条消息。
     */
    private String trimMessagesJson(String messagesJson) {
        try {
            var messages = objectMapper.readTree(messagesJson);
            if (messages.isArray() && messages.size() > 20) {
                ArrayNode trimmed = objectMapper.createArrayNode();
                for (int i = messages.size() - 20; i < messages.size(); i++) {
                    trimmed.add(messages.get(i));
                }
                return objectMapper.writeValueAsString(trimmed);
            }
            return messagesJson;
        } catch (Exception e) {
            log.warn("Failed to trim messages JSON, using original: {}", e.getMessage());
            return messagesJson;
        }
    }

    /**
     * 获取指定 run 的最新检查点。
     */
    public Optional<AgentCheckpoint> getLatest(String runId) {
        List<AgentCheckpoint> results = projectJdbcTemplate.query(
            "SELECT * FROM agent_checkpoints WHERE run_id=? ORDER BY seq DESC LIMIT 1",
            CHECKPOINT_ROW_MAPPER, runId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * 获取指定代理的所有检查点。
     */
    public List<AgentCheckpoint> getByAgent(String agentId) {
        return projectJdbcTemplate.query(
            "SELECT * FROM agent_checkpoints WHERE agent_id=? ORDER BY seq DESC",
            CHECKPOINT_ROW_MAPPER, agentId);
    }

    /**
     * 删除指定 run 的所有检查点。
     */
    public void deleteByRunId(String runId) {
        sqliteConfig.executeWriteVoid(dbPath, () ->
            projectJdbcTemplate.update("DELETE FROM agent_checkpoints WHERE run_id=?", runId));
    }

    /**
     * 保留最新的 N 个检查点，淘汰旧的。
     */
    private void cleanupOldCheckpoints(String runId) {
        projectJdbcTemplate.update("""
            DELETE FROM agent_checkpoints WHERE run_id=? AND id NOT IN (
                SELECT id FROM agent_checkpoints WHERE run_id=? ORDER BY created_at DESC LIMIT ?
            )""", runId, runId, MAX_CHECKPOINTS_PER_RUN);
    }

    private static final RowMapper<AgentCheckpoint> CHECKPOINT_ROW_MAPPER = (rs, rowNum) ->
        new AgentCheckpoint(
            rs.getString("id"),
            rs.getString("run_id"),
            rs.getString("session_id"),
            rs.getString("agent_id"),
            rs.getInt("seq"),
            rs.getString("messages_json"),
            rs.getString("file_state_json"),
            rs.getInt("tool_call_count"),
            rs.getInt("turn_count"),
            rs.getLong("tokens_consumed"),
            rs.getString("working_dir"),
            Instant.parse(rs.getString("created_at"))
        );
}
