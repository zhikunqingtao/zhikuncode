package com.aicodeassistant.config.database;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteConfigWriteCoordinationTest {
    @TempDir Path temp;
    private SqliteConfig sqlite;

    @AfterEach void close() { if (sqlite != null) sqlite.destroy(); }

    @Test
    void projectDatabaseUsesSingleConnectionAsTheProcessWideSerializationBoundary() {
        sqlite = new SqliteConfig(new DatabaseResolver("", temp.toString()));
        HikariDataSource dataSource = (HikariDataSource) sqlite.getProjectDataSource(temp);

        assertThat(dataSource.getMaximumPoolSize()).isEqualTo(1);
        assertThat(sqlite.getProjectDataSource(temp.resolve("different-call-site"))).isSameAs(dataSource);
    }

    @Test
    void sqliteBusyIsReportedAsAuthorizationStoreBusy() {
        sqlite = new SqliteConfig(new DatabaseResolver("", temp.toString()));

        assertThatThrownBy(() -> sqlite.executeWriteBounded(
                temp.resolve("data.db"), Duration.ofSeconds(1),
                () -> { throw new RuntimeException(new java.sql.SQLException("SQLITE_BUSY", null, 5)); }))
                .isInstanceOf(SqliteConfig.DatabaseWriteUnavailableException.class)
                .extracting(error -> ((SqliteConfig.DatabaseWriteUnavailableException) error).code())
                .isEqualTo("AUTHORIZATION_STORE_BUSY");
    }
}
