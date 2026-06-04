package com.aicodeassistant.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolRegistry 会话级激活/过滤行为单元测试。
 * <p>
 * 覆盖 PR-6 的延迟加载与会话激活语义：
 * <ul>
 *   <li>activate() 将工具加入会话激活集合</li>
 *   <li>getActiveToolDefinitions() 过滤未激活的 deferred 工具</li>
 *   <li>clearActivations() 清理会话激活状态</li>
 *   <li>多会话隔离</li>
 * </ul>
 */
class ToolRegistryActivationTest {

    private static final String SESSION_A = "session-a";
    private static final String SESSION_B = "session-b";

    /** 创建最小化 Tool mock —— 控制 shouldDefer/alwaysLoad 行为。 */
    private Tool createMockTool(String name, boolean shouldDefer, boolean alwaysLoad) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "mock tool " + name; }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of("type", "object");
            }
            @Override public ToolResult call(ToolInput input, ToolUseContext context) {
                return ToolResult.success("ok");
            }
            @Override public boolean shouldDefer() { return shouldDefer; }
            @Override public boolean alwaysLoad() { return alwaysLoad; }
        };
    }

    private String getDefName(Map<String, Object> def) {
        @SuppressWarnings("unchecked")
        Map<String, Object> fn = (Map<String, Object>) def.get("function");
        return (String) fn.get("name");
    }

    private List<String> defNames(List<Map<String, Object>> defs) {
        return defs.stream().map(this::getDefName).toList();
    }

    // ===== 过滤逻辑 =====

    @Test
    @DisplayName("alwaysLoad=true 工具始终包含")
    void alwaysLoadTool_alwaysIncluded() {
        Tool always = createMockTool("AlwaysOn", /*defer*/ true, /*always*/ true);
        Tool deferred = createMockTool("Deferred", /*defer*/ true, /*always*/ false);
        ToolRegistry registry = new ToolRegistry(List.of(always, deferred));

        List<String> names = defNames(registry.getActiveToolDefinitions(SESSION_A));
        assertTrue(names.contains("AlwaysOn"));
        assertFalse(names.contains("Deferred"));
    }

    @Test
    @DisplayName("shouldDefer=false 工具始终包含")
    void nonDeferredTool_alwaysIncluded() {
        Tool eager = createMockTool("Eager", /*defer*/ false, /*always*/ false);
        Tool deferred = createMockTool("Deferred", /*defer*/ true, /*always*/ false);
        ToolRegistry registry = new ToolRegistry(List.of(eager, deferred));

        List<String> names = defNames(registry.getActiveToolDefinitions(SESSION_A));
        assertTrue(names.contains("Eager"));
        assertFalse(names.contains("Deferred"));
    }

    @Test
    @DisplayName("shouldDefer=true 且未激活 → 被排除")
    void deferredNotActivated_excluded() {
        Tool deferred = createMockTool("Lazy", /*defer*/ true, /*always*/ false);
        ToolRegistry registry = new ToolRegistry(List.of(deferred));

        assertTrue(registry.getActiveToolDefinitions(SESSION_A).isEmpty());
    }

    @Test
    @DisplayName("shouldDefer=true 且已激活 → 包含")
    void deferredActivated_included() {
        Tool deferred = createMockTool("Lazy", /*defer*/ true, /*always*/ false);
        ToolRegistry registry = new ToolRegistry(List.of(deferred));

        registry.activate(SESSION_A, List.of("Lazy"));

        List<String> names = defNames(registry.getActiveToolDefinitions(SESSION_A));
        assertEquals(List.of("Lazy"), names);
    }

    // ===== activate / clearActivations =====

    @Test
    @DisplayName("activate 累加而非覆盖")
    void activate_appendsToExistingSet() {
        Tool a = createMockTool("A", true, false);
        Tool b = createMockTool("B", true, false);
        ToolRegistry registry = new ToolRegistry(List.of(a, b));

        registry.activate(SESSION_A, List.of("A"));
        registry.activate(SESSION_A, List.of("B"));

        List<String> names = defNames(registry.getActiveToolDefinitions(SESSION_A));
        assertTrue(names.contains("A"));
        assertTrue(names.contains("B"));
    }

    @Test
    @DisplayName("clearActivations 清理后 deferred 工具重新被排除")
    void clearActivations_removesActivation() {
        Tool deferred = createMockTool("Lazy", true, false);
        ToolRegistry registry = new ToolRegistry(List.of(deferred));

        registry.activate(SESSION_A, List.of("Lazy"));
        assertEquals(1, registry.getActiveToolDefinitions(SESSION_A).size());

        registry.clearActivations(SESSION_A);
        assertTrue(registry.getActiveToolDefinitions(SESSION_A).isEmpty());
    }

    // ===== 多会话隔离 =====

    @Test
    @DisplayName("会话间激活状态相互隔离")
    void sessionIsolation_activationsIndependent() {
        Tool deferred = createMockTool("Lazy", true, false);
        ToolRegistry registry = new ToolRegistry(List.of(deferred));

        registry.activate(SESSION_A, List.of("Lazy"));

        // SESSION_A 看到 Lazy
        assertEquals(List.of("Lazy"),
                defNames(registry.getActiveToolDefinitions(SESSION_A)));
        // SESSION_B 不受影响
        assertTrue(registry.getActiveToolDefinitions(SESSION_B).isEmpty());
    }

    @Test
    @DisplayName("clearActivations 仅清理目标会话")
    void clearActivations_onlyAffectsTargetSession() {
        Tool deferred = createMockTool("Lazy", true, false);
        ToolRegistry registry = new ToolRegistry(List.of(deferred));

        registry.activate(SESSION_A, List.of("Lazy"));
        registry.activate(SESSION_B, List.of("Lazy"));

        registry.clearActivations(SESSION_A);

        assertTrue(registry.getActiveToolDefinitions(SESSION_A).isEmpty());
        assertEquals(List.of("Lazy"),
                defNames(registry.getActiveToolDefinitions(SESSION_B)));
    }

    // ===== 综合：混合工具集 =====

    @Test
    @DisplayName("混合工具集 — alwaysLoad / eager / deferred(未激活+已激活) 同时存在")
    void mixedTools_correctFilter() {
        Tool always = createMockTool("Always", true, true);
        Tool eager = createMockTool("Eager", false, false);
        Tool deferredOff = createMockTool("DeferredOff", true, false);
        Tool deferredOn = createMockTool("DeferredOn", true, false);
        ToolRegistry registry = new ToolRegistry(
                List.of(always, eager, deferredOff, deferredOn));

        registry.activate(SESSION_A, List.of("DeferredOn"));

        List<String> names = defNames(registry.getActiveToolDefinitions(SESSION_A));
        assertTrue(names.contains("Always"));
        assertTrue(names.contains("Eager"));
        assertTrue(names.contains("DeferredOn"));
        assertFalse(names.contains("DeferredOff"));
    }
}
