package com.aicodeassistant.tool.task;

import com.aicodeassistant.model.TaskStatus;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Task 工具集黄金测试 — 覆盖 TaskCoordinator 和 6 个 Task 工具。
 */
class TaskToolGoldenTest {

    // ===== 1. TaskStatus 枚举测试 =====

    @Nested
    @DisplayName("1. TaskStatus")
    class TaskStatusTests {

        @Test
        @DisplayName("1.1 终态判定 — COMPLETED/FAILED/CANCELLED 为终态")
        void terminalStates() {
            assertTrue(TaskStatus.COMPLETED.isTerminal());
            assertTrue(TaskStatus.FAILED.isTerminal());
            assertTrue(TaskStatus.CANCELLED.isTerminal());
        }

        @Test
        @DisplayName("1.2 非终态判定 — PENDING/RUNNING 为非终态")
        void nonTerminalStates() {
            assertFalse(TaskStatus.PENDING.isTerminal());
            assertFalse(TaskStatus.RUNNING.isTerminal());
        }

        @Test
        @DisplayName("1.3 枚举值完整性 — 5 个状态")
        void allValues() {
            assertEquals(5, TaskStatus.values().length);
        }
    }

    // ===== 2. TaskState 测试 =====

    @Nested
    @DisplayName("2. TaskState")
    class TaskStateTests {

        @Test
        @DisplayName("2.1 构造与初始状态")
        void constructorDefaults() {
            TaskState state = new TaskState("t1", "s1", TaskStatus.PENDING);
            assertEquals("t1", state.getTaskId());
            assertEquals("s1", state.getSessionId());
            assertEquals(TaskStatus.PENDING, state.getStatus());
            assertNull(state.getDescription());
            assertNull(state.getError());
            assertNull(state.getOutput());
            assertNotNull(state.getCreatedAt());
            assertTrue(state.getChildTaskIds().isEmpty());
            assertTrue(state.getChildPids().isEmpty());
        }

        @Test
        @DisplayName("2.2 带描述的构造")
        void constructorWithDescription() {
            TaskState state = new TaskState("t2", "s1", TaskStatus.RUNNING, "test task");
            assertEquals("test task", state.getDescription());
        }

        @Test
        @DisplayName("2.3 可变字段设置")
        void mutableFields() {
            TaskState state = new TaskState("t3", "s1", TaskStatus.PENDING);
            state.setStatus(TaskStatus.RUNNING);
            state.setOutput("result");
            state.setError("err");
            assertEquals(TaskStatus.RUNNING, state.getStatus());
            assertEquals("result", state.getOutput());
            assertEquals("err", state.getError());
        }

        @Test
        @DisplayName("2.4 子任务 ID 列表线程安全")
        void childTaskIds() {
            TaskState state = new TaskState("t4", "s1", TaskStatus.PENDING);
            state.getChildTaskIds().add("child-1");
            state.getChildTaskIds().add("child-2");
            assertEquals(2, state.getChildTaskIds().size());
        }
    }

    // ===== 3. TaskCoordinator 测试 =====

    @Nested
    @DisplayName("3. TaskCoordinator")
    class CoordinatorTests {

        private SimpMessagingTemplate messagingTemplate;
        private TaskCoordinator coordinator;

        @BeforeEach
        void setUp() {
            messagingTemplate = mock(SimpMessagingTemplate.class);
            coordinator = new TaskCoordinator(messagingTemplate);
        }

        @Test
        @DisplayName("3.1 提交任务 — 状态变为 RUNNING 后 COMPLETED")
        void submitTask() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            coordinator.submit("t1", "s1", "test", latch::countDown);
            assertTrue(latch.await(5, TimeUnit.SECONDS));

            // 等待状态更新
            Thread.sleep(100);
            Optional<TaskState> task = coordinator.getTask("t1");
            assertTrue(task.isPresent());
            assertEquals(TaskStatus.COMPLETED, task.get().getStatus());
        }

        @Test
        @DisplayName("3.2 取消任务 — 三层中断传播")
        void cancelTask() throws Exception {
            AtomicBoolean cancelled = new AtomicBoolean(false);
            CountDownLatch started = new CountDownLatch(1);

            coordinator.submit("t2", "s1", "long task", () -> {
                started.countDown();
                try {
                    Thread.sleep(60_000); // 长时间运行
                } catch (InterruptedException e) {
                    cancelled.set(true);
                }
            });

            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread.sleep(50); // 确保状态已更新

            boolean result = coordinator.cancelTask("t2");
            assertTrue(result);

            Thread.sleep(100);
            assertTrue(cancelled.get(), "Task should have been interrupted");

            Optional<TaskState> task = coordinator.getTask("t2");
            assertTrue(task.isPresent());
            assertEquals(TaskStatus.CANCELLED, task.get().getStatus());
        }

        @Test
        @DisplayName("3.3 取消终态任务 — 返回 false")
        void cancelTerminalTask() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            coordinator.submit("t3", "s1", "quick", latch::countDown);
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            Thread.sleep(100);

            assertFalse(coordinator.cancelTask("t3"));
        }

        @Test
        @DisplayName("3.4 取消不存在的任务 — 返回 false")
        void cancelNonExistent() {
            assertFalse(coordinator.cancelTask("nonexistent"));
        }

        @Test
        @DisplayName("3.5 按会话列出任务")
        void listTasks() throws Exception {
            CountDownLatch latch = new CountDownLatch(2);
            coordinator.submit("t4", "s1", "task A", latch::countDown);
            coordinator.submit("t5", "s2", "task B", latch::countDown);
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            Thread.sleep(100);

            List<TaskState> s1Tasks = coordinator.listTasks("s1", null);
            assertEquals(1, s1Tasks.size());
            assertEquals("t4", s1Tasks.get(0).getTaskId());
        }

        @Test
        @DisplayName("3.6 按状态过滤任务")
        void listTasksByStatus() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            coordinator.submit("t6", "s1", "done", latch::countDown);
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            Thread.sleep(100);

            List<TaskState> running = coordinator.listTasks("s1", TaskStatus.RUNNING);
            assertTrue(running.isEmpty());

            List<TaskState> completed = coordinator.listTasks("s1", TaskStatus.COMPLETED);
            assertEquals(1, completed.size());
        }

        @Test
        @DisplayName("3.7 输出截断 — 超过 1MB 截断")
        void truncateOutput() {
            String small = "hello";
            assertEquals(small, TaskCoordinator.truncateOutput(small));

            String large = "x".repeat(1024 * 1024 + 100);
            String truncated = TaskCoordinator.truncateOutput(large);
            assertTrue(truncated.length() < large.length());
            assertTrue(truncated.endsWith("[Output truncated at 1MB limit]"));
        }

        @Test
        @DisplayName("3.8 STOMP 推送 — 任务状态变更时推送")
        void stompNotification() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            coordinator.submit("t7", "s1", "notify test", latch::countDown);
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            Thread.sleep(100);

            // submit 推送 PENDING + RUNNING + COMPLETED = 至少 2 次
            verify(messagingTemplate, atLeast(2))
                    .convertAndSend(eq("/topic/session/s1"), (Object) argThat(payload -> {
                        if (payload instanceof Map<?, ?> map) {
                            return "task_update".equals(map.get("type"));
                        }
                        return false;
                    }));
        }

        @Test
        @DisplayName("3.9 并发限制 — 超过 MAX_CONCURRENT_TASKS 抛异常")
        void maxConcurrentTasks() {
            // 提交 MAX_CONCURRENT_TASKS 个长时间任务
            for (int i = 0; i < TaskCoordinator.MAX_CONCURRENT_TASKS; i++) {
                coordinator.submit("max-" + i, "s1", "task " + i, () -> {
                    try { Thread.sleep(60_000); } catch (InterruptedException ignored) {}
                });
            }

            // 第 11 个应该抛异常
            assertThrows(IllegalStateException.class, () ->
                    coordinator.submit("overflow", "s1", "overflow", () -> {}));

            // 清理
            for (int i = 0; i < TaskCoordinator.MAX_CONCURRENT_TASKS; i++) {
                coordinator.cancelTask("max-" + i);
            }
        }
    }

    // ===== 4. TaskCreateTool 测试 =====

    @Nested
    @DisplayName("4. TaskCreateTool")
    class CreateToolTests {

        private TaskCoordinator coordinator;
        private TaskCreateTool tool;

        @BeforeEach
        void setUp() {
            coordinator = new TaskCoordinator(mock(SimpMessagingTemplate.class));
            tool = new TaskCreateTool(coordinator);
        }

        @Test
        @DisplayName("4.1 工具名称和分组")
        void nameAndGroup() {
            assertEquals("TaskCreate", tool.getName());
            assertEquals("task", tool.getGroup());
        }

        @Test
        @DisplayName("4.2 Schema 包含必需字段")
        void schema() {
            Map<String, Object> schema = tool.getInputSchema();
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) schema.get("properties");
            assertTrue(props.containsKey("description"));
            assertTrue(props.containsKey("prompt"));
            assertTrue(props.containsKey("taskType"));
        }

        @Test
        @DisplayName("4.3 成功创建任务")
        void createSuccess() {
            ToolInput input = ToolInput.from(Map.of(
                    "description", "test task",
                    "prompt", "do something"));
            ToolUseContext ctx = ToolUseContext.of("/tmp", "session-1");

            ToolResult result = tool.call(input, ctx);
            assertFalse(result.isError());
            assertTrue(result.content().contains("created successfully"));
        }

        @Test
        @DisplayName("4.4 并发安全标记")
        void concurrencySafe() {
            assertTrue(tool.isConcurrencySafe(ToolInput.from(Map.of())));
        }
    }

    // ===== 5. TaskUpdateTool 测试 =====

    @Nested
    @DisplayName("5. TaskUpdateTool")
    class UpdateToolTests {

        private TaskCoordinator coordinator;
        private TaskUpdateTool tool;

        @BeforeEach
        void setUp() {
            coordinator = new TaskCoordinator(mock(SimpMessagingTemplate.class));
            tool = new TaskUpdateTool(coordinator);
        }

        @Test
        @DisplayName("5.1 工具名称")
        void name() {
            assertEquals("TaskUpdate", tool.getName());
        }

        @Test
        @DisplayName("5.2 更新已有任务状态")
        void updateStatus() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            coordinator.submit("u1", "s1", "test", () -> {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                latch.countDown();
            });
            Thread.sleep(100);

            ToolInput input = ToolInput.from(Map.of(
                    "taskId", "u1",
                    "status", "COMPLETED"));
            ToolResult result = tool.call(input, ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("COMPLETED"));

            coordinator.cancelTask("u1");
        }

        @Test
        @DisplayName("5.3 更新不存在的任务 — 返回错误")
        void updateNotFound() {
            ToolInput input = ToolInput.from(Map.of("taskId", "nonexistent"));
            ToolResult result = tool.call(input, ToolUseContext.of("/tmp", "s1"));
            assertTrue(result.isError());
            assertTrue(result.content().contains("Task not found"));
        }
    }

    // ===== 6. TaskListTool 测试 =====

    @Nested
    @DisplayName("6. TaskListTool")
    class ListToolTests {

        private TaskCoordinator coordinator;
        private TaskListTool tool;

        @BeforeEach
        void setUp() {
            coordinator = new TaskCoordinator(mock(SimpMessagingTemplate.class));
            tool = new TaskListTool(coordinator);
        }

        @Test
        @DisplayName("6.1 工具名称和只读标记")
        void nameAndReadOnly() {
            assertEquals("TaskList", tool.getName());
            assertTrue(tool.isReadOnly(ToolInput.from(Map.of())));
        }

        @Test
        @DisplayName("6.2 空列表返回友好消息")
        void emptyList() {
            ToolInput input = ToolInput.from(Map.of());
            ToolResult result = tool.call(input, ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("No tasks found"));
        }

        @Test
        @DisplayName("6.3 列出任务")
        void listTasks() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            coordinator.submit("l1", "s1", "task one", latch::countDown);
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            Thread.sleep(100);

            ToolResult result = tool.call(ToolInput.from(Map.of()),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("Tasks (1)"));
            assertTrue(result.content().contains("l1"));
        }
    }

    // ===== 7. TaskGetTool 测试 =====

    @Nested
    @DisplayName("7. TaskGetTool")
    class GetToolTests {

        private TaskCoordinator coordinator;
        private TaskGetTool tool;

        @BeforeEach
        void setUp() {
            coordinator = new TaskCoordinator(mock(SimpMessagingTemplate.class));
            tool = new TaskGetTool(coordinator);
        }

        @Test
        @DisplayName("7.1 工具名称和只读标记")
        void nameAndReadOnly() {
            assertEquals("TaskGet", tool.getName());
            assertTrue(tool.isReadOnly(ToolInput.from(Map.of())));
        }

        @Test
        @DisplayName("7.2 获取已有任务")
        void getExisting() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            coordinator.submit("g1", "s1", "get test", latch::countDown);
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            Thread.sleep(100);

            ToolInput input = ToolInput.from(Map.of("taskId", "g1"));
            ToolResult result = tool.call(input, ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("Task: g1"));
            assertTrue(result.content().contains("Status: COMPLETED"));
        }

        @Test
        @DisplayName("7.3 获取不存在的任务 — 返回错误")
        void getNotFound() {
            ToolInput input = ToolInput.from(Map.of("taskId", "nonexistent"));
            ToolResult result = tool.call(input, ToolUseContext.of("/tmp", "s1"));
            assertTrue(result.isError());
        }
    }

    // ===== 8. TaskStopTool 测试 =====

    @Nested
    @DisplayName("8. TaskStopTool")
    class StopToolTests {

        private TaskCoordinator coordinator;
        private TaskStopTool tool;

        @BeforeEach
        void setUp() {
            coordinator = new TaskCoordinator(mock(SimpMessagingTemplate.class));
            tool = new TaskStopTool(coordinator);
        }

        @Test
        @DisplayName("8.1 工具名称")
        void name() {
            assertEquals("TaskStop", tool.getName());
        }

        @Test
        @DisplayName("8.2 停止运行中的任务")
        void stopRunning() throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            coordinator.submit("s1", "sess1", "long task", () -> {
                started.countDown();
                try { Thread.sleep(60_000); } catch (InterruptedException ignored) {}
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread.sleep(50);

            ToolInput input = ToolInput.from(Map.of("taskId", "s1", "reason", "test"));
            ToolResult result = tool.call(input, ToolUseContext.of("/tmp", "sess1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("cancelled"));
        }

        @Test
        @DisplayName("8.3 停止已完成的任务 — 返回错误")
        void stopCompleted() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            coordinator.submit("s2", "sess1", "quick", latch::countDown);
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            Thread.sleep(100);

            ToolInput input = ToolInput.from(Map.of("taskId", "s2"));
            ToolResult result = tool.call(input, ToolUseContext.of("/tmp", "sess1"));
            assertTrue(result.isError());
            assertTrue(result.content().contains("terminal state"));
        }

        @Test
        @DisplayName("8.4 停止不存在的任务 — 返回错误")
        void stopNotFound() {
            ToolInput input = ToolInput.from(Map.of("taskId", "nonexistent"));
            ToolResult result = tool.call(input, ToolUseContext.of("/tmp", "sess1"));
            assertTrue(result.isError());
        }
    }

    // ===== 9. TaskOutputTool 测试 =====

    @Nested
    @DisplayName("9. TaskOutputTool")
    class OutputToolTests {

        private TaskCoordinator coordinator;
        private TaskOutputTool tool;

        @BeforeEach
        void setUp() {
            coordinator = new TaskCoordinator(mock(SimpMessagingTemplate.class));
            tool = new TaskOutputTool(coordinator);
        }

        @Test
        @DisplayName("9.1 工具名称")
        void name() {
            assertEquals("TaskOutput", tool.getName());
        }

        @Test
        @DisplayName("9.2 非子任务上下文 — 返回错误")
        void notInTaskContext() {
            ToolInput input = ToolInput.from(Map.of("output", "hello"));
            // currentTaskId 为 null
            ToolResult result = tool.call(input, ToolUseContext.of("/tmp", "s1"));
            assertTrue(result.isError());
            assertTrue(result.content().contains("sub-task context"));
        }

        @Test
        @DisplayName("9.3 子任务上下文中输出 — 成功")
        void outputInContext() throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            coordinator.submit("o1", "s1", "output task", () -> {
                started.countDown();
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread.sleep(50);

            ToolInput input = ToolInput.from(Map.of("output", "task result"));
            ToolUseContext ctx = ToolUseContext.of("/tmp", "s1").withCurrentTaskId("o1");

            ToolResult result = tool.call(input, ctx);
            assertFalse(result.isError());
            assertTrue(result.content().contains("reported to parent task"));

            // 验证输出已写入 TaskState
            assertEquals("task result", coordinator.getTask("o1").get().getOutput());

            coordinator.cancelTask("o1");
        }

        @Test
        @DisplayName("9.4 错误输出标记")
        void errorOutput() throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            coordinator.submit("o2", "s1", "error task", () -> {
                started.countDown();
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread.sleep(50);

            ToolInput input = ToolInput.from(Map.of("output", "error msg", "isError", true));
            ToolUseContext ctx = ToolUseContext.of("/tmp", "s1").withCurrentTaskId("o2");

            ToolResult result = tool.call(input, ctx);
            assertFalse(result.isError());
            assertTrue(result.content().contains("(error)"));

            // 验证 error 也被设置
            assertEquals("error msg", coordinator.getTask("o2").get().getError());

            coordinator.cancelTask("o2");
        }

        @Test
        @DisplayName("9.5 超大输出截断")
        void truncateLargeOutput() throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            coordinator.submit("o3", "s1", "big output", () -> {
                started.countDown();
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread.sleep(50);

            String largeOutput = "x".repeat(1024 * 1024 + 500);
            ToolInput input = ToolInput.from(Map.of("output", largeOutput));
            ToolUseContext ctx = ToolUseContext.of("/tmp", "s1").withCurrentTaskId("o3");

            ToolResult result = tool.call(input, ctx);
            assertFalse(result.isError());

            String stored = coordinator.getTask("o3").get().getOutput();
            assertTrue(stored.endsWith("[Output truncated at 1MB limit]"));

            coordinator.cancelTask("o3");
        }
    }

    // ===== 10. ToolUseContext currentTaskId 测试 =====

    @Nested
    @DisplayName("10. ToolUseContext currentTaskId")
    class ContextTests {

        @Test
        @DisplayName("10.1 默认 currentTaskId 为 null")
        void defaultNull() {
            ToolUseContext ctx = ToolUseContext.of("/tmp", "s1");
            assertNull(ctx.currentTaskId());
        }

        @Test
        @DisplayName("10.2 withCurrentTaskId 设置任务 ID")
        void withTaskId() {
            ToolUseContext ctx = ToolUseContext.of("/tmp", "s1").withCurrentTaskId("task-1");
            assertEquals("task-1", ctx.currentTaskId());
        }

        @Test
        @DisplayName("10.3 withCurrentTaskId 保留其他字段")
        void preserveFields() {
            ToolUseContext original = ToolUseContext.of("/work", "s2")
                    .withNestingDepth(2);
            ToolUseContext withTask = original.withCurrentTaskId("task-2");
            assertEquals("/work", withTask.workingDirectory());
            assertEquals("s2", withTask.sessionId());
            assertEquals(2, withTask.nestingDepth());
            assertEquals("task-2", withTask.currentTaskId());
        }

        @Test
        @DisplayName("10.4 兼容旧 7 参数构造")
        void backwardCompatible7Args() {
            ToolUseContext ctx = new ToolUseContext("/tmp", "s1", "tid", null, List.of(), false, 1);
            assertEquals(1, ctx.nestingDepth());
            assertNull(ctx.currentTaskId());
        }
    }
}
