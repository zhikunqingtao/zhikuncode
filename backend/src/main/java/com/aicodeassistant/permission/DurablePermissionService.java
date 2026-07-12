package com.aicodeassistant.permission;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Durable Permission Service — 权限请求持久化与恢复。
 * <p>
 * 核心功能:
 * <ul>
 *   <li>persistRequest: 将权限请求持久化到 DB（timeout = 300 秒）</li>
 *   <li>resolve: 更新 DB 状态为 approved/denied</li>
 *   <li>getPending: 查询 pending 且未超时的权限请求（重连恢复用）</li>
 *   <li>expireTimedOut: 定时扫描超时请求并自动 deny</li>
 * </ul>
 */
@Service
public class DurablePermissionService {

    private static final Logger log = LoggerFactory.getLogger(DurablePermissionService.class);

    /** 权限请求超时时间（秒） */
    public static final int TIMEOUT_SECONDS = 300;

    private final AtomicInteger expirationFailureCount = new AtomicInteger(0);

    private final JdbcTemplate jdbcTemplate;
    private final SqliteConfig sqliteConfig;
    private final Path dbPath;

    private static final RowMapper<PermissionRequestRecord> ROW_MAPPER = (rs, rowNum) -> {
        String decidedAtStr = rs.getString("decided_at");
        return new PermissionRequestRecord(
                rs.getString("id"),
                rs.getString("run_id"),
                rs.getString("session_id"),
                rs.getString("tool_use_id"),
                rs.getString("tool_name"),
                rs.getString("risk_level"),
                rs.getString("reason"),
                rs.getString("input_summary"),
                rs.getString("status"),
                rs.getString("decision"),
                rs.getString("decided_by"),
                rs.getInt("remember") == 1,
                rs.getString("remember_scope"),
                Instant.parse(rs.getString("requested_at")),
                decidedAtStr != null ? Instant.parse(decidedAtStr) : null,
                Instant.parse(rs.getString("timeout_at")),
                rs.getString("source"),
                rs.getString("child_session_id"),
                Instant.parse(rs.getString("created_at"))
        );
    };

    public DurablePermissionService(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbcTemplate,
                                     SqliteConfig sqliteConfig,
                                     DatabaseResolver databaseResolver) {
        this.jdbcTemplate = jdbcTemplate;
        this.sqliteConfig = sqliteConfig;
        this.dbPath = databaseResolver.getProjectDbPath(Path.of(System.getProperty("user.dir")));
    }

    /**
     * 持久化权限请求到数据库。
     *
     * @param runId          运行信封 ID（可为 null）
     * @param sessionId      会话 ID
     * @param toolUseId      工具调用 ID
     * @param toolName       工具名称
     * @param riskLevel      风险级别
     * @param reason         请求原因
     * @param inputSummary   工具输入摘要（JSON 序列化）
     * @param source         来源（direct / bubble）
     * @param childSessionId 子会话 ID（冒泡时非 null）
     */
    @Transactional
    public void persistRequest(String runId, String sessionId, String toolUseId,
                                String toolName, String riskLevel, String reason,
                                String inputSummary, String source, String childSessionId) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant timeoutAt = now.plusSeconds(TIMEOUT_SECONDS);

        sqliteConfig.executeWriteVoid(dbPath, () ->
                jdbcTemplate.update("""
                    INSERT INTO permission_requests
                    (id, run_id, session_id, tool_use_id, tool_name, risk_level, reason,
                     input_summary, status, source, child_session_id,
                     requested_at, timeout_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?, ?, ?, ?)
                    """,
                        id, runId, sessionId, toolUseId, toolName, riskLevel, reason,
                        inputSummary, source != null ? source : "direct", childSessionId,
                        now.toString(), timeoutAt.toString(), now.toString()
                )
        );

        log.info("Persisted permission request: id={}, toolUseId={}, toolName={}, timeout={}",
                id, toolUseId, toolName, timeoutAt);
    }

    /**
     * 解决持久化的权限请求 — 原子更新状态为 approved/denied。
     * <p>
     * 使用 WHERE status='pending' 条件确保并发安全：
     * 同一请求只有第一个到达的 resolve 调用能成功更新，后续调用返回 false。
     *
     * @param toolUseId     工具调用 ID
     * @param decision      决策（approved / denied）
     * @param decidedBy     决策者（USER_WS / TIMEOUT / REST）
     * @param remember      是否记住
     * @param rememberScope 记忆作用域
     * @return true 如果实际更新了记录（首次处理），false 表示该请求已被其他操作处理
     */
    @Transactional
    public boolean resolve(String toolUseId, String decision, String decidedBy,
                         boolean remember, String rememberScope) {
        Instant now = Instant.now();
        String status = "approved".equals(decision) ? "approved" : "denied";

        int affected = sqliteConfig.executeWrite(dbPath, () ->
                jdbcTemplate.update("""
                    UPDATE permission_requests
                    SET status = ?, decision = ?, decided_by = ?,
                        remember = ?, remember_scope = ?, decided_at = ?
                    WHERE tool_use_id = ? AND status = 'pending'
                    """,
                        status, decision, decidedBy,
                        remember ? 1 : 0, rememberScope, now.toString(),
                        toolUseId
                )
        );

        if (affected > 0) {
            log.info("Resolved permission request: toolUseId={}, decision={}, decidedBy={}",
                    toolUseId, decision, decidedBy);
        } else {
            log.warn("Permission request already resolved (concurrent race): toolUseId={}, attemptedDecision={}, attemptedBy={}",
                    toolUseId, decision, decidedBy);
        }
        return affected > 0;
    }

    /**
     * 获取指定会话的所有 pending 且未超时的权限请求。
     * 用于断线重连后恢复未决权限弹窗。
     *
     * @param sessionId 会话 ID
     * @return pending 权限请求列表
     */
    public List<PermissionRequestRecord> getPending(String sessionId) {
        Instant now = Instant.now();
        return jdbcTemplate.query("""
            SELECT * FROM permission_requests
            WHERE session_id = ? AND status = 'pending' AND timeout_at > ?
            ORDER BY requested_at ASC
            """,
                ROW_MAPPER, sessionId, now.toString()
        );
    }

    /**
     * 定时过期处理 — 每 30 秒扫描超时的 pending 请求并自动标记为 timeout/deny。
     */
    @Transactional
    @Scheduled(fixedRate = 30_000)
    public void expireTimedOut() {
        Instant now = Instant.now();
        try {
            sqliteConfig.executeWriteVoid(dbPath, () -> {
                int expired = jdbcTemplate.update("""
                    UPDATE permission_requests
                    SET status = 'timeout', decision = 'deny', decided_by = 'TIMEOUT', decided_at = ?
                    WHERE status = 'pending' AND timeout_at <= ?
                    """,
                        now.toString(), now.toString()
                );
                if (expired > 0) {
                    log.info("Expired {} timed-out permission requests", expired);
                }
            });
            expirationFailureCount.set(0);
        } catch (Exception e) {
            int failures = expirationFailureCount.incrementAndGet();
            log.error("Failed to expire timed-out permission requests (consecutive failures: {}): {}",
                failures, e.getMessage(), e);
            if (failures >= 5) {
                log.error("ALERT: Permission expiration has failed {} consecutive times - manual intervention may be needed", failures);
            }
        }
    }

    /**
     * 按 toolUseId 查询单条 pending 权限请求。
     */
    public PermissionRequestRecord findPendingByToolUseId(String toolUseId) {
        List<PermissionRequestRecord> results = jdbcTemplate.query("""
            SELECT * FROM permission_requests
            WHERE tool_use_id = ? AND status = 'pending'
            LIMIT 1
            """,
                ROW_MAPPER, toolUseId
        );
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 查询指定会话的权限请求（可按状态过滤）。
     *
     * @param sessionId 会话 ID
     * @param status    状态过滤（null 表示全部）
     * @return 权限请求列表
     */
    public List<PermissionRequestRecord> findBySession(String sessionId, String status) {
        if (status != null && !status.isEmpty()) {
            return jdbcTemplate.query("""
                SELECT * FROM permission_requests
                WHERE session_id = ? AND status = ?
                ORDER BY requested_at DESC
                """,
                    ROW_MAPPER, sessionId, status
            );
        }
        return jdbcTemplate.query("""
            SELECT * FROM permission_requests
            WHERE session_id = ?
            ORDER BY requested_at DESC
            """,
                ROW_MAPPER, sessionId
        );
    }
}
