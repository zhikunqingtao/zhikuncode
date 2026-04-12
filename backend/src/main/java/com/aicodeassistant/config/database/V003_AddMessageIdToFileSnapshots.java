package com.aicodeassistant.config.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * V003: 为 file_snapshots 表添加 message_id 列。
 * <p>
 * 修复已有数据库中 file_snapshots 表缺少 message_id 列的问题。
 * 使用 ALTER TABLE 添加缺失列，如果列已存在则忽略错误（幂等）。
 */
@Order(3)
@Component
public class V003_AddMessageIdToFileSnapshots implements Migration {

    private static final Logger log = LoggerFactory.getLogger(V003_AddMessageIdToFileSnapshots.class);

    private final JdbcTemplate projectJdbcTemplate;

    public V003_AddMessageIdToFileSnapshots(@Qualifier("projectJdbcTemplate") JdbcTemplate projectJdbcTemplate) {
        this.projectJdbcTemplate = projectJdbcTemplate;
    }

    @Override
    public void execute() {
        log.info("Adding message_id column to file_snapshots table (if missing)...");
        try {
            projectJdbcTemplate.execute("ALTER TABLE file_snapshots ADD COLUMN message_id TEXT");
            log.info("Column message_id added to file_snapshots successfully");
        } catch (DataAccessException e) {
            // SQLite throws error if column already exists — this is expected for idempotency
            if (e.getMessage() != null && e.getMessage().contains("duplicate column name")) {
                log.debug("Column message_id already exists in file_snapshots — skipping");
            } else {
                throw e;
            }
        }
    }
}
