package com.aicodeassistant.config.database;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * V022: 为 messages 表添加 meta_json 列（通用客户端元数据，JSON 文本）。
 * <p>
 * 首个用途：运行中追加的 steering 用户指令携带 {"steering": true}，
 * 使刷新后的历史消息仍能标识 steering 边界。无 meta 的旧消息该列为 NULL，
 * 行为与之前完全一致。
 */
@Component
@Order(22)
public class V022_AddMessageMetaJson implements Migration {
    private static final String CHECKSUM = MigrationChecksums.sha256(
            "v022-messages-meta-json-v1");
    private final JdbcTemplate jdbc;

    public V022_AddMessageMetaJson(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override public String checksum() { return CHECKSUM; }

    @Override
    public void execute() {
        if (!columns("messages").contains("meta_json")) {
            jdbc.execute("ALTER TABLE messages ADD COLUMN meta_json TEXT");
        }
    }

    @Override
    public void validate() {
        if (!columns("messages").contains("meta_json")) {
            throw new IllegalStateException(
                    "V022 messages.meta_json postcondition failed");
        }
    }

    private Set<String> columns(String table) {
        return jdbc.queryForList("PRAGMA table_info('" + table + "')")
                .stream().map(row -> String.valueOf(row.get("name")))
                .collect(Collectors.toSet());
    }
}
