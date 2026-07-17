package com.aicodeassistant.config.database;

/**
 * 数据库迁移接口 — 所有迁移脚本必须实现。
 * 迁移应为幂等操作（使用 IF NOT EXISTS）。
 *
 */
public interface Migration {
    enum Scope { GLOBAL, PROJECT }

    /** Stable identifier persisted in schema_migrations. */
    default String id() {
        return getClass().getName();
    }

    /** Existing migrations are project-scoped unless explicitly declared global. */
    default Scope scope() {
        return Scope.PROJECT;
    }

    /**
     * Stable checksum. New migrations should override this with a SHA-256 of their frozen SQL.
     * The class name fallback is intentionally stable for the already released idempotent migrations.
     */
    default String checksum() {
        return MigrationChecksums.sha256(id());
    }

    void execute();

    /** Post-condition checked in the same transaction as execute(). */
    default void validate() {
        // Existing idempotent migrations predate post-condition hooks.
    }
}
