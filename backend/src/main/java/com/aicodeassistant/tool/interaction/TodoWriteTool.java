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
 * @see <a href="SPEC §4.1.2">TodoWriteTool</a>
 */
@Component
public class TodoWriteTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TodoWriteTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SimpMessagingTemplate messagingTemplate;

    /** 内存 Todo 存储 — 按 scopeKey 隔离 */
    private final ConcurrentMap<String, List<Map<String, Object>>> todoStore = new ConcurrentHashMap<>();

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
                6. When you start working on a task - Mark it as in_progress BEFORE beginning work
                7. After completing a task - Mark it as completed
                
                ## When NOT to Use This Tool
                Skip using this tool when:
                1. There is only a single, straightforward task
                2. The task is trivial
                3. The task can be completed in less than 3 trivial steps
                4. The task is purely conversational or informational
                
                ## Task States and Management
                1. **Task States**: pending, in_progress, completed
                   - Exactly ONE task must be in_progress at any time
                   - Mark tasks complete IMMEDIATELY after finishing
                2. **Task Completion Requirements**:
                   - ONLY mark as completed when FULLY accomplished
                   - If you encounter errors or blockers, keep as in_progress
                   - Never mark as completed if tests are failing or implementation is partial
                3. **Task Breakdown**:
                   - Create specific, actionable items
                   - Break complex tasks into smaller, manageable steps
                   - Always provide both content (imperative) and activeForm (present continuous)
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

        String scopeKey = context.sessionId();

        // 1. 获取当前 todos
        List<Map<String, Object>> oldTodos = todoStore.getOrDefault(scopeKey, List.of());

        // 2. 合并或替换
        List<Map<String, Object>> resultTodos;
        if (merge) {
            // merge=true: 按 id 合并 — 新列表中的条目覆盖旧列表同 id 条目
            Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
            oldTodos.forEach(t -> merged.put((String) t.get("id"), t));
            newTodos.forEach(t -> merged.put((String) t.get("id"), t));
            resultTodos = new ArrayList<>(merged.values());
        } else {
            // merge=false: 全量替换
            resultTodos = new ArrayList<>(newTodos);
        }

        // 3. 全部完成检测 → 清空列表
        boolean allComplete = !resultTodos.isEmpty() && resultTodos.stream()
                .allMatch(t -> "COMPLETE".equals(t.get("status"))
                        || "CANCELLED".equals(t.get("status")));
        if (allComplete) {
            resultTodos = List.of();
        }

        // 4. 验证代理提示: 3+ 任务完成 + 无 "verif" 任务 → 提醒验证
        boolean verificationNudgeNeeded = false;
        long completedCount = newTodos.stream()
                .filter(t -> "COMPLETE".equals(t.get("status"))).count();
        boolean hasVerifyTask = resultTodos.stream()
                .anyMatch(t -> ((String) t.getOrDefault("content", ""))
                        .toLowerCase().contains("verif"));
        if (completedCount >= 3 && !hasVerifyTask) {
            verificationNudgeNeeded = true;
        }

        // 5. 更新存储 + WebSocket 推送
        todoStore.put(scopeKey, resultTodos);
        try {
            messagingTemplate.convertAndSend(
                    "/topic/session/" + context.sessionId(),
                    Map.of("type", "todos_update", "todos", resultTodos));
        } catch (Exception e) {
            log.warn("Failed to send todos update: {}", e.getMessage());
        }

        // 6. 构建结果
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

    /** 获取指定 scope 的 todos（测试用） */
    List<Map<String, Object>> getTodos(String scopeKey) {
        return todoStore.getOrDefault(scopeKey, List.of());
    }
}
