package com.aicodeassistant.config.database;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Project-scoped persistent permission grants. */
@Component @Order(16)
public class V016_CreatePermissionGrantSchema implements Migration {
    private static final String CHECKSUM = "110bb572f9cb48dc4c32b57c77f005a1f113ef068e6a69b03aebdd950c967fdc";
    private final JdbcTemplate jdbc;
    public V016_CreatePermissionGrantSchema(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public String checksum() { return CHECKSUM; }
    @Override public void execute() {
        jdbc.execute("DROP TABLE IF EXISTS permission_rules");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS permission_grants (
                  grant_id TEXT PRIMARY KEY,
                  grant_kind TEXT NOT NULL CHECK(grant_kind IN ('STANDARD','CHILD_EXACT')),
                  scope TEXT NOT NULL CHECK(scope IN ('SESSION','WORKSPACE')),
                  session_id TEXT REFERENCES sessions(id) ON DELETE CASCADE,
                  workspace_hash TEXT,
                  tool_name TEXT NOT NULL,
                  action TEXT NOT NULL,
                  risk_class TEXT NOT NULL,
                  grant_key_hash TEXT NOT NULL,
                  canonical_cwd TEXT NOT NULL,
                  parent_session_id TEXT REFERENCES sessions(id) ON DELETE CASCADE,
                  parent_run_id TEXT REFERENCES run_envelopes(id) ON DELETE CASCADE,
                  child_session_id TEXT,
                  agent_type TEXT,
                  reason_code TEXT,
                  created_by_interaction_id TEXT NOT NULL REFERENCES interaction_requests(interaction_id) ON DELETE CASCADE,
                  created_at TEXT NOT NULL,
                  expires_at TEXT,
                  revoked_at TEXT,
                  last_matched_at TEXT,
                  CHECK((scope='SESSION' AND session_id IS NOT NULL AND workspace_hash IS NULL)
                     OR (scope='WORKSPACE' AND session_id IS NULL AND workspace_hash IS NOT NULL)),
                  CHECK((grant_kind='STANDARD' AND parent_session_id IS NULL AND parent_run_id IS NULL
                         AND child_session_id IS NULL AND agent_type IS NULL AND reason_code IS NULL)
                     OR (grant_kind='CHILD_EXACT' AND scope='SESSION' AND parent_session_id IS NOT NULL
                         AND parent_run_id IS NOT NULL AND child_session_id IS NOT NULL
                         AND agent_type IS NOT NULL AND reason_code IS NOT NULL AND session_id=parent_session_id))
                )
                """);
        jdbc.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_active_permission_grant ON permission_grants(
                  grant_kind,scope,COALESCE(session_id,''),COALESCE(workspace_hash,''),tool_name,action,
                  risk_class,grant_key_hash,COALESCE(parent_run_id,''),COALESCE(child_session_id,''))
                WHERE revoked_at IS NULL
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_permission_grant_session ON permission_grants(session_id,revoked_at,expires_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_permission_grant_workspace ON permission_grants(workspace_hash,revoked_at,expires_at)");
    }
    @Override public void validate() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='uq_active_permission_grant'", Integer.class);
        if (count == null || count != 1) throw new IllegalStateException("V016 permission grant unique index missing");
    }
}
