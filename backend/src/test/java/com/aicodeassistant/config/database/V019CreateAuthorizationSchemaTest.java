package com.aicodeassistant.config.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V019CreateAuthorizationSchemaTest {
    @TempDir Path temp;

    @Test
    void createsFinalSchemaAndRejectsIllegalGrants() {
        JdbcTemplate jdbc = database();
        new V015_CreateInteractionSchema(jdbc).execute();
        new V018_CreateWebSocketBindingSchema(jdbc).execute();
        V019_CreateAuthorizationSchema migration = new V019_CreateAuthorizationSchema(jdbc);
        migration.execute();
        migration.validate();
        assertThat(jdbc.queryForList("PRAGMA table_info(interaction_requests)").stream()
                .anyMatch(row -> "authorization_context_json".equals(row.get("name")))).isTrue();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO permission_grants(grant_id,grant_kind,scope,delegation_policy,workspace_key,
                  authorization_schema_version,analyzer_id,tool_name,action,effects_json,capability_hash,
                  constraints_json,risk_class,created_at,expires_at)
                VALUES('bad','EXACT_GUARDED','WORKSPACE','ROOT_AND_DESCENDANTS','w',1,'bash-v1','Bash',
                  'execute','[]','cap','{}','GUARDED','2026-01-01T00:00:00Z','2027-01-01T00:00:00Z')
                """)).isInstanceOf(Exception.class);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO permission_grants(grant_id,grant_kind,scope,delegation_policy,workspace_key,
                  authorization_schema_version,analyzer_id,tool_name,action,effects_json,capability_hash,
                  constraints_json,risk_class,created_at,expires_at)
                VALUES('bad-bash-workspace','READ_CAPABILITY','WORKSPACE','ROOT_AND_DESCENDANTS','w',
                  1,'bash-v2','Bash','execute','[\"READ_RESOURCE\"]','cap','{}','GUARDED',
                  '2026-01-01T00:00:00Z','2027-01-01T00:00:00Z')
                """)).isInstanceOf(Exception.class);
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check(permission_grants)")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM websocket_session_binding", Integer.class)).isZero();
    }

    private JdbcTemplate database() {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + temp.resolve("migration.db"));
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("PRAGMA foreign_keys=ON");
        jdbc.execute("CREATE TABLE sessions(id TEXT PRIMARY KEY,working_dir TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE run_envelopes(id TEXT PRIMARY KEY,session_id TEXT NOT NULL,status TEXT NOT NULL)");
        return jdbc;
    }
}
