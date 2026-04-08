package com.aicodeassistant.prompt;

import java.util.function.Supplier;

/**
 * SystemPromptSection — 系统提示段抽象。
 * <p>
 * 密封接口设计，支持两种段类型：
 * - MemoizedSection: 记忆化段，首次计算后缓存，跨轮次复用
 * - UncachedSection: 易变段，每轮重算，但仅在内容变化时才实际破坏缓存
 * <p>
 * 对照源码: SPEC §3.1.1 SystemPromptBuilder
 *
 * @see MemoizedSection
 * @see UncachedSection
 */
public sealed interface SystemPromptSection {

    /**
     * 段名称 — 用于缓存键和调试
     */
    String name();

    /**
     * 计算段内容 — 延迟执行
     */
    Supplier<String> compute();

    /**
     * 是否破坏缓存 — true 表示每次都需要重新计算
     */
    boolean cacheBreak();

    /**
     * 段描述 — 用于调试和日志
     */
    default String description() {
        return name();
    }
}

/**
 * 记忆化段 — 首次计算后缓存，跨轮次复用。
 * <p>
 * 适用于内容相对稳定但计算成本较高的段，如：
 * - session_guidance: 基于工具集的会话引导
 * - memory: CLAUDE.md 记忆加载
 * - env_info: 环境信息（模型、OS、工作目录等）
 */
record MemoizedSection(String name, Supplier<String> compute) implements SystemPromptSection {

    @Override
    public boolean cacheBreak() {
        return false;
    }

    public MemoizedSection(String name, Supplier<String> compute, String description) {
        this(name, compute);
    }
}

/**
 * 易变段 — 每轮重算，但仅在内容变化时才实际破坏缓存。
 * <p>
 * 适用于内容可能频繁变化的段，如：
 * - mcp_instructions: MCP 服务器连接状态可能在轮次间变化
 */
record UncachedSection(String name, Supplier<String> compute, String reason) implements SystemPromptSection {

    @Override
    public boolean cacheBreak() {
        return true;
    }

    @Override
    public String description() {
        return name + " (" + reason + ")";
    }
}
