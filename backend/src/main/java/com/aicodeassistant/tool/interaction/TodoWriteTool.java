package com.aicodeassistant.tool.interaction;

import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * TodoWriteTool — 创建和管理任务列表。
 * <p>
 * 支持 merge（按 id 合并）和 replace（全量替换）两种模式。
 * 全部完成时自动清空列表。3+ 任务完成且无验证任务时提醒验证。
 *
 */
@Component
public class TodoWriteTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TodoWriteTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SimpMessagingTemplate messagingTemplate;

    /** 内存 Todo 存储 — 按 scopeKey 隔离 */
    private final ConcurrentMap<String, List<Map<String, Object>>> todoStore = new ConcurrentHashMap<>();

    /** 状态别名表（键为大写形式）— `completed` 为旧提示词的遗留写法 */
    private static final Map<String, String> TODO_STATUS_ALIASES = Map.of(
            "PENDING", "PENDING",
            "IN_PROGRESS", "IN_PROGRESS",
            "COMPLETE", "COMPLETE",
            "COMPLETED", "COMPLETE",
            "CANCELLED", "CANCELLED");

    public TodoWriteTool(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public String getName() {
        return "TodoWrite";
    }

    @Override
    public String getDescription() {
        return "Create and manage a task/todo list. " +
                "Supports merge mode (update by id) and replace mode (full replacement). " +
                "Auto-clears when all items are COMPLETE or CANCELLED.";
    }

    @Override
    public String prompt() {
        return """
                Use this tool to create and manage a structured task list for your current coding \
                session. This helps you track progress, organize complex tasks, and demonstrate \
                thoroughness to the user. It also helps the user understand the progress of the \
                task and overall progress of their requests.
                
                ## When to Use This Tool
                Use this tool proactively in these scenarios:
                1. Complex multi-step tasks - When a task requires 3 or more distinct steps
                2. Non-trivial and complex tasks - Tasks that require careful planning
                3. User explicitly requests todo list
                4. User provides multiple tasks - When users provide a list of things to be done
                5. After receiving new instructions - Immediately capture user requirements as todos
                6. When you start working on a task - Mark it as IN_PROGRESS BEFORE beginning work
                7. After completing a task - Mark it as COMPLETE
                
                ## When NOT to Use This Tool
                Skip using this tool when:
                1. There is only a single, straightforward task
                2. The task is trivial
                3. The task can be completed in less than 3 trivial steps
                4. The task is purely conversational or informational
                
                ## Task States and Management
                1. **Task States**: PENDING, IN_PROGRESS, COMPLETE, CANCELLED
                   - Exactly ONE task must be IN_PROGRESS at any time
                   - Mark tasks COMPLETE IMMEDIATELY after finishing
                2. **Task Completion Requirements**:
                   - ONLY mark as COMPLETE when FULLY accomplished
                   - If you encounter errors or blockers, keep as IN_PROGRESS
                   - Never mark as COMPLETE if tests are failing or implementation is partial
                3. **Task Breakdown**:
                   - Create specific, actionable items
                   - Break complex tasks into smaller, manageable steps
                   - Always provide both id and content, and set status to one of the states above
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "todos", Map.of(
                                "type", "array",
                                "description", "List of todo items",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "id", Map.of("type", "string"),
                                                "content", Map.of("type", "string"),
                                                "status", Map.of(
                                                        "type", "string",
                                                        "enum", List.of("PENDING", "IN_PROGRESS", "COMPLETE", "CANCELLED"))
                                        )
                                )
                        ),
                        "merge", Map.of(
                                "type", "boolean",
                                "description", "true=merge by id, false=replace all")
                ),
                "required", List.of("todos")
        );
    }

    @Override
    public String getGroup() {
        return "interaction";
    }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.NONE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult call(ToolInput input, ToolUseContext context) {
        List<Map<String, Object>> newTodos = (List<Map<String, Object>>)
                input.getRawData().get("todos");
        boolean merge = input.getBoolean("merge", false);

        // 1. 归一已提供的非空状态；缺失或 null 保持既有运行行为，不补默认值。
        List<Map<String, Object>> normalizedTodos = new ArrayList<>();
        for (Map<String, Object> todo : newTodos) {
            Object rawStatus = todo.get("status");
            Map<String, Object> normalized = new LinkedHashMap<>(todo);
            if (rawStatus == null) {
                normalizedTodos.add(normalized);
                continue;
            }
            String status = normalizeTodoStatus(rawStatus);
            if (status == null) {
                return ToolResult.validationError("TODO_STATUS_INVALID",
                        "Invalid todo status: " + rawStatus
                                + ". Allowed values: PENDING, IN_PROGRESS, COMPLETE, CANCELLED.");
            }
            normalized.put("status", status);
            normalizedTodos.add(normalized);
        }

        String scopeKey = context.sessionId();

        // 2. 获取当前 todos
        List<Map<String, Object>> oldTodos = todoStore.getOrDefault(scopeKey, List.of());

        // 3. 合并或替换
        List<Map<String, Object>> resultTodos;
        if (merge) {
            // merge=true: 仅对有 id 的条目按 id 合并（新条目覆盖旧条目）；
            // 缺 id 的条目不参与合并、原样保留，互不覆盖
            Map<String, Map<String, Object>> mergedById = new LinkedHashMap<>();
            List<Map<String, Object>> withoutId = new ArrayList<>();
            oldTodos.forEach(t -> collectForMerge(mergedById, withoutId, t));
            normalizedTodos.forEach(t -> collectForMerge(mergedById, withoutId, t));
            resultTodos = new ArrayList<>(mergedById.values());
            resultTodos.addAll(withoutId);
        } else {
            // merge=false: 全量替换
            resultTodos = new ArrayList<>(normalizedTodos);
        }

        // 4. 全部完成检测 → 清空列表
        boolean allComplete = !resultTodos.isEmpty() && resultTodos.stream()
                .allMatch(t -> "COMPLETE".equals(t.get("status"))
                        || "CANCELLED".equals(t.get("status")));
        if (allComplete) {
            resultTodos = List.of();
        }

        // 5. 验证代理提示: 3+ 任务完成 + 无 "verif" 任务 → 提醒验证
        boolean verificationNudgeNeeded = false;
        long completedCount = normalizedTodos.stream()
                .filter(t -> "COMPLETE".equals(t.get("status"))).count();
        boolean hasVerifyTask = resultTodos.stream()
                .anyMatch(t -> ((String) t.getOrDefault("content", ""))
                        .toLowerCase().contains("verif"));
        if (completedCount >= 3 && !hasVerifyTask) {
            verificationNudgeNeeded = true;
        }

        // 6. 更新存储 + WebSocket 推送
        todoStore.put(scopeKey, resultTodos);
        try {
            messagingTemplate.convertAndSend(
                    "/topic/session/" + context.sessionId(),
                    Map.of("type", "todos_update", "todos", resultTodos));
        } catch (Exception e) {
            log.warn("Failed to send todos update: {}", e.getMessage());
        }

        // 7. 构建结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("oldTodos", oldTodos);
        result.put("newTodos", resultTodos);
        if (verificationNudgeNeeded) {
            result.put("verificationNudgeNeeded", true);
        }

        try {
            return ToolResult.success(MAPPER.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            return ToolResult.success("Todos updated. Count: " + resultTodos.size());
        }
    }

    /** 大小写不敏感归一 status 为规范枚举值；未知或空值返回 null。 */
    private static String normalizeTodoStatus(Object rawStatus) {
        if (rawStatus == null) {
            return null;
        }
        return TODO_STATUS_ALIASES.get(rawStatus.toString().toUpperCase(Locale.ROOT));
    }

    /** merge 收集：有 id 的按 id 覆盖合并；缺 id 的保留原条目 */
    private static void collectForMerge(Map<String, Map<String, Object>> mergedById,
                                        List<Map<String, Object>> withoutId,
                                        Map<String, Object> todo) {
        Object id = todo.get("id");
        if (id instanceof String idString && !idString.isEmpty()) {
            mergedById.put(idString, todo);
        } else {
            withoutId.add(todo);
        }
    }

    /** 获取指定 scope 的 todos（测试用） */
    List<Map<String, Object>> getTodos(String scopeKey) {
        return todoStore.getOrDefault(scopeKey, List.of());
    }
}
