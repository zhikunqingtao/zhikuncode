package com.aicodeassistant.config.database;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;

/** Executed atomically by MigrationRunner; preserves the v1 ledger and its validation contract. */
@Component
@Order(26)
public final class V026_ExtendSessionMerges implements Migration {
    private static final String SQL = """
            CREATE TABLE session_merges_v2 (
                operation_id TEXT PRIMARY KEY,
                idempotency_key TEXT NOT NULL UNIQUE,
                params_json TEXT NOT NULL,
                target_session_id TEXT NOT NULL UNIQUE,
                status TEXT NOT NULL CHECK(status IN ('preparing','paused','failed','completed','cancelled')),
                stage TEXT NOT NULL,
                package_path TEXT NOT NULL,
                result_json TEXT NOT NULL DEFAULT '{}',
                usage_json TEXT NOT NULL DEFAULT '[]',
                error TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                protocol_version INTEGER NOT NULL DEFAULT 1 CHECK(protocol_version IN (1,2)),
                active_slot INTEGER CHECK(active_slot IS NULL OR active_slot=1),
                run_epoch INTEGER NOT NULL DEFAULT 0 CHECK(run_epoch>=0),
                snapshot_version INTEGER NOT NULL DEFAULT 0 CHECK(snapshot_version>=0),
                snapshot_hash TEXT,
                handoff_hash TEXT,
                execution_json TEXT NOT NULL DEFAULT '{}',
                error_code TEXT,
                retry_at TEXT,
                CHECK(protocol_version=1 OR
                    (status IN ('preparing','paused','failed') AND active_slot IS 1) OR
                    (status IN ('completed','cancelled') AND active_slot IS NULL)),
                CHECK(protocol_version=1 OR stage IN
                    ('snapshotting','extracting','aggregating','validating','publishing','completed')),
                CHECK(protocol_version=1 OR status<>'completed' OR
                    (snapshot_hash IS NOT NULL AND handoff_hash IS NOT NULL AND stage='completed'))
            );
            INSERT INTO session_merges_v2
                (operation_id,idempotency_key,params_json,target_session_id,status,stage,
                 package_path,result_json,usage_json,error,created_at,updated_at,protocol_version,error_code)
            SELECT operation_id,idempotency_key,params_json,target_session_id,
                CASE WHEN status='preparing' THEN 'failed' ELSE status END,
                CASE WHEN status='preparing' THEN 'interrupted' ELSE stage END,
                package_path,result_json,usage_json,
                CASE WHEN status='preparing' THEN '旧版任务中断，请重新发起' ELSE error END,
                created_at,updated_at,1,
                CASE WHEN status='preparing' THEN 'LEGACY_INTERRUPTED' ELSE NULL END
            FROM session_merges;
            DROP TABLE session_merges;
            ALTER TABLE session_merges_v2 RENAME TO session_merges;
            CREATE INDEX idx_session_merges_status ON session_merges(status);
            CREATE UNIQUE INDEX uq_session_merges_active ON session_merges(active_slot) WHERE active_slot=1;
            
            CREATE TABLE session_merge_units (
                operation_id TEXT NOT NULL REFERENCES session_merges(operation_id) ON DELETE CASCADE,
                unit_id TEXT NOT NULL,
                stage TEXT NOT NULL CHECK(stage IN ('extracting','aggregating')),
                ordinal INTEGER NOT NULL,
                input_hash TEXT NOT NULL,
                input_json TEXT NOT NULL,
                processor_version TEXT NOT NULL,
                model TEXT NOT NULL,
                state TEXT NOT NULL CHECK(state IN ('pending','running','completed','split','failed')),
                attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count>=0),
                run_epoch INTEGER NOT NULL DEFAULT 0,
                result_path TEXT,
                result_hash TEXT,
                error_code TEXT,
                updated_at TEXT NOT NULL,
                PRIMARY KEY(operation_id,unit_id),
                CHECK(state<>'completed' OR (result_path IS NOT NULL AND result_hash IS NOT NULL))
            );
            CREATE INDEX idx_session_merge_units_next
                ON session_merge_units(operation_id,stage,state,ordinal,unit_id);
            
            CREATE TABLE session_merge_attempts (
                request_id TEXT PRIMARY KEY,
                operation_id TEXT NOT NULL REFERENCES session_merges(operation_id) ON DELETE CASCADE,
                unit_id TEXT NOT NULL,
                run_epoch INTEGER NOT NULL,
                model TEXT NOT NULL,
                started_at TEXT NOT NULL,
                finished_at TEXT,
                outcome TEXT NOT NULL CHECK(outcome IN ('running','completed','error','cancelled','unknown')),
                usage_json TEXT,
                usage_reported INTEGER NOT NULL DEFAULT 0 CHECK(usage_reported IN (0,1)),
                estimated_cost_usd REAL,
                CHECK(usage_reported=1 OR estimated_cost_usd IS NULL)
            );
            CREATE INDEX idx_session_merge_attempts_operation
                ON session_merge_attempts(operation_id,unit_id,started_at);
            """;
    private final JdbcTemplate jdbc;
    public V026_ExtendSessionMerges(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public String checksum() { return MigrationChecksums.sha256(SQL); }
    @Override public void execute() {
        if (!columns("session_merges").contains("protocol_version")) {
            for (String statement : SQL.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        }
        validate();
    }
    private List<String> columns(String table) {
        return jdbc.queryForList("PRAGMA table_info(" + table + ")").stream()
                .map(row -> row.get("name").toString()).toList();
    }
    @Override public void validate() {
        new V024_CreateSessionMerges(jdbc).validate();
        if (!columns("session_merges").containsAll(List.of("protocol_version", "active_slot", "run_epoch",
                "snapshot_version", "snapshot_hash", "handoff_hash", "execution_json", "error_code", "retry_at"))
                || !columns("session_merge_units").containsAll(List.of("operation_id", "unit_id", "stage", "ordinal",
                "input_hash", "input_json", "processor_version", "model", "state", "attempt_count", "run_epoch",
                "result_path", "result_hash", "error_code", "updated_at"))
                || !columns("session_merge_attempts").containsAll(List.of("request_id", "operation_id", "unit_id",
                "run_epoch", "model", "started_at", "finished_at", "outcome", "usage_json", "usage_reported", "estimated_cost_usd")))
            throw new IllegalStateException("V026 session merge schema incomplete");
        String schema = jdbc.queryForObject("SELECT sql FROM sqlite_master WHERE type='table' AND name='session_merges'", String.class);
        if (schema == null || !schema.contains("active_slot IS 1") || !schema.contains("handoff_hash IS NOT NULL"))
            throw new IllegalStateException("V026 session merge constraints missing");
        for (String name : List.of("uq_session_merges_active", "idx_session_merges_status",
                "idx_session_merge_units_next", "idx_session_merge_attempts_operation")) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name=?", Integer.class, name) != 1)
                throw new IllegalStateException("V026 session merge index missing: " + name);
        }
        for (String table : List.of("session_merge_units", "session_merge_attempts")) {
            if (jdbc.queryForList("PRAGMA foreign_key_list(" + table + ")").stream().noneMatch(row ->
                    "session_merges".equals(row.get("table")) && "operation_id".equals(row.get("from"))
                            && "CASCADE".equals(row.get("on_delete"))))
                throw new IllegalStateException("V026 session merge foreign key missing: " + table);
        }
    }
}
