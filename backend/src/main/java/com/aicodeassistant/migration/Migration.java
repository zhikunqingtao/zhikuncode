package com.aicodeassistant.migration;

/**
 * 版本迁移接口 — 定义单个迁移操作。
 * <p>
 * 每个迁移是幂等的（重复执行不会重复修改）。
 * 迁移按注册顺序执行。
 * 迁移失败不阻止启动（catch + 日志上报）。
 *
 * @see <a href="SPEC section 3.5.5">版本迁移框架</a>
 */
public interface Migration {

    /**
     * 迁移名称 — 用于日志和追踪。
     */
    String name();

    /**
     * 是否应该运行此迁移。
     * <p>
     * 实现应检查迁移前提条件（如旧字段是否存在）。
     * 幂等性要求: 如果迁移已完成（旧字段已清理），应返回 false。
     */
    boolean shouldRun();

    /**
     * 执行迁移。
     * <p>
     * 迁移步骤:
     * <ol>
     *     <li>读取旧配置</li>
     *     <li>转换为新格式</li>
     *     <li>写入新配置</li>
     *     <li>清理旧字段</li>
     * </ol>
     */
    void execute();
}
