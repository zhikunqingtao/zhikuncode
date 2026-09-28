package com.aicodeassistant.config.database;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V027：为 run_envelopes 增加 usage_status / usage_snapshot_seq 的非破坏性迁移。
 * 旧行必须保留且默认 'unknown'（未报告 ≠ 零消费），迁移可重复执行且校验可拒绝弱化的列定义。
 */
class V027AddRunUsageSnapshotTest {
    @TempDir Path temp;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + temp.resolve("migration.db"));
        jdbc = new JdbcTemplate(source);
        // V014.execute() 会先清空这些表再重建 Run/Event 结构。
        jdbc.execute("CREATE TABLE agent_checkpoints(id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE artifact_entries(id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE artifact_manifests(id TEXT PRIMARY KEY)");
        new V014_RebuildRunV2Schema(jdbc).execute();
    }

    @Test
    void keepsLegacyRowsAndMarksTheirUsageUnknownWithoutBackfill() {
        jdbc.update("""
                INSERT INTO run_envelopes(id,session_id,status,model,started_at,total_tokens,created_at,updated_at)
                VALUES('legacy-1','session-1','running','kimi-k3','2026-09-20T00:00:00Z',123,
                       '2026-09-20T00:00:00Z','2026-09-20T00:00:00Z')
                """);

        new V027_AddRunUsageSnapshot(jdbc).execute();
        new V027_AddRunUsageSnapshot(jdbc).validate();

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM run_envelopes WHERE id='legacy-1'");
        assertThat(row.get("model")).isEqualTo("kimi-k3");
        assertThat(row.get("status")).isEqualTo("running");
        assertThat(row.get("total_tokens")).isEqualTo(123);
        // 历史零值/非零值都不回填为已知：默认 unknown、快照序号 0。
        assertThat(row.get("usage_status")).isEqualTo("unknown");
        assertThat(((Number) row.get("usage_snapshot_seq")).longValue()).isZero();

        // 重复执行保持幂等，不覆盖已有数据。
        new V027_AddRunUsageSnapshot(jdbc).execute();
        new V027_AddRunUsageSnapshot(jdbc).validate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_envelopes", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT usage_status FROM run_envelopes WHERE id='legacy-1'", String.class))
                .isEqualTo("unknown");
    }

    @Test
    void reexecutionIsIdempotentAndDatabaseDefaultsAreTheOnlyUnknownSource() {
        new V027_AddRunUsageSnapshot(jdbc).execute();
        new V027_AddRunUsageSnapshot(jdbc).execute();
        new V027_AddRunUsageSnapshot(jdbc).validate();

        jdbc.update("""
                INSERT INTO run_envelopes(id,session_id,status,model,started_at,created_at,updated_at)
                VALUES('new-1','session-1','running','kimi-k3','2026-09-20T00:00:00Z',
                       '2026-09-20T00:00:00Z','2026-09-20T00:00:00Z')
                """);
        assertThat(jdbc.queryForObject(
                "SELECT usage_status FROM run_envelopes WHERE id='new-1'", String.class))
                .isEqualTo("unknown");
        assertThat(jdbc.queryForObject(
                "SELECT usage_snapshot_seq FROM run_envelopes WHERE id='new-1'", Integer.class))
                .isZero();
    }

    @Test
    void rejectsUnrecognizedStatusAndNegativeSequence() {
        new V027_AddRunUsageSnapshot(jdbc).execute();
        jdbc.update("""
                INSERT INTO run_envelopes(id,session_id,status,model,started_at,created_at,updated_at)
                VALUES('run-1','session-1','running','kimi-k3','2026-09-20T00:00:00Z',
                       '2026-09-20T00:00:00Z','2026-09-20T00:00:00Z')
                """);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE run_envelopes SET usage_status='estimated' WHERE id='run-1'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE run_envelopes SET usage_snapshot_seq=-1 WHERE id='run-1'"))
                .isInstanceOf(DataAccessException.class);
        jdbc.update("UPDATE run_envelopes SET usage_status='known', usage_snapshot_seq=3 WHERE id='run-1'");
        assertThat(jdbc.queryForObject(
                "SELECT usage_status FROM run_envelopes WHERE id='run-1'", String.class))
                .isEqualTo("known");
    }

    @Test
    void validationRejectsMissingOrWeakColumns() {
        // 尚未迁移 → 校验必须失败。
        assertThatThrownBy(() -> new V027_AddRunUsageSnapshot(jdbc).validate())
                .isInstanceOf(IllegalStateException.class);

        // 可空、无默认值的 usage_status 不满足"默认 unknown"语义。
        jdbc.execute("ALTER TABLE run_envelopes ADD COLUMN usage_status TEXT");
        assertThatThrownBy(() -> new V027_AddRunUsageSnapshot(jdbc).validate())
                .isInstanceOf(IllegalStateException.class);

        jdbc.execute("ALTER TABLE run_envelopes ADD COLUMN usage_snapshot_seq INTEGER NOT NULL DEFAULT 0");
        assertThatThrownBy(() -> new V027_AddRunUsageSnapshot(jdbc).validate())
                .isInstanceOf(IllegalStateException.class);
    }
}
