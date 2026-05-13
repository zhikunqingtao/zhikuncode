package com.aicodeassistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 异常事件持久化仓储 — 操作 data.db 的 anomaly_events 表。
 * <p>
 * 记录 Swarm/Worker 运行期间的异常事件（中止、超时、规则违规等），
 * 供 Phase 2 异常检测和诊断使用。
 */
@Repository
public class AnomalyEventRepository {

    private static final Logger log = LoggerFactory.getLogger(AnomalyEventRepository.class);

    private static final int MAX_CONTEXT_SNAPSHOT_SIZE = 100 * 1024; // 100KB

    private final JdbcTemplate jdbcTemplate;

    public AnomalyEventRepository(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存异常事件。
     *
     * @param id          事件唯一 ID
     * @param swarmId     所属 Swarm ID
     * @param workerId    触发 Worker ID
     * @param ruleId      规则标识（如 "worker_abort", "loop_detect", "timeout"）
     * @param severity    严重级别（"low" | "medium" | "high" | "critical"）
     * @param message     事件描述
     * @param detectedAt  检测时间戳（epoch millis）
     * @param contextSnapshot 上下文快照 JSON（nullable）
     */
    public void save(String id, String swarmId, String workerId, String ruleId,
                     String severity, String message, long detectedAt, String contextSnapshot) {
        // 截断过大的 context_snapshot，防止 SQLite 表膨胀
        String truncatedSnapshot = contextSnapshot;
        if (contextSnapshot != null && contextSnapshot.length() > MAX_CONTEXT_SNAPSHOT_SIZE) {
            truncatedSnapshot = contextSnapshot.substring(0, MAX_CONTEXT_SNAPSHOT_SIZE)
                    + "\n... [truncated at 100KB, original size: " + contextSnapshot.length() + "]";
            log.warn("Truncated context_snapshot for event {}: {} -> {} bytes", id, contextSnapshot.length(), MAX_CONTEXT_SNAPSHOT_SIZE);
        }

        jdbcTemplate.update(
                """
                INSERT OR REPLACE INTO anomaly_events (id, swarm_id, worker_id, rule_id,
                    severity, message, detected_at, context_snapshot)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, swarmId, workerId, ruleId, severity, message, detectedAt, truncatedSnapshot
        );
        log.debug("Saved anomaly event: id={}, ruleId={}, worker={}", id, ruleId, workerId);
    }

    /**
     * 按 Swarm ID 查询所有异常事件（按 detected_at 降序）。
     */
    public List<Map<String, Object>> findBySwarmId(String swarmId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM anomaly_events WHERE swarm_id = ? ORDER BY detected_at DESC",
                swarmId
        );
    }

    /**
     * 按 Worker ID 查询异常事件。
     */
    public List<Map<String, Object>> findByWorkerId(String workerId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM anomaly_events WHERE worker_id = ? ORDER BY detected_at DESC",
                workerId
        );
    }

    /**
     * 解决异常事件 — 记录解决时间和解决方式。
     *
     * @param id         事件 ID
     * @param resolution 解决描述（如 "auto_recovered", "manually_resolved"）
     */
    public void resolve(String id, String resolution) {
        long resolvedAt = System.currentTimeMillis();
        jdbcTemplate.update(
                "UPDATE anomaly_events SET resolved_at = ?, resolution = ? WHERE id = ?",
                resolvedAt, resolution, id
        );
        log.debug("Resolved anomaly event: id={}, resolution={}", id, resolution);
    }

    /**
     * 查询未解决的异常事件。
     */
    public List<Map<String, Object>> findUnresolved(String swarmId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM anomaly_events WHERE swarm_id = ? AND resolved_at IS NULL ORDER BY detected_at DESC",
                swarmId
        );
    }

    /**
     * 按严重级别统计。
     */
    public Map<String, Object> countBySeverity(String swarmId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT severity, COUNT(*) as cnt FROM anomaly_events WHERE swarm_id = ? GROUP BY severity",
                swarmId
        );
        var result = new java.util.LinkedHashMap<String, Object>();
        for (var row : rows) {
            result.put((String) row.get("severity"), row.get("cnt"));
        }
        return result;
    }

    /**
     * 删除指定时间之前已解决的异常事件。
     * @param cutoffMs 截止时间（epoch millis）
     * @return 删除的行数
     */
    public int deleteResolvedBefore(long cutoffMs) {
        return jdbcTemplate.update(
                "DELETE FROM anomaly_events WHERE resolved_at IS NOT NULL AND resolved_at < ?",
                cutoffMs
        );
    }

    /**
     * 查询异常事件总数。
     */
    public int countAll() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM anomaly_events",
                Integer.class
        );
        return count != null ? count : 0;
    }
}
