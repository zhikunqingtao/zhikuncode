package com.aicodeassistant.tool.interaction;

import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * AskUserQuestionTool — 向用户提出多选问题。
 * <p>
 * 通过 WebSocket 推送问题到前端，阻塞等待用户选择（带 5 分钟超时）。
 * 前端用户选择后通过 {@link #receiveAnswer(String, Map)} 回调完成 Future。
 * <p>
 * 输入验证: 1-4 个问题，每个 2-4 个选项。
 *
 * @see <a href="SPEC §4.1.6">AskUserQuestionTool</a>
 */
@Component
public class AskUserQuestionTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(AskUserQuestionTool.class);
    private static final Duration QUESTION_TIMEOUT = Duration.ofMinutes(5);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SimpMessagingTemplate messagingTemplate;
    private final ConcurrentMap<String, CompletableFuture<Map<String, String>>> pendingQuestions
            = new ConcurrentHashMap<>();

    public AskUserQuestionTool(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public String getName() {
        return "AskUserQuestion";
    }

    @Override
    public String getDescription() {
        return "Ask the user a multiple-choice question. " +
                "Supports 1-4 questions, each with 2-4 options. " +
                "The tool blocks until the user responds or times out.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "questions", Map.of(
                                "type", "array",
                                "description", "List of questions to ask (1-4)",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "question", Map.of("type", "string"),
                                                "options", Map.of(
                                                        "type", "array",
                                                        "items", Map.of(
                                                                "type", "object",
                                                                "properties", Map.of(
                                                                        "label", Map.of("type", "string"),
                                                                        "description", Map.of("type", "string")
                                                                )
                                                        )
                                                ),
                                                "multiSelect", Map.of("type", "boolean")
                                        )
                                )
                        )
                ),
                "required", List.of("questions")
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
    public boolean requiresUserInteraction() {
        return true;
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult call(ToolInput input, ToolUseContext context) {
        List<Map<String, Object>> questions = (List<Map<String, Object>>)
                input.getRawData().get("questions");

        // 1. 输入验证: 1-4 个问题，每个 2-4 个选项
        if (questions == null || questions.isEmpty() || questions.size() > 4) {
            return ToolResult.error("Must provide 1-4 questions.");
        }
        for (Map<String, Object> q : questions) {
            List<Map<String, String>> options = (List<Map<String, String>>) q.get("options");
            if (options == null || options.size() < 2 || options.size() > 4) {
                return ToolResult.error(
                        "Each question must have 2-4 options. Got: "
                                + (options == null ? 0 : options.size()));
            }
        }

        // 2. 生成唯一请求 ID
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<Map<String, String>> future = new CompletableFuture<>();
        pendingQuestions.put(requestId, future);

        // 3. 通过 WebSocket 推送问题到前端
        messagingTemplate.convertAndSend(
                "/topic/session/" + context.sessionId(),
                Map.of("type", "ask_user_question",
                        "requestId", requestId,
                        "questions", questions));

        log.info("AskUserQuestion: sent {} questions, requestId={}", questions.size(), requestId);

        // 4. 阻塞等待用户选择（带超时）
        try {
            Map<String, String> answers = future.get(
                    QUESTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            // 5. 构建结果
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("questions", questions);
            result.put("answers", answers);
            return ToolResult.success(MAPPER.writeValueAsString(result));

        } catch (TimeoutException e) {
            pendingQuestions.remove(requestId);
            return ToolResult.error(
                    "User did not respond within " + QUESTION_TIMEOUT.toMinutes() + " minutes.");
        } catch (Exception e) {
            pendingQuestions.remove(requestId);
            return ToolResult.error("Error waiting for user response: " + e.getMessage());
        }
    }

    /**
     * WebSocket 回调入口 — 前端用户选择后调用。
     * 通过 @MessageMapping("/answer/{requestId}") 映射。
     */
    public void receiveAnswer(String requestId, Map<String, String> answers) {
        CompletableFuture<Map<String, String>> future = pendingQuestions.remove(requestId);
        if (future != null) {
            future.complete(answers);
        } else {
            log.warn("Received answer for unknown requestId: {}", requestId);
        }
    }

    /** 获取待处理问题数（测试用） */
    int getPendingCount() {
        return pendingQuestions.size();
    }
}
