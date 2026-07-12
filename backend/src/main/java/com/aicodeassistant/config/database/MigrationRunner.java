package com.aicodeassistant.config.database;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 迁移运行器 — 启动时按 @Order 顺序执行所有迁移。
 * 任何迁移失败仅记录日志，不阻断应用启动。
 *
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);

    private final List<Migration> migrations;

    public MigrationRunner(List<Migration> migrations) {
        this.migrations = migrations.stream()
                .sorted(Comparator.comparingInt(m -> {
                    Order order = m.getClass().getAnnotation(Order.class);
                    return order != null ? order.value() : Integer.MAX_VALUE;
                }))
                .toList();
    }

    @PostConstruct
    public void run() {
        log.info("Running {} database migrations...", migrations.size());
        for (Migration migration : migrations) {
            String name = migration.getClass().getSimpleName();
            try {
                migration.execute();
                log.info("Migration {} completed successfully", name);
            } catch (Exception e) {
                log.error("Migration {} failed (non-fatal): {}", name, e.getMessage());
            }
        }
        log.info("Database migration complete");
    }
}
