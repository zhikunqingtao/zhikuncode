package com.aicodeassistant.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 钩子注册表 — 管理所有已注册的钩子处理器。
 * <p>
 * 支持按事件类型注册和查询钩子，按优先级排序执行。
 *
 * @see <a href="SPEC section 3.10">Hook 系统</a>
 */
@Component
public class HookRegistry {

    private static final Logger log = LoggerFactory.getLogger(HookRegistry.class);

    /** 已注册的钩子 (按事件类型分组) */
    private final Map<HookEvent, List<HookRegistration>> hooks = new ConcurrentHashMap<>();

    /**
     * 注册钩子。
     *
     * @param event    事件类型
     * @param matcher  匹配器（正则表达式，null 表示匹配全部）
     * @param priority 优先级（数值越小越先执行）
     * @param handler  处理函数
     * @param source   钩子来源标识
     */
    public void register(HookEvent event, String matcher, int priority,
                         Function<HookContext, HookResult> handler, String source) {
        HookRegistration registration = new HookRegistration(event, matcher, priority, handler, source);
        hooks.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(registration);
        log.debug("Hook registered: event={}, matcher={}, priority={}, source={}",
                event, matcher, priority, source);
    }

    /**
     * 获取指定事件的所有钩子（按优先级排序）。
     */
    public List<HookRegistration> getHooks(HookEvent event) {
        List<HookRegistration> registrations = hooks.get(event);
        if (registrations == null || registrations.isEmpty()) {
            return List.of();
        }
        return registrations.stream()
                .sorted(Comparator.comparingInt(HookRegistration::priority))
                .toList();
    }

    /**
     * 注销指定来源的所有钩子。
     */
    public void unregisterBySource(String source) {
        hooks.values().forEach(list ->
                list.removeIf(reg -> source.equals(reg.source())));
        log.debug("Hooks unregistered for source: {}", source);
    }

    /**
     * 清空所有已注册的钩子。
     */
    public void clear() {
        hooks.clear();
    }

    /**
     * 获取已注册钩子总数。
     */
    public int size() {
        return hooks.values().stream().mapToInt(List::size).sum();
    }

    /**
     * 钩子注册记录。
     */
    public record HookRegistration(
            HookEvent event,
            String matcher,
            int priority,
            Function<HookContext, HookResult> handler,
            String source
    ) {}

    /**
     * 钩子执行上下文。
     */
    public record HookContext(
            String toolName,
            String input,
            String output,
            String sessionId,
            Map<String, Object> metadata
    ) {
        public static HookContext forTool(String toolName, String input, String sessionId) {
            return new HookContext(toolName, input, null, sessionId, Map.of());
        }

        public static HookContext forToolOutput(String toolName, String output, String sessionId) {
            return new HookContext(toolName, null, output, sessionId, Map.of());
        }

        public static HookContext forMessage(String input, String sessionId) {
            return new HookContext(null, input, null, sessionId, Map.of());
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

        public static HookResult passThrough() {
            return new HookResult(true, null, null, null);
        }
    }

    /**
     * StopHook 专用结果 — 对齐原版 stopHooks.ts StopHookResult。
     */
    public record StopHookResult(
            boolean preventContinuation,
            List<String> blockingErrors
    ) {
        public boolean hasBlockingErrors() {
            return blockingErrors != null && !blockingErrors.isEmpty();
        }

        public static StopHookResult ok() {
            return new StopHookResult(false, List.of());
        }

        public static StopHookResult blocking(List<String> errors) {
            return new StopHookResult(false, errors);
        }

        public static StopHookResult preventContinuation(String reason) {
            return new StopHookResult(true, List.of(reason));
        }
    }
}
