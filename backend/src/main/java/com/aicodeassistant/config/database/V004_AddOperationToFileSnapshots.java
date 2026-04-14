package com.aicodeassistant.config.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * V004: 为 file_snapshots 表添加 operation 列。
 * <p>
 * 修复已有数据库中 file_snapshots 表缺少 operation 列导致
 * SQLITE_CONSTRAINT_NOTNULL 错误的问题。
 * 使用 ALTER TABLE 添加缺失列，如果列已存在则忽略错误（幂等）。
 */
@Order(4)
@Component
public class V004_AddOperationToFileSnapshots implements Migration {

    private static final Logger log = LoggerFactory.getLogger(V004_AddOperationToFileSnapshots.class);

    private final JdbcTemplate projectJdbcTemplate;

    public V004_AddOperationToFileSnapshots(@Qualifier("projectJdbcTemplate") JdbcTemplate projectJdbcTemplate) {
        this.projectJdbcTemplate = projectJdbcTemplate;
    }

    @Override
    public void execute() {
        log.info("Adding operation column to file_snapshots table (if missing)...");
        try {
            projectJdbcTemplate.execute(
                    "ALTER TABLE file_snapshots ADD COLUMN operation TEXT NOT NULL DEFAULT 'edit'");
            log.info("Column operation added to file_snapshots successfully");
        } catch (DataAccessException e) {
            if (e.getMessage() != null && e.getMessage().contains("duplicate column name")) {
                log.debug("Column operation already exists in file_snapshots — skipping");
            } else {
                throw e;
            }
        }
    }
}
