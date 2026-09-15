package com.aicodeassistant.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 任务边界追踪器 — run 作用域，检测 TodoWrite 结果中"首次进入 IN_PROGRESS"的任务。
 * <p>
 * 每次 {@link QueryEngine#execute} 对应一个 run（一个 {@link QueryLoopState}），
 * tracker 挂在 state 上，run 结束随 state 一起回收，无需显式清理。
 * <p>
 * 语义：
 * <ul>
 *   <li>某 todo 从非 IN_PROGRESS（含新出现）变为 IN_PROGRESS → 产生一条边界</li>
 *   <li>同一 run 内同一任务 id 只产生一次（之后 COMPLETE→IN_PROGRESS 反复流转不再产生）</li>
 *   <li>seq 从 1 开始按产生顺序递增</li>
 * </ul>
 * 输入为 TodoWriteTool 返回的 result content JSON（含 oldTodos/newTodos 数组），
 * 与工具的具体存储实现解耦，纯函数式可离线单测。
 */
public final class TaskBoundaryTracker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一次任务边界：任务首次进入 IN_PROGRESS */
    public record TaskBoundary(String taskId, String title, int seq) {}

    private final Set<String> emittedTaskIds = new HashSet<>();
    private int seq = 0;

    /**
     * 消费一次 TodoWrite 工具的 result content，返回本次新产生的边界（按 todos 出现序）。
     * 非 JSON / 无 newTodos / 无新进入 IN_PROGRESS 的任务 → 空列表。
     */
    public List<TaskBoundary> onTodoWriteResult(String resultContent) {
        if (resultContent == null || resultContent.isBlank()) return List.of();
        JsonNode root;
        try {
            root = MAPPER.readTree(resultContent);
        } catch (Exception notJson) {
            return List.of();
        }
        if (root == null || !root.isObject()) return List.of();
        JsonNode newTodos = root.get("newTodos");
        if (newTodos == null || !newTodos.isArray()) return List.of();

        Map<String, String> oldStatusById = new HashMap<>();
        JsonNode oldTodos = root.get("oldTodos");
        if (oldTodos != null && oldTodos.isArray()) {
            for (JsonNode todo : oldTodos) {
                String id = textOrNull(todo.get("id"));
                if (id != null) {
                    oldStatusById.put(id, textOrNull(todo.get("status")));
                }
            }
        }

        List<TaskBoundary> boundaries = new ArrayList<>();
        for (JsonNode todo : newTodos) {
            if (!"IN_PROGRESS".equals(textOrNull(todo.get("status")))) continue;
            String taskId = textOrNull(todo.get("id"));
            if (taskId == null || taskId.isBlank()) continue;
            // 旧状态已是 IN_PROGRESS → 不是"进入"（例如 merge 重写同状态条目）
            if ("IN_PROGRESS".equals(oldStatusById.get(taskId))) continue;
            // run 内去重：同一任务只发一次
            if (!emittedTaskIds.add(taskId)) continue;
            boundaries.add(new TaskBoundary(taskId, textOrNull(todo.get("content")), ++seq));
        }
        return boundaries;
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }
}
