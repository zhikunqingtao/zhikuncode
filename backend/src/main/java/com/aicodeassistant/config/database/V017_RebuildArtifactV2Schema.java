package com.aicodeassistant.config.database;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Explicit declare/seal/verify artifact schema; no event-path inference. */
@Component @Order(17)
public class V017_RebuildArtifactV2Schema implements Migration {
    // SHA-256 frozen for the V2 DDL below. Any DDL edit must deliberately update it.
    private static final String CHECKSUM = MigrationChecksums.sha256("v017-artifact-v2-workspace-root-locking");
    private final JdbcTemplate jdbc;
    public V017_RebuildArtifactV2Schema(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public String checksum() { return CHECKSUM; }
    @Override public void execute() {
        jdbc.execute("DELETE FROM artifact_entries");
        jdbc.execute("DELETE FROM artifact_manifests");
        jdbc.execute("DROP TABLE artifact_entries");
        jdbc.execute("DROP TABLE artifact_manifests");
        jdbc.execute("""
                CREATE TABLE artifact_manifests(
                  manifest_id TEXT PRIMARY KEY,
                  run_id TEXT NOT NULL UNIQUE REFERENCES run_envelopes(id) ON DELETE CASCADE,
                  session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                  workspace_root TEXT NOT NULL,
                  state TEXT NOT NULL CHECK(state IN ('open','sealed','verified','partial','failed','unverified')),
                  created_at TEXT NOT NULL, updated_at TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE artifact_entries(
                  artifact_id TEXT PRIMARY KEY,
                  manifest_id TEXT NOT NULL REFERENCES artifact_manifests(manifest_id) ON DELETE CASCADE,
                  tool_use_id TEXT NOT NULL,
                  canonical_path TEXT NOT NULL,
                  operation TEXT NOT NULL CHECK(operation IN ('created','modified','deleted')),
                  state TEXT NOT NULL CHECK(state IN ('declared','sealed','integrity_verified','content_verified','unverified','unverified_size_limit','failed')),
                  sealed_hash TEXT,
                  actual_hash TEXT,
                  file_size INTEGER,
                  required_validator_id TEXT,
                  validator_result_json TEXT,
                  failure_code TEXT,
                  created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                  UNIQUE(manifest_id,canonical_path),
                  CHECK((state='declared' AND sealed_hash IS NULL) OR state!='declared'))
                """);
        jdbc.execute("CREATE INDEX idx_artifact_manifest_run ON artifact_manifests(run_id)");
        jdbc.execute("CREATE INDEX idx_artifact_entries_manifest ON artifact_entries(manifest_id)");
    }
    @Override public void validate() {
        Integer tables=jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('artifact_manifests','artifact_entries')",Integer.class);
        Integer index=jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_artifact_manifest_run'",Integer.class);
        if(tables==null||tables!=2||index==null||index!=1) throw new IllegalStateException("V017 artifact V2 postcondition failed");
    }
}
