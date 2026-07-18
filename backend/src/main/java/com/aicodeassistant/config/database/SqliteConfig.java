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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * SQLite 双库配置 — 全局库 + 项目库。
 * <p>
 * WAL 模式 + HikariCP 连接池 + 写入串行化。
 *
 */
@Configuration
public class SqliteConfig implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SqliteConfig.class);

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

    @Bean("globalTransactionManager")
    public PlatformTransactionManager globalTransactionManager(
            @Qualifier("globalDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    // ───── 项目库 DataSource (按需缓存) ─────

    private final ConcurrentHashMap<Path, DataSource> projectDataSources = new ConcurrentHashMap<>();

    public DataSource getProjectDataSource(Path projectRoot) {
        Path dbPath = databaseIdentity(databaseResolver.getProjectDbPath(projectRoot));
        return projectDataSources.computeIfAbsent(dbPath, path -> {
            databaseResolver.ensureDirectoryExists(path);
            log.info("Initializing project SQLite database: {}", path);
            // 本地单实例只使用一个项目库连接。所有 Repository 即使未显式进入 Java 写锁，
            // 也会在连接池边界串行访问 SQLite，避免虚拟线程并发造成第二个进程内 writer。
            return createDataSource(path, 1);
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

    @Bean("projectTransactionManager")
    public PlatformTransactionManager projectTransactionManager(
            @Qualifier("projectDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    // ───── 写入串行化 ─────

    private final ConcurrentHashMap<Path, ReentrantLock> writeLocks = new ConcurrentHashMap<>();

    /**
     * 写入串行化执行 — 使用 ReentrantLock 让 Virtual Thread 正确挂起等待。
     * SQLite WAL 模式仅允许一个写入者，Java Lock 比 SQLite busy_timeout 更高效。
     */
    public <T> T executeWrite(Path dbPath, Supplier<T> writeOperation) {
        Path identity = databaseIdentity(dbPath);
        ReentrantLock lock = writeLocks.computeIfAbsent(identity, k -> new ReentrantLock(true));
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

    /**
     * 执行带硬等待上限的运行时数据库写入；启动迁移仍应使用无此上限的 executeWrite。
     *
     * @param dbPath 数据库文件路径，也是进程内写锁的隔离键
     * @param timeout 获取写锁的最大等待时间，必须为正数
     * @param writeOperation 获得写锁后执行的短写操作
     * @return 写操作结果
     * @throws DatabaseWriteUnavailableException 等待超时或线程被中断时抛出
     */
    public <T> T executeWriteBounded(Path dbPath, Duration timeout, Supplier<T> writeOperation) {
        Objects.requireNonNull(dbPath, "dbPath");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(writeOperation, "writeOperation");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Database write timeout must be positive");
        }
        Path identity = databaseIdentity(dbPath);
        ReentrantLock lock = writeLocks.computeIfAbsent(identity, k -> new ReentrantLock(true));
        final boolean acquired;
        try {
            acquired = lock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DatabaseWriteUnavailableException("AUTHORIZATION_CANCELLED", interrupted);
        }
        if (!acquired) throw new DatabaseWriteUnavailableException("AUTHORIZATION_STORE_BUSY");
        try {
            return writeOperation.get();
        } catch (RuntimeException failure) {
            if (isSqliteBusy(failure)) {
                throw new DatabaseWriteUnavailableException("AUTHORIZATION_STORE_BUSY", failure);
            }
            throw failure;
        } finally {
            lock.unlock();
        }
    }

    public static final class DatabaseWriteUnavailableException extends RuntimeException {
        private final String code;
        public DatabaseWriteUnavailableException(String code) { super(code); this.code = code; }
        public DatabaseWriteUnavailableException(String code, Throwable cause) { super(code, cause); this.code = code; }
        public String code() { return code; }
    }

    // ───── 内部工具方法 ─────

    private DataSource createDataSource(Path dbPath, int maxPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5_000);
        // Hikari 的 connectionInitSql 只支持单条语句；分号拼接 PRAGMA 会让驱动只执行第一条，
        // 从而静默关闭外键。使用 Xerial 连接属性确保池内每个连接采用相同策略。
        org.sqlite.SQLiteConfig sqlite = new org.sqlite.SQLiteConfig();
        sqlite.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);
        sqlite.setBusyTimeout(5_000);
        sqlite.setSynchronous(org.sqlite.SQLiteConfig.SynchronousMode.NORMAL);
        sqlite.setCacheSize(-8_000);
        sqlite.enforceForeignKeys(true);
        sqlite.setTempStore(org.sqlite.SQLiteConfig.TempStore.MEMORY);
        config.setDataSourceProperties(sqlite.toProperties());
        // SQLite 不支持连接测试查询 isValid()，使用此配置
        config.setConnectionTestQuery("SELECT 1");
        return new HikariDataSource(config);
    }

    /** 同一物理数据库必须映射到同一个连接池和写锁键。 */
    private static Path databaseIdentity(Path dbPath) {
        Path absolute = dbPath.toAbsolutePath().normalize();
        Path existingAncestor = absolute;
        Deque<Path> missingSegments = new ArrayDeque<>();

        // 数据库父目录可能在首次解析后才被创建。必须从最近存在的真实祖先开始重建路径，
        // 否则 macOS 上 /var 与 /private/var 的别名会为同一数据库生成两个连接池和两把写锁。
        while (existingAncestor != null
                && !Files.exists(existingAncestor, LinkOption.NOFOLLOW_LINKS)) {
            Path fileName = existingAncestor.getFileName();
            if (fileName != null) missingSegments.addFirst(fileName);
            existingAncestor = existingAncestor.getParent();
        }

        try {
            Path canonical = existingAncestor == null ? absolute : existingAncestor.toRealPath();
            for (Path segment : missingSegments) canonical = canonical.resolve(segment);
            return canonical.normalize();
        } catch (Exception ignored) {
            // 实际路径不可解析时退化为绝对规范路径，后续 SQLite 仍会报告准确的打开错误。
        }
        return absolute;
    }

    private static boolean isSqliteBusy(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException sql
                    && (sql.getErrorCode() == 5 || sql.getErrorCode() == 6)) return true;
            String message = current.getMessage();
            if (message != null && (message.contains("SQLITE_BUSY") || message.contains("SQLITE_LOCKED"))) {
                return true;
            }
        }
        return false;
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
