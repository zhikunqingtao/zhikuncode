package com.aicodeassistant.config.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * V013: ArtifactManifest 产物清单表。
 * 支持运行产物的完整性验证和 SHA-256 哈希校验。
 */
@Order(13)
@Component
public class V013_AddArtifactManifestSchema implements Migration {

    private static final Logger log = LoggerFactory.getLogger(V013_AddArtifactManifestSchema.class);

    private final JdbcTemplate projectJdbcTemplate;

    public V013_AddArtifactManifestSchema(@Qualifier("projectJdbcTemplate") JdbcTemplate projectJdbcTemplate) {
        this.projectJdbcTemplate = projectJdbcTemplate;
    }

    @Override
    public void execute() {
        log.info("Creating artifact_manifests and artifact_entries tables...");

        projectJdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS artifact_manifests (
                id              TEXT PRIMARY KEY,
                run_id          TEXT NOT NULL REFERENCES run_envelopes(id) ON DELETE CASCADE,
                session_id      TEXT NOT NULL,
                status          TEXT NOT NULL DEFAULT 'pending',
                total_files     INTEGER DEFAULT 0,
                verified_files  INTEGER DEFAULT 0,
                failed_files    INTEGER DEFAULT 0,
                created_at      TEXT NOT NULL,
                verified_at     TEXT
            )
        """);
        projectJdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS artifact_entries (
                id              TEXT PRIMARY KEY,
                manifest_id     TEXT NOT NULL REFERENCES artifact_manifests(id) ON DELETE CASCADE,
                file_path       TEXT NOT NULL,
                operation       TEXT NOT NULL,
                expected_hash   TEXT,
                actual_hash     TEXT,
                file_size       INTEGER,
                verified        INTEGER DEFAULT 0,
                mismatch_detail TEXT,
                created_at      TEXT NOT NULL
            )
        """);
        projectJdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_artifact_manifest_run ON artifact_manifests(run_id)");
        projectJdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_artifact_entries_manifest ON artifact_entries(manifest_id)");

        log.info("V013: artifact_manifests and artifact_entries tables created.");
    }
}
