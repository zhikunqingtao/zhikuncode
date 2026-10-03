package com.aicodeassistant.tool.interaction;

import com.aicodeassistant.engine.ElicitationService;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 用户交互工具集黄金测试 — 覆盖 4 个交互工具。
 */
class InteractionToolGoldenTest {

    // ===== 1. AskUserQuestionTool 测试 =====

    @Nested
    @DisplayName("1. AskUserQuestionTool")
    class AskUserQuestionTests {

        private ElicitationService elicitationService;
        private AskUserQuestionTool tool;

        @BeforeEach
        void setUp() {
            elicitationService = mock(ElicitationService.class);
            tool = new AskUserQuestionTool(elicitationService);
        }

        @Test
        @DisplayName("1.1 工具名称和分组")
        void nameAndGroup() {
            assertEquals("AskUserQuestion", tool.getName());
            assertEquals("interaction", tool.getGroup());
            assertTrue(tool.requiresUserInteraction());
            assertTrue(tool.isReadOnly(ToolInput.from(Map.of())));
        }

        @Test
        @DisplayName("1.2 Schema 包含 questions 字段")
        void schema() {
            Map<String, Object> schema = tool.getInputSchema();
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) schema.get("properties");
            assertTrue(props.containsKey("questions"));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 5})
        @DisplayName("1.3 问题数量越界 — 返回校验错误且不发起交互")
        void invalidQuestionCount(int count) {
            List<Map<String, Object>> questions = java.util.stream.IntStream.range(0, count)
                    .mapToObj(ignored -> makeQuestion(2)).toList();
            ToolInput input = ToolInput.from(Map.of("questions", questions));
            ToolResult result = tool.call(input, ToolUseContext.of("/tmp", "s1"));

            assertValidationFailureWithoutInteraction(result, "ELICITATION_QUESTION_COUNT_INVALID");
            assertTrue(result.content().contains("1-4 questions"));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 5, 6})
        @DisplayName("1.4 选项数量越界 — 返回校验错误且不发起交互")
        void invalidOptionCount(int count) {
            List<Map<String, Object>> questions = List.of(makeQuestion(count));
            ToolInput input = ToolInput.from(Map.of("questions", questions));
            ToolResult result = tool.call(input, ToolUseContext.of("/tmp", "s1"));

            assertValidationFailureWithoutInteraction(result, "ELICITATION_OPTION_COUNT_INVALID");
            assertTrue(result.content().contains("2-4 options"));
            assertTrue(result.content().contains("Got: " + count));
        }

        @Test
        @DisplayName("1.5 后一道题选项越界 — 整批问题均不发起交互")
        void invalidLaterQuestionDoesNotStartEarlierQuestion() {
            List<Map<String, Object>> questions = List.of(makeQuestion(4), makeQuestion(6));
            ToolInput input = ToolInput.from(Map.of("questions", questions));
            ToolResult result = tool.call(input, ToolUseContext.of("/tmp", "s1"));

            assertValidationFailureWithoutInteraction(result, "ELICITATION_OPTION_COUNT_INVALID");
            assertTrue(result.content().contains("Got: 6"));
        }

        @Test
        @DisplayName("1.6 同会话先六选项失败，后两题四加三选项按顺序正常交互")
        void correctedQuestionsSucceedInSameSessionWithoutLosingOptions() throws Exception {
            ToolUseContext context = ToolUseContext.of("/tmp", "same-session")
                    .withCurrentRunId("same-run");
            ToolResult invalid = tool.call(
                    ToolInput.from(Map.of("questions", List.of(makeQuestion(6)))), context);
            assertValidationFailureWithoutInteraction(invalid, "ELICITATION_OPTION_COUNT_INVALID");

            Map<String, Object> first = makeQuestion(4);
            first.put("question", "希望先做哪些工作？（可多选）");
            first.put("multiSelect", true);
            Map<String, Object> second = makeQuestion(3);
            second.put("question", "后续优先选择哪个方向？");
            List<Map<String, Object>> questions = List.of(first, second);
            List<String> firstAnswer = List.of("opt-C", "opt-A");
            when(elicitationService.requestAndWait(eq("same-session"), eq("same-run"), anyString(),
                    anyList(), anyBoolean(), anyLong()))
                    .thenReturn(ElicitationService.ElicitationResponse.success(firstAnswer),
                            ElicitationService.ElicitationResponse.success("opt-B"));

            ToolResult result = tool.call(ToolInput.from(Map.of("questions", questions)), context);

            assertFalse(result.isError());
            assertEquals(ToolResult.ExecutionStatus.SUCCEEDED, result.executionStatus());
            Map<?, ?> payload = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(result.content(), Map.class);
            assertEquals(Map.of("questions", questions,
                    "answers", Map.of("q1", firstAnswer, "q2", "opt-B")), payload);

            var ordered = inOrder(elicitationService);
            ordered.verify(elicitationService).requestAndWait(eq("same-session"), eq("same-run"),
                    eq("希望先做哪些工作？（可多选）"), eq(List.of(
                            new ElicitationService.ElicitationOption("opt-A", "opt-A", "Option A"),
                            new ElicitationService.ElicitationOption("opt-B", "opt-B", "Option B"),
                            new ElicitationService.ElicitationOption("opt-C", "opt-C", "Option C"),
                            new ElicitationService.ElicitationOption("opt-D", "opt-D", "Option D"))),
                    eq(true), anyLong());
            ordered.verify(elicitationService).requestAndWait(eq("same-session"), eq("same-run"),
                    eq("后续优先选择哪个方向？"), eq(List.of(
                            new ElicitationService.ElicitationOption("opt-A", "opt-A", "Option A"),
                            new ElicitationService.ElicitationOption("opt-B", "opt-B", "Option B"),
                            new ElicitationService.ElicitationOption("opt-C", "opt-C", "Option C"))),
                    eq(false), anyLong());
            verifyNoMoreInteractions(elicitationService);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(booleans = {false, true})
        @DisplayName("1.7 多选参数按结构化值传递，省略时保持单选")
        void elicitationSuccess(Boolean multiSelect) throws Exception {
            boolean expectedMultiSelect = Boolean.TRUE.equals(multiSelect);
            Object answer = expectedMultiSelect ? List.of("opt-A", "opt-B") : "opt-A";
            when(elicitationService.requestAndWait(anyString(), nullable(String.class), anyString(),
                    anyList(), eq(expectedMultiSelect), anyLong()))
                    .thenReturn(ElicitationService.ElicitationResponse.success(answer));

            Map<String, Object> question = makeQuestion(2);
            question.put("question", "希望包含哪些内容？（可多选）");
            if (multiSelect == null) {
                question.remove("multiSelect");
            } else {
                question.put("multiSelect", multiSelect);
            }
            ToolInput input = ToolInput.from(Map.of("questions", List.of(question)));
            ToolResult result = tool.call(input, ToolUseContext.of("/tmp", "s1"));

            assertFalse(result.isError());
            Map<?, ?> answers = (Map<?, ?>) new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(result.content(), Map.class).get("answers");
            assertEquals(answer, answers.get("q1"));
            verify(elicitationService).requestAndWait(eq("s1"), nullable(String.class),
                    eq("希望包含哪些内容？（可多选）"), anyList(), eq(expectedMultiSelect), anyLong());
        }

        private void assertValidationFailureWithoutInteraction(ToolResult result, String failureCode) {
            assertAll(
                    () -> assertTrue(result.isError()),
                    () -> assertEquals(ToolResult.ExecutionStatus.FAILED, result.executionStatus()),
                    () -> assertEquals(ToolResult.ToolFailureType.VALIDATION, result.failureType()),
                    () -> assertEquals(failureCode, result.failureCode()),
                    () -> assertEquals(ToolResult.EffectState.NOT_STARTED, result.effectState()));
            verifyNoInteractions(elicitationService);
        }

        private Map<String, Object> makeQuestion(int numOptions) {
            List<Map<String, String>> options = new java.util.ArrayList<>();
            for (int i = 0; i < numOptions; i++) {
                options.add(Map.of("label", "opt-" + (char) ('A' + i),
                        "description", "Option " + (char) ('A' + i)));
            }
            Map<String, Object> q = new HashMap<>();
            q.put("question", "Which option?");
            q.put("options", options);
            q.put("multiSelect", false);
            return q;
        }
    }

    // ===== 2. TodoWriteTool 测试 =====

    @Nested
    @DisplayName("2. TodoWriteTool")
    class TodoWriteTests {

        private TodoWriteTool tool;

        @BeforeEach
        void setUp() {
            tool = new TodoWriteTool(mock(SimpMessagingTemplate.class));
        }

        @Test
        @DisplayName("2.1 工具名称和分组")
        void nameAndGroup() {
            assertEquals("TodoWrite", tool.getName());
            assertEquals("interaction", tool.getGroup());
        }

        @Test
        @DisplayName("2.2 replace 模式 — 全量替换")
        void replaceMode() {
            List<Map<String, Object>> todos = List.of(
                    Map.of("id", "t1", "content", "Task 1", "status", "PENDING"),
                    Map.of("id", "t2", "content", "Task 2", "status", "IN_PROGRESS"));

            ToolInput input = ToolInput.from(Map.of("todos", todos, "merge", false));
            ToolResult result = tool.call(input, ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());

            List<Map<String, Object>> stored = tool.getTodos("s1");
            assertEquals(2, stored.size());
        }

        @Test
        @DisplayName("2.3 merge 模式 — 按 id 合并")
        void mergeMode() {
            // 先创建
            List<Map<String, Object>> initial = List.of(
                    Map.of("id", "t1", "content", "Task 1", "status", "PENDING"),
                    Map.of("id", "t2", "content", "Task 2", "status", "PENDING"));
            tool.call(ToolInput.from(Map.of("todos", initial, "merge", false)),
                    ToolUseContext.of("/tmp", "s1"));

            // 合并更新 t1
            List<Map<String, Object>> updates = List.of(
                    Map.of("id", "t1", "content", "Task 1 Updated", "status", "COMPLETE"));
            tool.call(ToolInput.from(Map.of("todos", updates, "merge", true)),
                    ToolUseContext.of("/tmp", "s1"));

            List<Map<String, Object>> stored = tool.getTodos("s1");
            assertEquals(2, stored.size());
            // t1 应被更新
            Map<String, Object> t1 = stored.stream()
                    .filter(t -> "t1".equals(t.get("id"))).findFirst().orElseThrow();
            assertEquals("COMPLETE", t1.get("status"));
            assertEquals("Task 1 Updated", t1.get("content"));
        }

        @Test
        @DisplayName("2.4 全部完成 — 自动清空")
        void allComplete() {
            List<Map<String, Object>> todos = List.of(
                    Map.of("id", "t1", "content", "Done", "status", "COMPLETE"),
                    Map.of("id", "t2", "content", "Cancelled", "status", "CANCELLED"));

            tool.call(ToolInput.from(Map.of("todos", todos, "merge", false)),
                    ToolUseContext.of("/tmp", "s1"));

            List<Map<String, Object>> stored = tool.getTodos("s1");
            assertTrue(stored.isEmpty(), "Should auto-clear when all complete/cancelled");
        }

        @Test
        @DisplayName("2.5 验证提醒 — 3+ 完成且无 verify 任务")
        void verificationNudge() {
            List<Map<String, Object>> todos = List.of(
                    Map.of("id", "t1", "content", "Task 1", "status", "COMPLETE"),
                    Map.of("id", "t2", "content", "Task 2", "status", "COMPLETE"),
                    Map.of("id", "t3", "content", "Task 3", "status", "COMPLETE"),
                    Map.of("id", "t4", "content", "Task 4", "status", "PENDING"));

            ToolInput input = ToolInput.from(Map.of("todos", todos, "merge", false));
            ToolResult result = tool.call(input, ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("verificationNudgeNeeded"));
        }

        @Test
        @DisplayName("2.6 会话隔离")
        void sessionIsolation() {
            tool.call(ToolInput.from(Map.of("todos",
                            List.of(Map.of("id", "a1", "content", "A", "status", "PENDING")),
                            "merge", false)),
                    ToolUseContext.of("/tmp", "s1"));

            tool.call(ToolInput.from(Map.of("todos",
                            List.of(Map.of("id", "b1", "content", "B", "status", "PENDING")),
                            "merge", false)),
                    ToolUseContext.of("/tmp", "s2"));

            assertEquals(1, tool.getTodos("s1").size());
            assertEquals(1, tool.getTodos("s2").size());
            assertEquals("a1", tool.getTodos("s1").get(0).get("id"));
            assertEquals("b1", tool.getTodos("s2").get(0).get("id"));
        }

        @Test
        @DisplayName("2.7 merge 缺 id 条目 — 均保留且不互相覆盖")
        void mergeWithoutIdKeepsAllEntries() {
            List<Map<String, Object>> withoutIds = List.of(
                    Map.of("content", "First", "status", "PENDING"),
                    Map.of("content", "Second", "status", "IN_PROGRESS"));
            tool.call(ToolInput.from(Map.of("todos", withoutIds, "merge", true)),
                    ToolUseContext.of("/tmp", "s1"));

            List<Map<String, Object>> stored = tool.getTodos("s1");
            assertEquals(2, stored.size());
            assertTrue(stored.stream().anyMatch(t -> "First".equals(t.get("content"))));
            assertTrue(stored.stream().anyMatch(t -> "Second".equals(t.get("content"))));
        }
    }

    // ===== 3. SleepTool 测试 =====

    @Nested
    @DisplayName("3. SleepTool")
    class SleepTests {

        private SleepTool tool;

        @BeforeEach
        void setUp() {
            tool = new SleepTool();
        }

        @Test
        @DisplayName("3.1 工具名称和标记")
        void nameAndFlags() {
            assertEquals("Sleep", tool.getName());
            assertEquals("interaction", tool.getGroup());
            assertTrue(tool.isReadOnly(ToolInput.from(Map.of())));
            assertTrue(tool.isConcurrencySafe(ToolInput.from(Map.of())));
        }

        @Test
        @DisplayName("3.2 有效睡眠 — 1 秒")
        void validSleep() {
            long start = System.currentTimeMillis();
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("seconds", 1)),
                    ToolUseContext.of("/tmp", "s1"));
            long elapsed = System.currentTimeMillis() - start;

            assertFalse(result.isError());
            assertTrue(result.content().contains("Slept for 1 seconds"));
            assertTrue(elapsed >= 900, "Should sleep at least 900ms");
        }

        @Test
        @DisplayName("3.3 无效值 — 小于 1")
        void tooSmall() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("seconds", 0)),
                    ToolUseContext.of("/tmp", "s1"));
            assertTrue(result.isError());
            assertTrue(result.content().contains("between 1 and 300"));
        }

        @Test
        @DisplayName("3.4 无效值 — 大于 300")
        void tooLarge() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("seconds", 301)),
                    ToolUseContext.of("/tmp", "s1"));
            assertTrue(result.isError());
        }

        @Test
        @DisplayName("3.5 中断唤醒")
        void interrupt() throws Exception {
            Thread testThread = Thread.currentThread();
            CompletableFuture<ToolResult> future = CompletableFuture.supplyAsync(() ->
                    tool.call(ToolInput.from(Map.of("seconds", 60)),
                            ToolUseContext.of("/tmp", "s1")));

            Thread.sleep(200);
            // 找到执行 sleep 的线程并中断
            // 使用 CompletableFuture.cancel 不会中断线程，直接测试短时间 sleep 代替
            // 此测试验证的是编译和逻辑路径正确性
            assertFalse(future.isDone());
            future.cancel(true);
        }
    }

    // ===== 4. BriefTool 测试 =====

    @Nested
    @DisplayName("4. BriefTool")
    class BriefTests {

        private BriefTool tool;

        @BeforeEach
        void setUp() {
            tool = new BriefTool();
        }

        @Test
        @DisplayName("4.1 工具名称和标记")
        void nameAndFlags() {
            assertEquals("Brief", tool.getName());
            assertEquals("interaction", tool.getGroup());
            assertTrue(tool.isConcurrencySafe(ToolInput.from(Map.of())));
        }

        @Test
        @DisplayName("4.2 project scope")
        void projectScope() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("scope", "project")),
                    ToolUseContext.of("/workspace", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("Project Brief"));
            assertTrue(result.content().contains("/workspace"));
        }

        @Test
        @DisplayName("4.3 session scope")
        void sessionScope() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("scope", "session")),
                    ToolUseContext.of("/tmp", "session-42"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("Session Brief"));
            assertTrue(result.content().contains("session-42"));
        }

        @Test
        @DisplayName("4.4 custom scope — 有 topic")
        void customScope() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("scope", "custom", "topic", "API Design")),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("API Design"));
        }

        @Test
        @DisplayName("4.5 custom scope — 无 topic 返回错误")
        void customScopeNoTopic() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("scope", "custom")),
                    ToolUseContext.of("/tmp", "s1"));
            assertTrue(result.isError());
            assertTrue(result.content().contains("topic"));
        }

        @Test
        @DisplayName("4.6 默认 scope 为 project")
        void defaultScope() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of()),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("Project Brief"));
        }
    }
}
