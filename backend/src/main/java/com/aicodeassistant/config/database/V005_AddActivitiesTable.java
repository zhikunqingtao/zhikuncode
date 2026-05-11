package com.aicodeassistant.config.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * V005: Activity 数据持久化表。
 * 存储每个会话中的操作活动记录（工具调用、文件变更等）。
 */
@Order(5)
@Component
public class V005_AddActivitiesTable implements Migration {

    private static final Logger log = LoggerFactory.getLogger(V005_AddActivitiesTable.class);

    private final JdbcTemplate projectJdbcTemplate;

    public V005_AddActivitiesTable(@Qualifier("projectJdbcTemplate") JdbcTemplate projectJdbcTemplate) {
        this.projectJdbcTemplate = projectJdbcTemplate;
    }

    @Override
    public void execute() {
        log.info("Creating activities table...");

        projectJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS activities (
                    id              TEXT PRIMARY KEY,
                    session_id      TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                    operation_type  TEXT NOT NULL,
                    summary         TEXT NOT NULL,
                    status          TEXT NOT NULL DEFAULT 'completed',
                    timestamp       INTEGER NOT NULL,
                    duration        INTEGER,
                    file_count      INTEGER DEFAULT 0,
                    decision        TEXT,
                    tool_result_json TEXT,
                    changed_files_json TEXT,
                    insight_json    TEXT,
                    created_at      TEXT NOT NULL,
                    updated_at      TEXT NOT NULL
                )
                """);

        projectJdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_activities_session ON activities(session_id, timestamp)");

        log.info("V005: activities table created.");
    }
}
