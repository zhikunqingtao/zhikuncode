package com.aicodeassistant.authorization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationSubjectResolverTest {
    @TempDir Path temp;

    @Test
    void childUsesPersistedRootSessionAndWorkspace() throws Exception {
        JdbcTemplate jdbc = database();
        jdbc.update("INSERT INTO sessions(id,working_dir) VALUES(?,?)", "root-session", temp.toString());
        jdbc.update("INSERT INTO run_envelopes(id,session_id,parent_run_id) VALUES('root','root-session',NULL)");
        jdbc.update("INSERT INTO run_envelopes(id,session_id,parent_run_id) VALUES('child','child-session','root')");

        AuthorizationSubject resolved = new AuthorizationSubjectResolver(jdbc).resolve("child");
        assertThat(resolved.rootRunId()).isEqualTo("root");
        assertThat(resolved.rootSessionId()).isEqualTo("root-session");
        assertThat(resolved.currentRunId()).isEqualTo("child");
        assertThat(resolved.authorizationRoot()).isEqualTo(temp.toRealPath());
    }

    @Test
    void missingRootSessionFailsClosedEvenWhenRunChainExists() {
        JdbcTemplate jdbc = database();
        jdbc.update("INSERT INTO run_envelopes(id,session_id,parent_run_id) VALUES('root','missing',NULL)");

        assertThatThrownBy(() -> new AuthorizationSubjectResolver(jdbc).resolve("root"))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Root session");
    }

    @Test
    void missingAndCyclicParentChainsFailClosed() {
        JdbcTemplate jdbc = database();
        jdbc.update("INSERT INTO sessions(id,working_dir) VALUES(?,?)", "s", temp.toString());
        jdbc.update("INSERT INTO run_envelopes(id,session_id,parent_run_id) VALUES('a','s','b')");
        jdbc.update("INSERT INTO run_envelopes(id,session_id,parent_run_id) VALUES('b','s','a')");
        AuthorizationSubjectResolver resolver = new AuthorizationSubjectResolver(jdbc);
        assertThatThrownBy(() -> resolver.resolve("a"))
                .isInstanceOf(AuthorizationException.class).hasMessageContaining("cycle");
        assertThatThrownBy(() -> resolver.resolve("missing"))
                .isInstanceOf(AuthorizationException.class).hasMessageContaining("missing parent");
    }

    private JdbcTemplate database() {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + temp.resolve("subject.db"));
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sessions(id TEXT PRIMARY KEY,working_dir TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE run_envelopes(id TEXT PRIMARY KEY,session_id TEXT NOT NULL,parent_run_id TEXT)");
        return jdbc;
    }
}
