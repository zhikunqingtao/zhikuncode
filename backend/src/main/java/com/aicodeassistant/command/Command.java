package com.aicodeassistant.command;

import java.util.List;

/**
 * 命令基础接口 — 对照源码 CommandBase 的完整属性集。
 * <p>
 * 源码中命令分为 3 种类型:
 * <ul>
 *     <li>PromptCommand: 生成提示词发送给 LLM（含 contentLength/allowedTools/model 等额外字段）</li>
 *     <li>LocalCommand (type=LOCAL): 本地执行（含 supportsNonInteractive 字段）</li>
 *     <li>LocalJSXCommand (type=LOCAL_JSX): 本地执行，返回需前端渲染的组件</li>
 * </ul>
 *
 * @see <a href="SPEC §3.3.1">命令接口定义</a>
 */
public interface Command {

    // ==================== 基础标识 ====================

    /** 命令名称（不含/前缀） */
    String getName();

    /** 命令别名 */
    default List<String> getAliases() { return List.of(); }

    /** 命令描述 */
    String getDescription();

    /** 命令类型 */
    CommandType getType();

    // ==================== 可见性与可用性 ====================

    /** 是否从 /help 列表中隐藏（内部命令） */
    default boolean isHidden() { return false; }

    /** 是否来自 MCP 服务器注册的命令 */
    default boolean isMcp() { return false; }

    /** 用户是否可直接通过 /name 调用（false 表示仅供内部调用） */
    default boolean isUserInvocable() { return true; }

    /** 命令可用性要求 — 认证、特性标志等前置条件 */
    default CommandAvailability getAvailability() { return CommandAvailability.ALWAYS; }

    /** 命令版本 */
    default String getVersion() { return "1.0"; }

    /** 命令加载来源模块 */
    default String getLoadedFrom() { return "builtin"; }

    // ==================== 执行控制 ====================

    /** 执行命令 */
    CommandResult execute(String args, CommandContext context);

    /** 是否立即执行（不等待 LLM 当前轮次完成） */
    default boolean isImmediate() { return false; }

    /** 是否为敏感命令 — 敏感命令在日志中脱敏 */
    default boolean isSensitive() { return false; }

    /** 是否支持非交互模式（headless/CI 场景） */
    default boolean supportsNonInteractive() { return false; }
}
