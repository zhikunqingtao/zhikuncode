package com.aicodeassistant.permission;

import com.aicodeassistant.hook.HookEvent;
import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.memdir.MemdirService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-PERM-004: HookService 8 种事件类型验证
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TC-PERM-004: HookService 事件类型验证")
class HookServiceEventTest {

    @Mock private MemdirService memdirService;

    private HookRegistry hookRegistry;
    private HookService hookService;

    @BeforeEach
    void setUp() {
        hookRegistry = new HookRegistry();
        hookService = new HookService(hookRegistry, memdirService);
    }

    @Nested
    @DisplayName("事件触发验证")
    class EventTriggerTests {

        @Test
        @DisplayName("注册并触发 PRE_TOOL_USE 事件")
        void preToolUseEventFires() {
            AtomicInteger callCount = new AtomicInteger(0);
            hookRegistry.register(HookEvent.PRE_TOOL_USE, null, 1,
                ctx -> { callCount.incrementAndGet(); return HookRegistry.HookResult.passThrough(); },
                "test-hook");

            HookRegistry.HookContext context = new HookRegistry.HookContext(
                "Bash", "ls -la", null, "test-session", Map.of());
            HookRegistry.HookResult result = hookService.execute(HookEvent.PRE_TOOL_USE, context);

            assertTrue(result.proceed(), "PRE_TOOL_USE 应继续执行");
            assertEquals(1, callCount.get(), "回调应被调用 1 次");
        }

        @Test
        @DisplayName("注册并触发 POST_TOOL_USE 事件")
        void postToolUseEventFires() {
            AtomicInteger callCount = new AtomicInteger(0);
            hookRegistry.register(HookEvent.POST_TOOL_USE, null, 1,
                ctx -> { callCount.incrementAndGet(); return HookRegistry.HookResult.passThrough(); },
                "test-hook");

            HookRegistry.HookContext context = new HookRegistry.HookContext(
                "Bash", null, "output", "test-session", Map.of());
            hookService.execute(HookEvent.POST_TOOL_USE, context);

            assertEquals(1, callCount.get());
        }

        @Test
        @DisplayName("注册并触发 USER_PROMPT_SUBMIT 事件")
        void userPromptSubmitEventFires() {
            AtomicInteger callCount = new AtomicInteger(0);
            hookRegistry.register(HookEvent.USER_PROMPT_SUBMIT, null, 1,
                ctx -> { callCount.incrementAndGet(); return HookRegistry.HookResult.passThrough(); },
                "test-hook");

            HookRegistry.HookContext context = new HookRegistry.HookContext(
                null, "user input", null, "test-session", Map.of());
            hookService.execute(HookEvent.USER_PROMPT_SUBMIT, context);

            assertEquals(1, callCount.get());
        }

        @Test
        @DisplayName("注册并触发 NOTIFICATION 事件")
        void notificationEventFires() {
            AtomicInteger callCount = new AtomicInteger(0);
            hookRegistry.register(HookEvent.NOTIFICATION, null, 1,
                ctx -> { callCount.incrementAndGet(); return HookRegistry.HookResult.passThrough(); },
                "test-hook");

            HookRegistry.HookContext context = new HookRegistry.HookContext(
                null, null, null, null, Map.of("level", "info", "message", "test"));
            hookService.execute(HookEvent.NOTIFICATION, context);

            assertEquals(1, callCount.get());
        }
    }

    @Nested
    @DisplayName("HookEvent 枚举完整性")
    class EventEnumTests {

        @ParameterizedTest
        @EnumSource(HookEvent.class)
        @DisplayName("所有 HookEvent 枚举值均可解析")
        void allHookEventsResolvable(HookEvent event) {
            assertNotNull(event.name(), "枚举名不应为 null");
            assertEquals(event, HookEvent.fromString(event.name()),
                event.name() + " 应可通过 fromString 解析");
        }

        @Test
        @DisplayName("HookEvent 应包含至少 8 种事件类型")
        void hookEventHasAtLeast8Types() {
            assertTrue(HookEvent.values().length >= 8,
                "HookEvent 应有至少 8 种事件类型，实际: " + HookEvent.values().length);
        }
    }

    @Nested
    @DisplayName("异常隔离验证")
    class ExceptionIsolationTests {

        @Test
        @DisplayName("Hook 异常不应影响主流程")
        void hookExceptionDoesNotBlockMainFlow() {
            // 注册一个抛异常的 Hook（高优先级数=后执行）
            hookRegistry.register(HookEvent.PRE_TOOL_USE, null, 10,
                ctx -> { throw new RuntimeException("测试异常"); },
                "bad-hook");

            // 注册一个正常的 Hook（低优先级数=先执行）
            AtomicInteger goodHookCalls = new AtomicInteger(0);
            hookRegistry.register(HookEvent.PRE_TOOL_USE, null, 1,
                ctx -> { goodHookCalls.incrementAndGet(); return HookRegistry.HookResult.passThrough(); },
                "good-hook");

            HookRegistry.HookContext context = new HookRegistry.HookContext(
                "Bash", "ls", null, "test", Map.of());

            // 执行不应抛出异常
            assertDoesNotThrow(() -> hookService.execute(HookEvent.PRE_TOOL_USE, context),
                "Hook 异常不应传播到主流程");
        }
    }

    @Test
    @DisplayName("无注册 Hook 时执行应 passthrough")
    void noHooksReturnPassThrough() {
        HookRegistry.HookContext context = new HookRegistry.HookContext(
            "Bash", null, null, "test", Map.of());
        HookRegistry.HookResult result = hookService.execute(HookEvent.STOP, context);
        assertTrue(result.proceed(), "无 Hook 时应 passthrough");
    }

    @Test
    @DisplayName("Hook 返回 deny 应阻止后续执行")
    void hookDenyShouldBlockExecution() {
        hookRegistry.register(HookEvent.PRE_TOOL_USE, null, 1,
            ctx -> HookRegistry.HookResult.deny("操作被拒绝"),
            "deny-hook");

        HookRegistry.HookContext context = new HookRegistry.HookContext(
            "Bash", "rm -rf /", null, "test", Map.of());
        HookRegistry.HookResult result = hookService.execute(HookEvent.PRE_TOOL_USE, context);

        assertFalse(result.proceed(), "deny Hook 应阻止执行");
        assertEquals("操作被拒绝", result.message());
    }
}
