package com.aicodeassistant.hook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HookService + HookRegistry 单元测试。
 */
class HookServiceTest {

    private HookRegistry registry;
    private HookService hookService;

    @BeforeEach
    void setUp() {
        registry = new HookRegistry();
        hookService = new HookService(registry);
    }

    @Test
    @DisplayName("无钩子时返回 passThrough")
    void execute_noHooks_passThrough() {
        var result = hookService.executePreToolUse("BashTool", "ls", "session1");
        assertTrue(result.proceed());
        assertNull(result.modifiedInput());
    }

    @Test
    @DisplayName("PreToolUse 钩子可以拒绝操作")
    void preToolUse_deny() {
        registry.register(HookEvent.PRE_TOOL_USE, "Bash.*", 1,
                ctx -> HookRegistry.HookResult.deny("dangerous command"), "test-plugin");

        var result = hookService.executePreToolUse("BashTool", "rm -rf /", "session1");
        assertFalse(result.proceed());
        assertEquals("dangerous command", result.message());
    }

    @Test
    @DisplayName("PreToolUse 钩子可以修改输入")
    void preToolUse_modifyInput() {
        registry.register(HookEvent.PRE_TOOL_USE, null, 1,
                ctx -> HookRegistry.HookResult.modifyInput("sanitized_" + ctx.input()), "sanitizer");

        var result = hookService.executePreToolUse("BashTool", "ls", "session1");
        assertTrue(result.proceed());
        assertEquals("sanitized_ls", result.modifiedInput());
    }

    @Test
    @DisplayName("PostToolUse 钩子可以修改输出")
    void postToolUse_modifyOutput() {
        registry.register(HookEvent.POST_TOOL_USE, null, 1,
                ctx -> HookRegistry.HookResult.modifyOutput("[filtered] " + ctx.output()), "filter");

        var result = hookService.executePostToolUse("BashTool", "sensitive data", "session1");
        assertTrue(result.proceed());
        assertEquals("[filtered] sensitive data", result.modifiedOutput());
    }

    @Test
    @DisplayName("钩子按优先级排序执行")
    void hooks_priorityOrder() {
        StringBuilder order = new StringBuilder();

        registry.register(HookEvent.PRE_TOOL_USE, null, 10,
                ctx -> { order.append("B"); return HookRegistry.HookResult.allow(); }, "low-priority");
        registry.register(HookEvent.PRE_TOOL_USE, null, 1,
                ctx -> { order.append("A"); return HookRegistry.HookResult.allow(); }, "high-priority");
        registry.register(HookEvent.PRE_TOOL_USE, null, 5,
                ctx -> { order.append("M"); return HookRegistry.HookResult.allow(); }, "mid-priority");

        hookService.executePreToolUse("AnyTool", "input", "session1");
        assertEquals("AMB", order.toString());
    }

    @Test
    @DisplayName("匹配器正则过滤 — 不匹配的钩子不执行")
    void matcher_filters() {
        StringBuilder executed = new StringBuilder();

        registry.register(HookEvent.PRE_TOOL_USE, "Bash.*", 1,
                ctx -> { executed.append("bash"); return HookRegistry.HookResult.allow(); }, "bash-hook");
        registry.register(HookEvent.PRE_TOOL_USE, "File.*", 1,
                ctx -> { executed.append("file"); return HookRegistry.HookResult.allow(); }, "file-hook");

        hookService.executePreToolUse("BashTool", "ls", "session1");
        assertEquals("bash", executed.toString());
    }

    @Test
    @DisplayName("钩子执行异常不阻塞主流程")
    void hook_exceptionDoesNotBlock() {
        registry.register(HookEvent.PRE_TOOL_USE, null, 1,
                ctx -> { throw new RuntimeException("boom"); }, "broken");
        registry.register(HookEvent.PRE_TOOL_USE, null, 2,
                ctx -> HookRegistry.HookResult.allow(), "working");

        var result = hookService.executePreToolUse("AnyTool", "input", "session1");
        assertTrue(result.proceed());
    }

    @Test
    @DisplayName("UserPromptSubmit 钩子")
    void userPromptSubmit() {
        registry.register(HookEvent.USER_PROMPT_SUBMIT, null, 1,
                ctx -> HookRegistry.HookResult.modifyInput("[enhanced] " + ctx.input()), "enhancer");

        var result = hookService.executeUserPromptSubmit("hello", "session1");
        assertTrue(result.proceed());
        assertEquals("[enhanced] hello", result.modifiedInput());
    }

    @Test
    @DisplayName("HookRegistry — 注销指定来源")
    void registry_unregisterBySource() {
        registry.register(HookEvent.PRE_TOOL_USE, null, 1,
                ctx -> HookRegistry.HookResult.allow(), "plugin-A");
        registry.register(HookEvent.PRE_TOOL_USE, null, 2,
                ctx -> HookRegistry.HookResult.allow(), "plugin-B");
        assertEquals(2, registry.size());

        registry.unregisterBySource("plugin-A");
        assertEquals(1, registry.size());
    }

    @Test
    @DisplayName("HookRegistry — clear 清空所有钩子")
    void registry_clear() {
        registry.register(HookEvent.PRE_TOOL_USE, null, 1,
                ctx -> HookRegistry.HookResult.allow(), "a");
        registry.register(HookEvent.POST_TOOL_USE, null, 1,
                ctx -> HookRegistry.HookResult.allow(), "b");
        assertEquals(2, registry.size());

        registry.clear();
        assertEquals(0, registry.size());
    }

    @Test
    @DisplayName("链式钩子修改 — 前一个钩子的输出是后一个的输入")
    void chainedModification() {
        registry.register(HookEvent.PRE_TOOL_USE, null, 1,
                ctx -> HookRegistry.HookResult.modifyInput(ctx.input() + "_step1"), "step1");
        registry.register(HookEvent.PRE_TOOL_USE, null, 2,
                ctx -> HookRegistry.HookResult.modifyInput(ctx.input() + "_step2"), "step2");

        var result = hookService.executePreToolUse("AnyTool", "base", "session1");
        assertTrue(result.proceed());
        assertEquals("base_step1_step2", result.modifiedInput());
    }

    @Test
    @DisplayName("deny 中断链 — 后续钩子不再执行")
    void denyBreaksChain() {
        StringBuilder executed = new StringBuilder();

        registry.register(HookEvent.PRE_TOOL_USE, null, 1,
                ctx -> { executed.append("1"); return HookRegistry.HookResult.deny("stop"); }, "first");
        registry.register(HookEvent.PRE_TOOL_USE, null, 2,
                ctx -> { executed.append("2"); return HookRegistry.HookResult.allow(); }, "second");

        var result = hookService.executePreToolUse("AnyTool", "input", "session1");
        assertFalse(result.proceed());
        assertEquals("1", executed.toString());
    }
}
