package com.aicodeassistant.config.database;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 为 Run 增加已观测用量快照列：usage_status（known/partial/unknown）与 usage_snapshot_seq。
 * 非破坏性迁移 — 只新增列；旧数据保持 'unknown'，历史零值不回填为已知。
 */
@Component
@Order(27)
public final class V027_AddRunUsageSnapshot implements Migration {
    static final String ADD_USAGE_STATUS_SQL = """
            ALTER TABLE run_envelopes ADD COLUMN usage_status TEXT NOT NULL DEFAULT 'unknown'
                CHECK(usage_status IN ('known','partial','unknown'))
            """;
    static final String ADD_USAGE_SNAPSHOT_SEQ_SQL = """
            ALTER TABLE run_envelopes ADD COLUMN usage_snapshot_seq INTEGER NOT NULL DEFAULT 0
                CHECK(usage_snapshot_seq >= 0)
            """;
    private final JdbcTemplate jdbc;

    public V027_AddRunUsageSnapshot(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String checksum() {
        return MigrationChecksums.sha256(ADD_USAGE_STATUS_SQL + ";" + ADD_USAGE_SNAPSHOT_SEQ_SQL);
    }

    @Override
    public void execute() {
        if (!hasColumn("usage_status")) jdbc.execute(ADD_USAGE_STATUS_SQL);
        if (!hasColumn("usage_snapshot_seq")) jdbc.execute(ADD_USAGE_SNAPSHOT_SEQ_SQL);
    }

    @Override
    public void validate() {
        boolean statusValid = jdbc.queryForList("PRAGMA table_info(run_envelopes)").stream()
                .anyMatch(column -> "usage_status".equals(column.get("name"))
                        && "TEXT".equalsIgnoreCase(String.valueOf(column.get("type")))
                        && ((Number) column.get("notnull")).intValue() == 1
                        && "'unknown'".equals(column.get("dflt_value")));
        boolean seqValid = jdbc.queryForList("PRAGMA table_info(run_envelopes)").stream()
                .anyMatch(column -> "usage_snapshot_seq".equals(column.get("name"))
                        && "INTEGER".equalsIgnoreCase(String.valueOf(column.get("type")))
                        && ((Number) column.get("notnull")).intValue() == 1
                        && "0".equals(String.valueOf(column.get("dflt_value"))));
        if (!statusValid) throw new IllegalStateException("V027 run_envelopes.usage_status schema incomplete");
        if (!seqValid) throw new IllegalStateException("V027 run_envelopes.usage_snapshot_seq schema incomplete");
    }

    private boolean hasColumn(String name) {
        return jdbc.queryForList("PRAGMA table_info(run_envelopes)").stream()
                .anyMatch(column -> name.equals(column.get("name")));
    }
}
