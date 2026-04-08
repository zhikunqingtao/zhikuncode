package com.aicodeassistant.plugin;

import java.util.function.Function;

/**
 * 钩子处理器 — 插件事件钩子定义。
 * <p>
 * 支持的钩子事件类型 (对照源码 loadPluginHooks.ts):
 * <ul>
 *     <li>PreToolUse  — 工具调用前拦截（可修改输入/拒绝调用）</li>
 *     <li>PostToolUse — 工具调用后处理（可修改输出）</li>
 *     <li>Notification — 通知事件处理</li>
 *     <li>Stop        — 查询循环结束后处理</li>
 * </ul>
 *
 * @param eventType 事件类型
 * @param matcher   匹配器（正则表达式，匹配工具名等）
 * @param priority  优先级（数值越小越先执行）
 * @param handler   处理函数
 * @see <a href="SPEC §4.6.2">插件钩子注册</a>
 */
public record HookHandler(
        HookEventType eventType,
        String matcher,
        int priority,
        Function<HookContext, HookResult> handler
) {

    /**
     * 钩子事件类型。
     */
    public enum HookEventType {
        PRE_TOOL_USE,
        POST_TOOL_USE,
        NOTIFICATION,
        STOP
    }

    /**
     * 钩子执行上下文。
     */
    public record HookContext(
            String toolName,
            String toolInput,
            String toolOutput,
            String sessionId
    ) {
        public static HookContext of(String toolName, String input) {
            return new HookContext(toolName, input, null, null);
        }
    }

    /**
     * 钩子执行结果。
     */
    public record HookResult(
            boolean proceed,
            String modifiedInput,
            String modifiedOutput,
            String message
    ) {
        public static HookResult allow() {
            return new HookResult(true, null, null, null);
        }

        public static HookResult deny(String reason) {
            return new HookResult(false, null, null, reason);
        }

        public static HookResult modifyInput(String newInput) {
            return new HookResult(true, newInput, null, null);
        }

        public static HookResult modifyOutput(String newOutput) {
            return new HookResult(true, null, newOutput, null);
        }
    }

    /**
     * 创建 PreToolUse 钩子。
     */
    public static HookHandler preToolUse(String matcher, int priority,
                                          Function<HookContext, HookResult> handler) {
        return new HookHandler(HookEventType.PRE_TOOL_USE, matcher, priority, handler);
    }

    /**
     * 创建 PostToolUse 钩子。
     */
    public static HookHandler postToolUse(String matcher, int priority,
                                           Function<HookContext, HookResult> handler) {
        return new HookHandler(HookEventType.POST_TOOL_USE, matcher, priority, handler);
    }
}
