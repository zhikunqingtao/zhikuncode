package com.aicodeassistant.config.database;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V025AddSessionPermissionModeTest {
    @TempDir Path temp;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + temp.resolve("migration.db"));
        jdbc = new JdbcTemplate(source);
        new V002_InitProjectSchema(jdbc).execute();
    }

    @Test
    void oldAndNewSessionsDefaultToStandardWithoutChangingExistingData() {
        insertSession("old");
        V025_AddSessionPermissionMode migration = new V025_AddSessionPermissionMode(jdbc);
        migration.execute();
        migration.validate();
        insertSession("new");

        assertThat(jdbc.queryForList("SELECT permission_mode FROM sessions ORDER BY id", String.class))
                .containsExactly("DEFAULT", "DEFAULT");
        assertThat(jdbc.queryForObject("SELECT model FROM sessions WHERE id='old'", String.class))
                .isEqualTo("kimi-k3");
        jdbc.update("UPDATE sessions SET permission_mode='AUTO_APPROVE' WHERE id='old'");
        migration.execute();
        migration.validate();
        assertThat(jdbc.queryForObject("SELECT permission_mode FROM sessions WHERE id='old'", String.class))
                .isEqualTo("AUTO_APPROVE");
    }

    @Test
    void rejectsInvalidAndNullModes() {
        new V025_AddSessionPermissionMode(jdbc).execute();
        insertSession("session");
        assertThatThrownBy(() -> jdbc.update("UPDATE sessions SET permission_mode='INVALID'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE sessions SET permission_mode=NULL"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void validationRejectsANullableColumnWithoutTheDefault() {
        jdbc.execute("ALTER TABLE sessions ADD COLUMN permission_mode TEXT");
        assertThatThrownBy(() -> new V025_AddSessionPermissionMode(jdbc).validate())
                .isInstanceOf(IllegalStateException.class);
    }

    private void insertSession(String id) {
        jdbc.update("INSERT INTO sessions(id,model,working_dir,created_at,updated_at) VALUES(?,?,?,?,?)",
                id, "kimi-k3", "/workspace", "2026-09-20T00:00:00Z", "2026-09-20T00:00:00Z");
    }
}
