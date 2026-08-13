package com.aicodeassistant.config.database;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/** Root Run 级简洁工作台关联、验收条款与证据归属。 */
@Component
@Order(21)
public final class V021_CreateWorkbenchProjectionSchema implements Migration {
    private static final String CHECKSUM = MigrationChecksums.sha256(
            "v021-workbench-root-run-projection-v1");
    private final JdbcTemplate jdbc;

    public V021_CreateWorkbenchProjectionSchema(
            @Qualifier("projectJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override public String checksum() { return CHECKSUM; }

    @Override
    public void execute() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS run_workbench_bindings (
                  root_run_id TEXT PRIMARY KEY REFERENCES run_envelopes(id) ON DELETE CASCADE,
                  request_message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
                  result_message_id TEXT REFERENCES messages(id) ON DELETE SET NULL,
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS run_acceptance_criteria (
                  criterion_id TEXT PRIMARY KEY,
                  root_run_id TEXT NOT NULL REFERENCES run_envelopes(id) ON DELETE CASCADE,
                  ordinal INTEGER NOT NULL CHECK(ordinal >= 0),
                  criterion_type TEXT NOT NULL CHECK(criterion_type IN ('business')),
                  source_text TEXT NOT NULL,
                  status TEXT NOT NULL CHECK(status IN ('passed','failed','partial','not_verified')),
                  evidence_bundle_id TEXT REFERENCES evidence_bundles(bundle_id) ON DELETE SET NULL,
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL,
                  UNIQUE(root_run_id, ordinal)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_run_acceptance_root
                ON run_acceptance_criteria(root_run_id, ordinal)
                """);
        Set<String> evidenceColumns = columns("evidence_bundles");
        if (!evidenceColumns.contains("run_id")) {
            jdbc.execute("""
                    ALTER TABLE evidence_bundles ADD COLUMN run_id TEXT
                    REFERENCES run_envelopes(id) ON DELETE SET NULL
                    """);
        }
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_evidence_bundles_run
                ON evidence_bundles(run_id, created_at DESC)
                """);
    }

    @Override
    public void validate() {
        Set<String> binding = columns("run_workbench_bindings");
        Set<String> criteria = columns("run_acceptance_criteria");
        Set<String> evidence = columns("evidence_bundles");
        if (!binding.containsAll(Set.of(
                "root_run_id", "request_message_id", "result_message_id"))
                || !criteria.containsAll(Set.of(
                "criterion_id", "root_run_id", "ordinal", "criterion_type",
                "source_text", "status", "evidence_bundle_id"))
                || !evidence.contains("run_id")) {
            throw new IllegalStateException(
                    "V021 workbench projection schema postcondition failed");
        }
    }

    private Set<String> columns(String table) {
        return jdbc.queryForList("PRAGMA table_info('" + table + "')")
                .stream().map(row -> String.valueOf(row.get("name")))
                .collect(Collectors.toSet());
    }
}
