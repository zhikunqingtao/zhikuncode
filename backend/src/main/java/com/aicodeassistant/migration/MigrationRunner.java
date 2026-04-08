package com.aicodeassistant.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * 版本迁移运行器 — 在应用启动时执行所有待运行的迁移。
 * <p>
 * 对照 SPEC section 3.5.5 的版本迁移框架设计:
 * <ul>
 *     <li>迁移按注册顺序执行</li>
 *     <li>每个迁移是幂等的</li>
 *     <li>迁移失败不阻止启动</li>
 *     <li>迁移完成后通过清理旧字段避免二次触发</li>
 * </ul>
 *
 * @see <a href="SPEC section 3.5.5">版本迁移框架</a>
 */
@Component("versionMigrationRunner")
public class MigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);

    private final List<Migration> migrations;
    private final List<MigrationRecord> executionLog = new ArrayList<>();

    /**
     * Spring 自动注入所有 Migration Bean。
     * 如果没有迁移 Bean 则使用空列表。
     */
    public MigrationRunner(List<Migration> migrations) {
        this.migrations = migrations != null ? migrations : List.of();
    }

    /**
     * 在应用启动后执行所有待运行的迁移。
     */
    @PostConstruct
    public void runAll() {
        if (migrations.isEmpty()) {
            log.debug("No migrations registered");
            return;
        }

        log.info("Running {} registered migration(s)...", migrations.size());
        int executed = 0;
        int skipped = 0;
        int failed = 0;

        for (Migration migration : migrations) {
            try {
                if (migration.shouldRun()) {
                    log.info("Executing migration: {}", migration.name());
                    long startTime = System.currentTimeMillis();

                    migration.execute();

                    long duration = System.currentTimeMillis() - startTime;
                    log.info("Migration completed: {} ({}ms)", migration.name(), duration);

                    executionLog.add(new MigrationRecord(
                            migration.name(), MigrationStatus.SUCCESS, duration, null));
                    executed++;
                } else {
                    log.debug("Migration skipped (not needed): {}", migration.name());
                    executionLog.add(new MigrationRecord(
                            migration.name(), MigrationStatus.SKIPPED, 0, null));
                    skipped++;
                }
            } catch (Exception e) {
                log.error("Migration failed: {} — {}", migration.name(), e.getMessage(), e);
                executionLog.add(new MigrationRecord(
                        migration.name(), MigrationStatus.FAILED, 0, e.getMessage()));
                failed++;
                // 不阻止启动 — 继续执行其他迁移
            }
        }

        log.info("Migration summary: {} executed, {} skipped, {} failed (total: {})",
                executed, skipped, failed, migrations.size());
    }

    /**
     * 获取迁移执行日志。
     */
    public List<MigrationRecord> getExecutionLog() {
        return List.copyOf(executionLog);
    }

    /**
     * 获取已注册迁移数量。
     */
    public int getMigrationCount() {
        return migrations.size();
    }

    // ===== 内部类型 =====

    public enum MigrationStatus {
        SUCCESS,
        SKIPPED,
        FAILED
    }

    public record MigrationRecord(
            String name,
            MigrationStatus status,
            long durationMs,
            String error
    ) {}
}
