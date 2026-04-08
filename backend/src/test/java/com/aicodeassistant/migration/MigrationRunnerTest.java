package com.aicodeassistant.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MigrationRunner 单元测试。
 */
class MigrationRunnerTest {

    @Test
    @DisplayName("空迁移列表不报错")
    void emptyMigrations() {
        MigrationRunner runner = new MigrationRunner(List.of());
        runner.runAll();
        assertEquals(0, runner.getMigrationCount());
        assertTrue(runner.getExecutionLog().isEmpty());
    }

    @Test
    @DisplayName("shouldRun=true 的迁移被执行")
    void executeMigration() {
        AtomicBoolean executed = new AtomicBoolean(false);
        Migration migration = new Migration() {
            @Override public String name() { return "test-migration"; }
            @Override public boolean shouldRun() { return true; }
            @Override public void execute() { executed.set(true); }
        };

        MigrationRunner runner = new MigrationRunner(List.of(migration));
        runner.runAll();

        assertTrue(executed.get());
        assertEquals(1, runner.getExecutionLog().size());
        assertEquals(MigrationRunner.MigrationStatus.SUCCESS,
                runner.getExecutionLog().getFirst().status());
    }

    @Test
    @DisplayName("shouldRun=false 的迁移被跳过")
    void skipMigration() {
        AtomicBoolean executed = new AtomicBoolean(false);
        Migration migration = new Migration() {
            @Override public String name() { return "skip-migration"; }
            @Override public boolean shouldRun() { return false; }
            @Override public void execute() { executed.set(true); }
        };

        MigrationRunner runner = new MigrationRunner(List.of(migration));
        runner.runAll();

        assertFalse(executed.get());
        assertEquals(MigrationRunner.MigrationStatus.SKIPPED,
                runner.getExecutionLog().getFirst().status());
    }

    @Test
    @DisplayName("迁移失败不阻止后续迁移")
    void failureDoesNotBlock() {
        List<String> executionOrder = new ArrayList<>();

        Migration failing = new Migration() {
            @Override public String name() { return "failing"; }
            @Override public boolean shouldRun() { return true; }
            @Override public void execute() {
                executionOrder.add("failing");
                throw new RuntimeException("boom");
            }
        };

        Migration succeeding = new Migration() {
            @Override public String name() { return "succeeding"; }
            @Override public boolean shouldRun() { return true; }
            @Override public void execute() { executionOrder.add("succeeding"); }
        };

        MigrationRunner runner = new MigrationRunner(List.of(failing, succeeding));
        runner.runAll();

        assertEquals(List.of("failing", "succeeding"), executionOrder);

        var log = runner.getExecutionLog();
        assertEquals(2, log.size());
        assertEquals(MigrationRunner.MigrationStatus.FAILED, log.get(0).status());
        assertEquals(MigrationRunner.MigrationStatus.SUCCESS, log.get(1).status());
        assertNotNull(log.get(0).error());
    }

    @Test
    @DisplayName("迁移按注册顺序执行")
    void executionOrder() {
        List<String> order = new ArrayList<>();

        MigrationRunner runner = new MigrationRunner(List.of(
                createMigration("first", order),
                createMigration("second", order),
                createMigration("third", order)
        ));
        runner.runAll();

        assertEquals(List.of("first", "second", "third"), order);
    }

    @Test
    @DisplayName("getMigrationCount 返回正确数量")
    void migrationCount() {
        MigrationRunner runner = new MigrationRunner(List.of(
                createMigration("a", new ArrayList<>()),
                createMigration("b", new ArrayList<>())
        ));
        assertEquals(2, runner.getMigrationCount());
    }

    private Migration createMigration(String name, List<String> tracker) {
        return new Migration() {
            @Override public String name() { return name; }
            @Override public boolean shouldRun() { return true; }
            @Override public void execute() { tracker.add(name); }
        };
    }
}
