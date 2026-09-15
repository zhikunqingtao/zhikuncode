package com.aicodeassistant.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TaskBoundaryTracker 离线单测 — 模拟同一 run 内连续的 TodoWrite 结果序列，
 * 验证"首次进入 IN_PROGRESS"边界检测、run 内去重与 seq 递增语义。
 */
class TaskBoundaryTrackerTest {

    private final TaskBoundaryTracker tracker = new TaskBoundaryTracker();

    private static String todo(String id, String content, String status) {
        return "{\"id\":\"" + id + "\",\"content\":\"" + content + "\",\"status\":\"" + status + "\"}";
    }

    /** 构造 TodoWriteTool 的 result content JSON：{"oldTodos":[...],"newTodos":[...]} */
    private static String todoWriteResult(String oldTodos, String newTodos) {
        return "{\"oldTodos\":[" + oldTodos + "],\"newTodos\":[" + newTodos + "]}";
    }

    @Test
    void pendingToInProgressEmitsBoundaryWithSeq1() {
        List<TaskBoundaryTracker.TaskBoundary> boundaries = tracker.onTodoWriteResult(
                todoWriteResult(todo("a", "Task A", "PENDING"),
                                todo("a", "Task A", "IN_PROGRESS")));

        assertThat(boundaries).hasSize(1);
        assertThat(boundaries.get(0).taskId()).isEqualTo("a");
        assertThat(boundaries.get(0).title()).isEqualTo("Task A");
        assertThat(boundaries.get(0).seq()).isEqualTo(1);
    }

    @Test
    void completePlusNewInProgressInSameWriteEmitsOnlyTheNewTask() {
        // 第一次 TodoWrite：任务A 进入 IN_PROGRESS（seq=1）
        tracker.onTodoWriteResult(
                todoWriteResult(todo("a", "Task A", "PENDING"),
                                todo("a", "Task A", "IN_PROGRESS")));

        // 第二次 TodoWrite：任务A IN_PROGRESS→COMPLETE，任务B PENDING→IN_PROGRESS
        List<TaskBoundaryTracker.TaskBoundary> boundaries = tracker.onTodoWriteResult(
                todoWriteResult(todo("a", "Task A", "IN_PROGRESS") + "," + todo("b", "Task B", "PENDING"),
                                todo("a", "Task A", "COMPLETE") + "," + todo("b", "Task B", "IN_PROGRESS")));

        assertThat(boundaries).hasSize(1);
        assertThat(boundaries.get(0).taskId()).isEqualTo("b");
        assertThat(boundaries.get(0).title()).isEqualTo("Task B");
        assertThat(boundaries.get(0).seq()).isEqualTo(2);
    }

    @Test
    void repeatedInProgressAndReTransitionsDoNotEmitAgain() {
        // 任务A 进入 IN_PROGRESS（seq=1），任务B 进入 IN_PROGRESS（seq=2）
        tracker.onTodoWriteResult(
                todoWriteResult("",
                        todo("a", "Task A", "IN_PROGRESS") + "," + todo("b", "Task B", "IN_PROGRESS")));

        // 任务B 保持 IN_PROGRESS 的重复 TodoWrite（merge 重写同状态条目）→ 不产生
        assertThat(tracker.onTodoWriteResult(
                todoWriteResult(todo("b", "Task B", "IN_PROGRESS"),
                                todo("a", "Task A", "IN_PROGRESS") + "," + todo("b", "Task B", "IN_PROGRESS"))))
                .isEmpty();

        // 任务A COMPLETE→IN_PROGRESS 反复流转 → run 内已发过，不再产生
        assertThat(tracker.onTodoWriteResult(
                todoWriteResult(todo("a", "Task A", "COMPLETE"),
                                todo("a", "Task A", "IN_PROGRESS"))))
                .isEmpty();
    }

    @Test
    void newTaskAppearingDirectlyAsInProgressEmits() {
        // oldTodos 为空，新任务首次出现即 IN_PROGRESS
        List<TaskBoundaryTracker.TaskBoundary> boundaries = tracker.onTodoWriteResult(
                todoWriteResult("", todo("c", "Task C", "IN_PROGRESS")));

        assertThat(boundaries).hasSize(1);
        assertThat(boundaries.get(0).taskId()).isEqualTo("c");
        assertThat(boundaries.get(0).seq()).isEqualTo(1);
    }

    @Test
    void multipleTasksEnteringInProgressEmitInTodoOrder() {
        List<TaskBoundaryTracker.TaskBoundary> boundaries = tracker.onTodoWriteResult(
                todoWriteResult(todo("a", "Task A", "PENDING") + "," + todo("b", "Task B", "PENDING"),
                                todo("a", "Task A", "IN_PROGRESS") + "," + todo("b", "Task B", "IN_PROGRESS")));

        assertThat(boundaries).hasSize(2);
        assertThat(boundaries.get(0).taskId()).isEqualTo("a");
        assertThat(boundaries.get(0).seq()).isEqualTo(1);
        assertThat(boundaries.get(1).taskId()).isEqualTo("b");
        assertThat(boundaries.get(1).seq()).isEqualTo(2);
    }

    @Test
    void nonInProgressTransitionsDoNotEmit() {
        // PENDING→COMPLETE 不经过 IN_PROGRESS → 不产生
        assertThat(tracker.onTodoWriteResult(
                todoWriteResult(todo("a", "Task A", "PENDING"),
                                todo("a", "Task A", "COMPLETE"))))
                .isEmpty();
    }

    @Test
    void malformedOrNonTodoWritePayloadsAreIgnoredWithoutThrowing() {
        assertThat(tracker.onTodoWriteResult(null)).isEmpty();
        assertThat(tracker.onTodoWriteResult("")).isEmpty();
        assertThat(tracker.onTodoWriteResult("   ")).isEmpty();
        assertThat(tracker.onTodoWriteResult("not json at all")).isEmpty();
        assertThat(tracker.onTodoWriteResult("{}")).isEmpty();
        // 其他工具的 JSON（无 newTodos 键）
        assertThat(tracker.onTodoWriteResult("{\"result\":\"ok\",\"exitCode\":0}")).isEmpty();
        // newTodos 不是数组
        assertThat(tracker.onTodoWriteResult("{\"oldTodos\":[],\"newTodos\":{}}")).isEmpty();
        // 坏 JSON（截断）
        assertThat(tracker.onTodoWriteResult("{\"newTodos\":[{\"id\":\"a\"")).isEmpty();
        // 缺 id / 缺 status 的条目安全跳过
        assertThat(tracker.onTodoWriteResult(
                "{\"newTodos\":[{\"content\":\"no id\",\"status\":\"IN_PROGRESS\"},"
                        + "{\"id\":\"x\",\"content\":\"no status\"}]}"))
                .isEmpty();
    }
}
