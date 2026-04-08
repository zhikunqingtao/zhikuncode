package com.aicodeassistant.tool.agent;

import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AgentTool 子代理系统黄金测试 — 对齐 SPEC §4.1.1。
 * <p>
 * 覆盖 6 个核心类别:
 * 1. AgentConcurrencyController — 并发限制 (8 tests)
 * 2. BackgroundAgentTracker — 后台追踪 (5 tests)
 * 3. WorktreeManager — Worktree 管理 (3 tests)
 * 4. SubAgentExecutor — 编排核心 (5 tests)
 * 5. AgentTool — 工具接口 (5 tests)
 * 6. ToolUseContext — nestingDepth 扩展 (4 tests)
 * <p>
 * 总计: 30 条黄金测试。
 */
class AgentToolGoldenTest {

    // ═══════════════════════════════════════════════
    // 1. AgentConcurrencyController 并发控制
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("1. AgentConcurrencyController — 并发限制")
    class ConcurrencyControllerTests {

        private AgentConcurrencyController controller;

        @BeforeEach
        void setUp() {
            controller = new AgentConcurrencyController();
        }

        @Test
        @DisplayName("1.1 正常获取槽位 — 深度1, 全局/会话计数递增")
        void acquireSlot_normal_shouldIncrement() {
            var slot = controller.acquireSlot("agent-1", 1, "session-1");
            assertNotNull(slot);
            assertEquals(1, controller.getActiveCount());
            assertEquals(1, controller.getSessionActiveCount("session-1"));
            slot.close();
            assertEquals(0, controller.getActiveCount());
        }

        @Test
        @DisplayName("1.2 嵌套深度超限 — depth>3 抛出 AgentLimitExceededException")
        void acquireSlot_nestingExceeded_shouldThrow() {
            assertThrows(AgentLimitExceededException.class,
                    () -> controller.acquireSlot("agent-1", 4, "session-1"));
            assertEquals(0, controller.getActiveCount());
        }

        @Test
        @DisplayName("1.3 嵌套深度边界 — depth=3 正常获取")
        void acquireSlot_maxNesting_shouldSucceed() {
            var slot = controller.acquireSlot("agent-1", 3, "session-1");
            assertNotNull(slot);
            slot.close();
        }

        @Test
        @DisplayName("1.4 会话级并发超限 — 超过10个抛异常")
        void acquireSlot_sessionLimitExceeded_shouldThrow() {
            // 获取 10 个槽位
            var slots = new java.util.ArrayList<AgentConcurrencyController.AgentSlot>();
            for (int i = 0; i < 10; i++) {
                slots.add(controller.acquireSlot("agent-" + i, 1, "session-1"));
            }
            assertEquals(10, controller.getSessionActiveCount("session-1"));

            // 第 11 个应失败
            assertThrows(AgentLimitExceededException.class,
                    () -> controller.acquireSlot("agent-11", 1, "session-1"));

            // 全局计数不应递增（回退）
            assertEquals(10, controller.getActiveCount());

            // 清理
            slots.forEach(AgentConcurrencyController.AgentSlot::close);
        }

        @Test
        @DisplayName("1.5 不同会话独立计数 — session-1 和 session-2 各自独立")
        void acquireSlot_differentSessions_shouldBeIndependent() {
            var slot1 = controller.acquireSlot("agent-1", 1, "session-1");
            var slot2 = controller.acquireSlot("agent-2", 1, "session-2");
            assertEquals(2, controller.getActiveCount());
            assertEquals(1, controller.getSessionActiveCount("session-1"));
            assertEquals(1, controller.getSessionActiveCount("session-2"));
            slot1.close();
            slot2.close();
        }

        @Test
        @DisplayName("1.6 RAII 自动释放 — try-with-resources 关闭后计数归零")
        void acquireSlot_autoClose_shouldRelease() {
            try (var slot = controller.acquireSlot("agent-1", 1, "session-1")) {
                assertEquals(1, controller.getActiveCount());
            }
            assertEquals(0, controller.getActiveCount());
            assertEquals(0, controller.getSessionActiveCount("session-1"));
        }

        @Test
        @DisplayName("1.7 常量值验证 — MAX=30/SESSION=10/DEPTH=3")
        void constants_shouldMatchSpec() {
            assertEquals(30, AgentConcurrencyController.MAX_CONCURRENT_AGENTS);
            assertEquals(10, AgentConcurrencyController.MAX_CONCURRENT_AGENTS_PER_SESSION);
            assertEquals(3, AgentConcurrencyController.MAX_AGENT_NESTING_DEPTH);
        }

        @Test
        @DisplayName("1.8 并发安全 — 多线程同时获取/释放")
        void acquireSlot_concurrent_shouldBeThreadSafe() throws Exception {
            int threadCount = 20;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                Thread.startVirtualThread(() -> {
                    try {
                        startLatch.await();
                        try (var slot = controller.acquireSlot(
                                "agent-" + idx, 1, "session-1")) {
                            successCount.incrementAndGet();
                            Thread.sleep(10);
                        }
                    } catch (Exception e) {
                        // 会话级限制可能导致部分失败
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
            // 完成后所有槽位应已释放
            assertEquals(0, controller.getActiveCount());
        }
    }

    // ═══════════════════════════════════════════════
    // 2. BackgroundAgentTracker 后台追踪
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("2. BackgroundAgentTracker — 后台追踪")
    class BackgroundTrackerTests {

        private BackgroundAgentTracker tracker;
        private SimpMessagingTemplate mockMessaging;

        @BeforeEach
        void setUp() {
            mockMessaging = mock(SimpMessagingTemplate.class);
            tracker = new BackgroundAgentTracker(mockMessaging);
        }

        @Test
        @DisplayName("2.1 注册代理 — 状态为 running")
        void register_shouldCreateRunningStatus() {
            tracker.register("agent-1", "session-1", "test task", "/tmp/output.txt");
            var status = tracker.getStatus("agent-1");
            assertNotNull(status);
            assertEquals("running", status.status());
            assertEquals("session-1", status.sessionId());
            assertEquals("test task", status.prompt());
        }

        @Test
        @DisplayName("2.2 标记完成 — 状态变为 completed")
        void markCompleted_shouldUpdateStatus() {
            tracker.register("agent-1", "session-1", "test", "/tmp/out.txt");
            var result = new SubAgentExecutor.AgentResult("completed", "done", "test", null);
            tracker.markCompleted("agent-1", result);
            var status = tracker.getStatus("agent-1");
            assertEquals("completed", status.status());
            assertNotNull(status.completedAt());
            assertNull(status.error());
        }

        @Test
        @DisplayName("2.3 标记失败 — 状态变为 failed 并记录错误")
        void markFailed_shouldRecordError() {
            tracker.register("agent-1", "session-1", "test", "/tmp/out.txt");
            tracker.markFailed("agent-1", "timeout");
            var status = tracker.getStatus("agent-1");
            assertEquals("failed", status.status());
            assertNotNull(status.completedAt());
        }

        @Test
        @DisplayName("2.4 列出活跃代理 — 只返回 running 状态")
        void listActive_shouldFilterRunning() {
            tracker.register("agent-1", "session-1", "task1", "/tmp/1.txt");
            tracker.register("agent-2", "session-1", "task2", "/tmp/2.txt");
            tracker.markCompleted("agent-1",
                    new SubAgentExecutor.AgentResult("completed", "done", "task1", null));
            var active = tracker.listActive("session-1");
            assertEquals(1, active.size());
            assertEquals("agent-2", active.getFirst().agentId());
        }

        @Test
        @DisplayName("2.5 STOMP 事件推送 — 注册时推送 agent_started")
        void register_shouldPushStompEvent() {
            tracker.register("agent-1", "session-1", "test", "/tmp/out.txt");
            verify(mockMessaging).convertAndSend(
                    eq("/topic/session/session-1"),
                    (Object) argThat(payload -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) payload;
                        return "task_update".equals(map.get("type"))
                                && "agent_started".equals(map.get("eventType"));
                    }));
        }
    }

    // ═══════════════════════════════════════════════
    // 3. WorktreeManager — Worktree 管理
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("3. WorktreeManager — Worktree 管理")
    class WorktreeManagerTests {

        private WorktreeManager manager;

        @BeforeEach
        void setUp() {
            manager = new WorktreeManager();
        }

        @Test
        @DisplayName("3.1 初始状态 — 无活跃 worktree")
        void initialState_shouldHaveNoActiveWorktrees() {
            assertEquals(0, manager.getActiveCount());
        }

        @Test
        @DisplayName("3.2 hasChanges 对不存在的路径 — 返回 false")
        void hasChanges_nonExistentPath_shouldReturnFalse() {
            assertFalse(manager.hasChanges(java.nio.file.Path.of("/nonexistent/path")));
        }

        @Test
        @DisplayName("3.3 removeWorktree 对不存在的路径 — 静默处理不抛异常")
        void removeWorktree_nonExistent_shouldNotThrow() {
            assertDoesNotThrow(() ->
                    manager.removeWorktree(java.nio.file.Path.of("/nonexistent/path")));
        }
    }

    // ═══════════════════════════════════════════════
    // 4. SubAgentExecutor — 编排核心
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("4. SubAgentExecutor — 编排核心")
    class SubAgentExecutorTests {

        @Test
        @DisplayName("4.1 AgentRequest record — 字段完整性")
        void agentRequest_shouldHaveAllFields() {
            var req = new SubAgentExecutor.AgentRequest(
                    "agent-1", "test prompt", "explore",
                    "haiku", SubAgentExecutor.IsolationMode.NONE, false);
            assertEquals("agent-1", req.agentId());
            assertEquals("test prompt", req.prompt());
            assertEquals("explore", req.agentType());
            assertEquals("haiku", req.model());
            assertEquals(SubAgentExecutor.IsolationMode.NONE, req.isolation());
            assertFalse(req.runInBackground());
        }

        @Test
        @DisplayName("4.2 AgentResult record — 同步完成")
        void agentResult_completed_shouldHaveResult() {
            var result = new SubAgentExecutor.AgentResult(
                    "completed", "task done", "test", null);
            assertEquals("completed", result.status());
            assertEquals("task done", result.result());
            assertNull(result.outputFile());
        }

        @Test
        @DisplayName("4.3 AgentResult record — 异步启动")
        void agentResult_asyncLaunched_shouldHaveOutputFile() {
            var result = new SubAgentExecutor.AgentResult(
                    "async_launched", null, "test", "/tmp/output.txt");
            assertEquals("async_launched", result.status());
            assertNull(result.result());
            assertEquals("/tmp/output.txt", result.outputFile());
        }

        @Test
        @DisplayName("4.4 IsolationMode 枚举 — 三种模式")
        void isolationMode_shouldHaveThreeValues() {
            assertEquals(3, SubAgentExecutor.IsolationMode.values().length);
            assertNotNull(SubAgentExecutor.IsolationMode.NONE);
            assertNotNull(SubAgentExecutor.IsolationMode.WORKTREE);
            assertNotNull(SubAgentExecutor.IsolationMode.REMOTE);
        }

        @Test
        @DisplayName("4.5 AgentDefinition — 五种内置代理类型")
        void agentDefinition_shouldHaveFiveBuiltinTypes() {
            assertNotNull(SubAgentExecutor.AgentDefinition.EXPLORE);
            assertNotNull(SubAgentExecutor.AgentDefinition.VERIFICATION);
            assertNotNull(SubAgentExecutor.AgentDefinition.PLAN);
            assertNotNull(SubAgentExecutor.AgentDefinition.GENERAL_PURPOSE);
            assertNotNull(SubAgentExecutor.AgentDefinition.GUIDE);

            // Explore 应禁止写工具
            assertTrue(SubAgentExecutor.AgentDefinition.EXPLORE.deniedTools().contains("FileEdit"));
            assertTrue(SubAgentExecutor.AgentDefinition.EXPLORE.deniedTools().contains("FileWrite"));

            // General Purpose 应有全工具访问
            assertTrue(SubAgentExecutor.AgentDefinition.GENERAL_PURPOSE.allowedTools().contains("*"));

            // Guide 限定工具集
            assertTrue(SubAgentExecutor.AgentDefinition.GUIDE.allowedTools().contains("Glob"));
            assertTrue(SubAgentExecutor.AgentDefinition.GUIDE.allowedTools().contains("Grep"));

            // maxTurns 均为 30
            assertEquals(30, SubAgentExecutor.AgentDefinition.EXPLORE.maxTurns());
            assertEquals(30, SubAgentExecutor.AgentDefinition.GENERAL_PURPOSE.maxTurns());

            // MAX_RESULT_SIZE_CHARS
            assertEquals(100_000, SubAgentExecutor.MAX_RESULT_SIZE_CHARS);
        }
    }

    // ═══════════════════════════════════════════════
    // 5. AgentTool — 工具接口
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("5. AgentTool — 工具接口")
    class AgentToolTests {

        private AgentTool agentTool;
        private SubAgentExecutor mockExecutor;

        @BeforeEach
        void setUp() {
            mockExecutor = mock(SubAgentExecutor.class);
            agentTool = new AgentTool(mockExecutor);
        }

        @Test
        @DisplayName("5.1 getName — 返回 Agent")
        void getName_shouldReturnAgent() {
            assertEquals("Agent", agentTool.getName());
        }

        @Test
        @DisplayName("5.2 getInputSchema — 包含 prompt 必填字段")
        void getInputSchema_shouldContainPrompt() {
            var schema = agentTool.getInputSchema();
            assertEquals("object", schema.get("type"));
            @SuppressWarnings("unchecked")
            var required = (List<String>) schema.get("required");
            assertTrue(required.contains("prompt"));
            @SuppressWarnings("unchecked")
            var props = (Map<String, Object>) schema.get("properties");
            assertTrue(props.containsKey("prompt"));
            assertTrue(props.containsKey("subagent_type"));
            assertTrue(props.containsKey("model"));
            assertTrue(props.containsKey("run_in_background"));
        }

        @Test
        @DisplayName("5.3 call 同步 — 返回 success")
        void call_sync_shouldReturnSuccess() {
            var result = new SubAgentExecutor.AgentResult(
                    "completed", "task done", "test", null);
            when(mockExecutor.executeSync(any(), any())).thenReturn(result);

            ToolUseContext ctx = ToolUseContext.of("/tmp", "session-1");
            ToolInput input = ToolInput.from(Map.of("prompt", "test task"));
            ToolResult toolResult = agentTool.call(input, ctx);

            assertFalse(toolResult.isError());
            assertEquals("task done", toolResult.content());
        }

        @Test
        @DisplayName("5.4 call 异步 — 返回 async_launched 信息")
        void call_async_shouldReturnAsyncInfo() {
            var result = new SubAgentExecutor.AgentResult(
                    "async_launched", null, "test", "/tmp/output.txt");
            when(mockExecutor.executeAsync(any(), any())).thenReturn(result);

            ToolUseContext ctx = ToolUseContext.of("/tmp", "session-1");
            ToolInput input = ToolInput.from(Map.of(
                    "prompt", "test task",
                    "run_in_background", true));
            ToolResult toolResult = agentTool.call(input, ctx);

            assertFalse(toolResult.isError());
            assertTrue(toolResult.content().contains("background"));
            assertTrue(toolResult.content().contains("/tmp/output.txt"));
        }

        @Test
        @DisplayName("5.5 call 超限 — 返回 error")
        void call_limitExceeded_shouldReturnError() {
            when(mockExecutor.executeSync(any(), any()))
                    .thenThrow(new AgentLimitExceededException("limit reached"));

            ToolUseContext ctx = ToolUseContext.of("/tmp", "session-1");
            ToolInput input = ToolInput.from(Map.of("prompt", "test"));
            ToolResult toolResult = agentTool.call(input, ctx);

            assertTrue(toolResult.isError());
            assertTrue(toolResult.content().contains("limit"));
        }
    }

    // ═══════════════════════════════════════════════
    // 6. ToolUseContext — nestingDepth 扩展
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("6. ToolUseContext — nestingDepth")
    class ToolUseContextTests {

        @Test
        @DisplayName("6.1 默认 nestingDepth 为 0")
        void defaultNestingDepth_shouldBeZero() {
            var ctx = ToolUseContext.of("/tmp", "session-1");
            assertEquals(0, ctx.nestingDepth());
        }

        @Test
        @DisplayName("6.2 withNestingDepth — 创建新实例")
        void withNestingDepth_shouldCreateNewInstance() {
            var ctx = ToolUseContext.of("/tmp", "session-1");
            var nested = ctx.withNestingDepth(2);
            assertEquals(2, nested.nestingDepth());
            assertEquals(0, ctx.nestingDepth()); // 原实例不变
        }

        @Test
        @DisplayName("6.3 兼容旧构造 — 6 参数构造默认 nestingDepth=0")
        void legacyConstructor_shouldDefaultToZero() {
            var ctx = new ToolUseContext("/tmp", "s1", "t1", null, List.of(), false);
            assertEquals(0, ctx.nestingDepth());
        }

        @Test
        @DisplayName("6.4 withToolUseId — 保留 nestingDepth")
        void withToolUseId_shouldPreserveNestingDepth() {
            var ctx = ToolUseContext.of("/tmp", "session-1").withNestingDepth(2);
            var withId = ctx.withToolUseId("tool-123");
            assertEquals(2, withId.nestingDepth());
            assertEquals("tool-123", withId.toolUseId());
        }
    }
}
