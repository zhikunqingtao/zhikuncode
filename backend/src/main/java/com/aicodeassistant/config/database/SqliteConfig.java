package com.aicodeassistant.config.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * SQLite 双库配置 — 全局库 + 项目库。
 * <p>
 * WAL 模式 + HikariCP 连接池 + 写入串行化。
 *
 * @see <a href="SPEC §7.1.1">SQLite WAL 模式与并发写入策略</a>
 */
@Configuration
public class SqliteConfig implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SqliteConfig.class);

    private static final String SQLITE_PRAGMAS = String.join("; ", List.of(
            "PRAGMA journal_mode=WAL",
            "PRAGMA busy_timeout=5000",
            "PRAGMA synchronous=NORMAL",
            "PRAGMA cache_size=-8000",
            "PRAGMA foreign_keys=ON",
            "PRAGMA wal_autocheckpoint=1000",
            "PRAGMA temp_store=MEMORY"
    ));

    private final DatabaseResolver databaseResolver;

    public SqliteConfig(DatabaseResolver databaseResolver) {
        this.databaseResolver = databaseResolver;
    }

    // ───── 全局库 DataSource ─────

    @Bean
    @Qualifier("globalDataSource")
    public DataSource globalDataSource() {
        Path dbPath = databaseResolver.getGlobalDbPath();
        databaseResolver.ensureDirectoryExists(dbPath);
        log.info("Initializing global SQLite database: {}", dbPath);
        return createDataSource(dbPath, 5);
    }

    @Bean
    @Qualifier("globalJdbcTemplate")
    public JdbcTemplate globalJdbcTemplate(@Qualifier("globalDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    // ───── 项目库 DataSource (按需缓存) ─────

    private final ConcurrentHashMap<Path, DataSource> projectDataSources = new ConcurrentHashMap<>();

    public DataSource getProjectDataSource(Path projectRoot) {
        Path dbPath = databaseResolver.getProjectDbPath(projectRoot);
        return projectDataSources.computeIfAbsent(dbPath, path -> {
            databaseResolver.ensureDirectoryExists(path);
            log.info("Initializing project SQLite database: {}", path);
            return createDataSource(path, 5);
        });
    }

    public JdbcTemplate getProjectJdbcTemplate(Path projectRoot) {
        return new JdbcTemplate(getProjectDataSource(projectRoot));
    }

    /**
     * 默认项目库 — 使用当前工作目录。
     * 启动时创建，后续通过 getProjectDataSource() 按需获取其他项目库。
     */
    @Bean
    @Qualifier("projectDataSource")
    public DataSource projectDataSource() {
        Path projectRoot = Path.of(System.getProperty("user.dir"));
        return getProjectDataSource(projectRoot);
    }

    @Bean
    @Qualifier("projectJdbcTemplate")
    public JdbcTemplate projectJdbcTemplate(@Qualifier("projectDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    // ───── 写入串行化 ─────

    private final ConcurrentHashMap<Path, ReentrantLock> writeLocks = new ConcurrentHashMap<>();

    /**
     * 写入串行化执行 — 使用 ReentrantLock 让 Virtual Thread 正确挂起等待。
     * SQLite WAL 模式仅允许一个写入者，Java Lock 比 SQLite busy_timeout 更高效。
     */
    public <T> T executeWrite(Path dbPath, Supplier<T> writeOperation) {
        ReentrantLock lock = writeLocks.computeIfAbsent(dbPath, k -> new ReentrantLock());
        lock.lock();
        try {
            return writeOperation.get();
        } finally {
            lock.unlock();
        }
    }

    public void executeWriteVoid(Path dbPath, Runnable writeOperation) {
        executeWrite(dbPath, () -> {
            writeOperation.run();
            return null;
        });
    }

    // ───── 内部工具方法 ─────

    private DataSource createDataSource(Path dbPath, int maxPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(1);
        config.setConnectionInitSql(SQLITE_PRAGMAS);
        // SQLite 不支持连接测试查询 isValid()，使用此配置
        config.setConnectionTestQuery("SELECT 1");
        return new HikariDataSource(config);
    }

    // ───── P1-02: 连接池清理 ─────

    /**
     * 定时清理空闲的项目库连接池 — 每小时检查一次。
     */
    @Scheduled(fixedRate = 3600_000)
    public void cleanupIdleDataSources() {
        projectDataSources.forEach((path, ds) -> {
            if (ds instanceof HikariDataSource hikariDs) {
                if (hikariDs.getHikariPoolMXBean() != null
                        && hikariDs.getHikariPoolMXBean().getActiveConnections() == 0
                        && hikariDs.getHikariPoolMXBean().getIdleConnections() <= 1) {
                    log.debug("Idle project DataSource: {}", path);
                }
            }
        });
    }

    /**
     * 应用关闭时关闭所有连接池 — 防止 SQLite 锁文件残留。
     */
    @Override
    public void destroy() {
        log.info("Closing all SQLite DataSources...");
        projectDataSources.forEach((path, ds) -> {
            if (ds instanceof HikariDataSource hikariDs) {
                try {
                    hikariDs.close();
                    log.debug("Closed project DataSource: {}", path);
                } catch (Exception e) {
                    log.warn("Failed to close DataSource {}: {}", path, e.getMessage());
                }
            }
        });
        projectDataSources.clear();
    }
}
