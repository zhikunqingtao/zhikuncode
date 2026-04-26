package com.aicodeassistant.config.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * V002: 为 memories 表添加 source 列，标记记忆来源。
 * 幂等操作：通过 PRAGMA table_info 检查列是否已存在。
 *
 */
@Order(2)
@Component
public class V002_AddMemorySource implements Migration {

    private static final Logger log = LoggerFactory.getLogger(V002_AddMemorySource.class);

    private final JdbcTemplate globalJdbcTemplate;

    public V002_AddMemorySource(@Qualifier("globalJdbcTemplate") JdbcTemplate globalJdbcTemplate) {
        this.globalJdbcTemplate = globalJdbcTemplate;
    }

    @Override
    public void execute() {
        try {
            var columns = globalJdbcTemplate.queryForList(
                    "PRAGMA table_info(memories)");
            boolean hasSource = columns.stream()
                    .anyMatch(col -> "source".equals(col.get("name")));

            if (!hasSource) {
                globalJdbcTemplate.execute(
                        "ALTER TABLE memories ADD COLUMN source TEXT DEFAULT 'USER'");
                globalJdbcTemplate.execute(
                        "CREATE INDEX IF NOT EXISTS idx_memories_source ON memories(source)");
                log.info("V002: Added 'source' column to memories table");
            } else {
                log.debug("V002: 'source' column already exists, skipping");
            }
        } catch (Exception e) {
            log.error("V002 migration failed: {}", e.getMessage(), e);
        }
    }
}
