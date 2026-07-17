package com.aicodeassistant.config.database;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContextException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationRunnerV2Test {

    @TempDir
    Path tempDir;

    private SqliteConfig sqliteConfig;
    private DataSource globalDataSource;

    @AfterEach
    void closeDataSources() {
        if (sqliteConfig != null) {
            sqliteConfig.destroy();
        }
        if (globalDataSource instanceof HikariDataSource hikari && !hikari.isClosed()) {
            hikari.close();
        }
    }

    @Test
    void appliesEachScopeToItsOwnDatabaseAndRecordsChecksum() {
        Fixture fixture = fixture();
        Migration global = migration(
                "test-global-v1", Migration.Scope.GLOBAL, "global-checksum",
                fixture.globalJdbc, "global_only");
        Migration project = migration(
                "test-project-v1", Migration.Scope.PROJECT, "project-checksum",
                fixture.projectJdbc, "project_only");

        fixture.runner(List.of(project, global)).run();

        assertThat(tableExists(fixture.globalJdbc, "global_only")).isTrue();
        assertThat(tableExists(fixture.globalJdbc, "project_only")).isFalse();
        assertThat(tableExists(fixture.projectJdbc, "project_only")).isTrue();
        assertThat(tableExists(fixture.projectJdbc, "global_only")).isFalse();
        assertThat(fixture.globalJdbc.queryForObject(
                "SELECT checksum FROM schema_migrations WHERE migration_id = ?",
                String.class, "test-global-v1")).isEqualTo("global-checksum");
        assertThat(fixture.projectJdbc.queryForObject(
                "SELECT checksum FROM schema_migrations WHERE migration_id = ?",
                String.class, "test-project-v1")).isEqualTo("project-checksum");
    }

    @Test
    void refusesStartupWhenAnAppliedMigrationChecksumChanges() {
        Fixture fixture = fixture();
        fixture.runner(List.of(migration(
                "immutable-v1", Migration.Scope.GLOBAL, "checksum-a",
                fixture.globalJdbc, "immutable_table"))).run();

        assertThatThrownBy(() -> fixture.runner(List.of(migration(
                "immutable-v1", Migration.Scope.GLOBAL, "checksum-b",
                fixture.globalJdbc, "immutable_table"))).run())
                .isInstanceOf(ApplicationContextException.class)
                .hasMessageContaining("refusing to start")
                .hasRootCauseMessage("Checksum mismatch for immutable-v1");
    }

    @Test
    void rollsBackSchemaAndHistoryWhenPostConditionFails() {
        Fixture fixture = fixture();
        Migration invalid = new Migration() {
            @Override
            public String id() {
                return "invalid-v1";
            }

            @Override
            public Scope scope() {
                return Scope.GLOBAL;
            }

            @Override
            public String checksum() {
                return "invalid-checksum";
            }

            @Override
            public void execute() {
                fixture.globalJdbc.execute("CREATE TABLE must_rollback(id INTEGER PRIMARY KEY)");
            }

            @Override
            public void validate() {
                throw new IllegalStateException("post-condition failed");
            }
        };

        assertThatThrownBy(() -> fixture.runner(List.of(invalid)).run())
                .isInstanceOf(ApplicationContextException.class)
                .hasRootCauseMessage("post-condition failed");

        assertThat(tableExists(fixture.globalJdbc, "must_rollback")).isFalse();
        if (tableExists(fixture.globalJdbc, "schema_migrations")) {
            assertThat(fixture.globalJdbc.queryForObject(
                    "SELECT COUNT(*) FROM schema_migrations WHERE migration_id = ?",
                    Integer.class, "invalid-v1")).isZero();
        }
    }

    private Fixture fixture() {
        DatabaseResolver resolver = new DatabaseResolver(
                tempDir.resolve("global.db").toString(),
                tempDir.resolve("project-root").toString());
        sqliteConfig = new SqliteConfig(resolver);
        globalDataSource = sqliteConfig.globalDataSource();
        DataSource projectDataSource = sqliteConfig.getProjectDataSource(Path.of(System.getProperty("user.dir")));
        return new Fixture(
                resolver,
                sqliteConfig,
                new JdbcTemplate(globalDataSource),
                new JdbcTemplate(projectDataSource),
                new DataSourceTransactionManager(globalDataSource),
                new DataSourceTransactionManager(projectDataSource));
    }

    private Migration migration(String id, Migration.Scope scope, String checksum,
                                JdbcTemplate jdbc, String tableName) {
        return new Migration() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Scope scope() {
                return scope;
            }

            @Override
            public String checksum() {
                return checksum;
            }

            @Override
            public void execute() {
                jdbc.execute("CREATE TABLE " + tableName + "(id INTEGER PRIMARY KEY)");
            }

            @Override
            public void validate() {
                if (!tableExists(jdbc, tableName)) {
                    throw new IllegalStateException("missing table " + tableName);
                }
            }
        };
    }

    private static boolean tableExists(JdbcTemplate jdbc, String tableName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                Integer.class, tableName);
        return count != null && count == 1;
    }

    private record Fixture(
            DatabaseResolver resolver,
            SqliteConfig sqliteConfig,
            JdbcTemplate globalJdbc,
            JdbcTemplate projectJdbc,
            DataSourceTransactionManager globalTx,
            DataSourceTransactionManager projectTx) {

        MigrationRunner runner(List<Migration> migrations) {
            return new MigrationRunner(
                    migrations,
                    globalJdbc,
                    projectJdbc,
                    globalTx,
                    projectTx,
                    sqliteConfig,
                    resolver);
        }
    }
}
