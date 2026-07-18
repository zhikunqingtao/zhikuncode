package com.aicodeassistant.config.database;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/** 为全新项目数据库创建最终权限架构。 */
@Component @Order(19)
public final class V019_CreateAuthorizationSchema implements Migration {
    private static final String CHECKSUM = MigrationChecksums.sha256("v019-create-final-authorization-schema-v1");
    private final JdbcTemplate jdbc;
    public V019_CreateAuthorizationSchema(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public String checksum() { return CHECKSUM; }

    @Override public void execute() {
        jdbc.execute("""
                CREATE TABLE permission_grants (
                  grant_id TEXT PRIMARY KEY,
                  grant_kind TEXT NOT NULL CHECK(grant_kind IN ('EXACT_GUARDED','READ_CAPABILITY','EDIT_CAPABILITY')),
                  scope TEXT NOT NULL CHECK(scope IN ('RUN','SESSION','WORKSPACE')),
                  delegation_policy TEXT NOT NULL CHECK(delegation_policy IN ('DIRECT_ONLY','ROOT_AND_DESCENDANTS')),
                  root_session_id TEXT REFERENCES sessions(id) ON DELETE CASCADE,
                  root_run_id TEXT REFERENCES run_envelopes(id) ON DELETE CASCADE,
                  actor_run_id TEXT REFERENCES run_envelopes(id) ON DELETE CASCADE,
                  workspace_key TEXT,
                  authorization_schema_version INTEGER NOT NULL CHECK(authorization_schema_version=1),
                  analyzer_id TEXT NOT NULL,
                  tool_name TEXT NOT NULL,
                  action TEXT NOT NULL,
                  effects_json TEXT NOT NULL,
                  operation_hash TEXT,
                  capability_hash TEXT,
                  constraints_json TEXT NOT NULL,
                  risk_class TEXT NOT NULL CHECK(risk_class IN ('SAFE','GUARDED')),
                  created_by_interaction_id TEXT REFERENCES interaction_requests(interaction_id) ON DELETE SET NULL,
                  created_at TEXT NOT NULL,
                  expires_at TEXT NOT NULL,
                  revoked_at TEXT,
                  version INTEGER NOT NULL DEFAULT 0 CHECK(version >= 0),
                  CHECK((grant_kind='EXACT_GUARDED' AND operation_hash IS NOT NULL AND capability_hash IS NULL
                         AND scope IN ('RUN','SESSION'))
                     OR (grant_kind IN ('READ_CAPABILITY','EDIT_CAPABILITY')
                         AND operation_hash IS NULL AND capability_hash IS NOT NULL)),
                  CHECK((scope='RUN' AND root_run_id IS NOT NULL AND root_session_id IS NULL AND workspace_key IS NULL)
                     OR (scope='SESSION' AND root_run_id IS NULL AND root_session_id IS NOT NULL AND workspace_key IS NULL)
                     OR (scope='WORKSPACE' AND root_run_id IS NULL AND root_session_id IS NULL AND workspace_key IS NOT NULL)),
                  CHECK((delegation_policy='DIRECT_ONLY' AND scope='RUN' AND actor_run_id IS NOT NULL)
                     OR (delegation_policy='ROOT_AND_DESCENDANTS' AND actor_run_id IS NULL)),
                  CHECK(scope != 'WORKSPACE' OR grant_kind IN ('READ_CAPABILITY','EDIT_CAPABILITY')),
                  CHECK(scope != 'WORKSPACE' OR analyzer_id != 'bash-v2')
                )
                """);
        jdbc.execute("""
                CREATE UNIQUE INDEX uq_active_exact_grant ON permission_grants(
                  scope,COALESCE(root_run_id,''),COALESCE(root_session_id,''),COALESCE(workspace_key,''),
                  COALESCE(actor_run_id,''),delegation_policy,authorization_schema_version,
                  analyzer_id,tool_name,action,operation_hash)
                WHERE revoked_at IS NULL AND grant_kind='EXACT_GUARDED'
                """);
        jdbc.execute("""
                CREATE UNIQUE INDEX uq_active_capability_grant ON permission_grants(
                  grant_kind,scope,COALESCE(root_run_id,''),COALESCE(root_session_id,''),
                  COALESCE(workspace_key,''),COALESCE(actor_run_id,''),delegation_policy,
                  authorization_schema_version,analyzer_id,tool_name,action,capability_hash)
                WHERE revoked_at IS NULL AND grant_kind IN ('READ_CAPABILITY','EDIT_CAPABILITY')
                """);
        jdbc.execute("CREATE INDEX idx_permission_grants_match ON permission_grants(scope,root_session_id,root_run_id,workspace_key,revoked_at,expires_at)");
    }

    @Override public void validate() {
        Set<String> columns = columns("permission_grants");
        if (!columns.containsAll(Set.of("grant_kind", "root_session_id", "root_run_id", "workspace_key",
                "operation_hash", "capability_hash", "constraints_json", "effects_json", "delegation_policy",
                "analyzer_id", "version"))) {
            throw new IllegalStateException("V019 permission grant schema incomplete");
        }
        if (!columns("interaction_requests").contains("authorization_context_json"))
            throw new IllegalStateException("V019 interaction authorization context missing");
        for (String index : Set.of("uq_active_exact_grant", "uq_active_capability_grant")) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name=?",
                    Integer.class, index);
            if (count == null || count != 1) throw new IllegalStateException("V019 index missing: " + index);
        }
        // V019 独占该权威表，因此在迁移校验阶段直接验证其外键与数据库完整性。
        if (!jdbc.queryForList("PRAGMA foreign_key_check(permission_grants)").isEmpty())
            throw new IllegalStateException("V019 permission grant foreign key check failed");
        String integrity = jdbc.queryForObject("PRAGMA integrity_check", String.class);
        if (!"ok".equalsIgnoreCase(integrity)) throw new IllegalStateException("V019 integrity check failed");
        String ddl = jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='permission_grants'", String.class);
        for (String required : Set.of("scope != 'WORKSPACE' OR analyzer_id != 'bash-v2'",
                "scope IN ('RUN','SESSION','WORKSPACE')", "risk_class IN ('SAFE','GUARDED')")) {
            if (ddl == null || !ddl.contains(required)) {
                throw new IllegalStateException("V019 permission grant CHECK missing: " + required);
            }
        }
    }

    private Set<String> columns(String table) {
        return jdbc.queryForList("PRAGMA table_info(" + table + ")").stream()
                .map(row -> String.valueOf(row.get("name"))).collect(Collectors.toSet());
    }
}
