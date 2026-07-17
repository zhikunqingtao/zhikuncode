package com.aicodeassistant.config.database;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContextException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Single authoritative migration runner for both SQLite databases.
 * A checksum mismatch, failed migration, or failed post-condition aborts startup.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MigrationRunner {
    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);
    private static final String HISTORY_SQL = """
            CREATE TABLE IF NOT EXISTS schema_migrations (
                migration_id TEXT PRIMARY KEY,
                checksum TEXT NOT NULL,
                applied_at TEXT NOT NULL
            )
            """;

    private final List<Migration> migrations;
    private final JdbcTemplate globalJdbc;
    private final JdbcTemplate projectJdbc;
    private final TransactionTemplate globalTx;
    private final TransactionTemplate projectTx;
    private final SqliteConfig sqliteConfig;
    private final DatabaseResolver databaseResolver;

    public MigrationRunner(
            List<Migration> migrations,
            @Qualifier("globalJdbcTemplate") JdbcTemplate globalJdbc,
            @Qualifier("projectJdbcTemplate") JdbcTemplate projectJdbc,
            @Qualifier("globalTransactionManager") PlatformTransactionManager globalTransactionManager,
            @Qualifier("projectTransactionManager") PlatformTransactionManager projectTransactionManager,
            SqliteConfig sqliteConfig,
            DatabaseResolver databaseResolver) {
        this.migrations = migrations.stream()
                .sorted(Comparator.comparingInt(MigrationRunner::orderOf)
                        .thenComparing(Migration::id))
                .toList();
        this.globalJdbc = globalJdbc;
        this.projectJdbc = projectJdbc;
        this.globalTx = new TransactionTemplate(globalTransactionManager);
        this.projectTx = new TransactionTemplate(projectTransactionManager);
        this.sqliteConfig = sqliteConfig;
        this.databaseResolver = databaseResolver;
    }

    private static int orderOf(Migration migration) {
        Order order = migration.getClass().getAnnotation(Order.class);
        return order == null ? Integer.MAX_VALUE : order.value();
    }

    @PostConstruct
    public void run() {
        try {
            runScope(Migration.Scope.GLOBAL, globalJdbc, globalTx,
                    databaseResolver.getGlobalDbPath());
            Path projectRoot = Path.of(System.getProperty("user.dir"));
            runScope(Migration.Scope.PROJECT, projectJdbc, projectTx,
                    databaseResolver.getProjectDbPath(projectRoot));
        } catch (RuntimeException failure) {
            throw new ApplicationContextException(
                    "Database migration failed; refusing to start with a partially migrated schema", failure);
        }
    }

    private void runScope(Migration.Scope scope, JdbcTemplate jdbc,
                          TransactionTemplate tx, Path dbPath) {
        List<Migration> scoped = migrations.stream().filter(m -> m.scope() == scope).toList();
        sqliteConfig.executeWriteVoid(dbPath, () -> tx.executeWithoutResult(status -> {
            jdbc.execute(HISTORY_SQL);
            Map<String, String> applied = jdbc.query(
                    "SELECT migration_id, checksum FROM schema_migrations",
                    rs -> {
                        java.util.HashMap<String, String> result = new java.util.HashMap<>();
                        while (rs.next()) result.put(rs.getString(1), rs.getString(2));
                        return result;
                    });
            for (Migration migration : scoped) {
                String previous = applied.get(migration.id());
                if (previous != null) {
                    if (!previous.equals(migration.checksum())) {
                        throw new IllegalStateException("Checksum mismatch for " + migration.id());
                    }
                    migration.validate();
                    continue;
                }
                log.info("Applying {} migration {}", scope, migration.id());
                migration.execute();
                migration.validate();
                jdbc.update("INSERT INTO schema_migrations(migration_id, checksum, applied_at) VALUES(?,?,?)",
                        migration.id(), migration.checksum(), Instant.now().toString());
            }
        }));
        log.info("Validated {} {} migrations", scoped.size(), scope);
    }
}
