package com.aicodeassistant.config.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * V003: 项目上下文索引持久化表。
 * 缓存项目的 git 信息、文件树等上下文，避免每次会话重复收集。
 *
 * @see <a href="SPEC §7.4.2">数据库初始化迁移</a>
 */
@Order(3)
@Component
public class V003_AddProjectContextTable implements Migration {

    private static final Logger log = LoggerFactory.getLogger(V003_AddProjectContextTable.class);

    private final JdbcTemplate projectJdbcTemplate;

    public V003_AddProjectContextTable(@Qualifier("projectJdbcTemplate") JdbcTemplate projectJdbcTemplate) {
        this.projectJdbcTemplate = projectJdbcTemplate;
    }

    @Override
    public void execute() {
        log.info("Creating project_context table...");

        projectJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS project_context (
                    id                TEXT PRIMARY KEY,
                    working_dir_hash  TEXT NOT NULL UNIQUE,
                    snapshot_json     TEXT NOT NULL,
                    git_head_sha      TEXT,
                    updated_at        TEXT NOT NULL
                )
                """);

        log.info("V003: project_context table created.");
    }
}
