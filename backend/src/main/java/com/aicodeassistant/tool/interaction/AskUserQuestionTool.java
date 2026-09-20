package com.aicodeassistant.tool.interaction;

import com.aicodeassistant.engine.ElicitationService;
import com.aicodeassistant.engine.ElicitationService.ElicitationOption;
import com.aicodeassistant.engine.ElicitationService.ElicitationResponse;
import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * AskUserQuestionTool — 向用户提出多选问题。
 * <p>
 * 通过 {@link ElicitationService} 逐个推送问题到前端，复用已有的 ElicitationDialog UI。
 * 每个问题等待投递及用户选择，期限由持久交互服务管理。
 * <p>
 * 输入验证: 1-4 个问题，每个 2-4 个选项。
 */
@Component
public class AskUserQuestionTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(AskUserQuestionTool.class);
    private static final long QUESTION_TIMEOUT_MS = 5 * 60 * 1000L; // 5 分钟
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ElicitationService elicitationService;

    public AskUserQuestionTool(ElicitationService elicitationService) {
        this.elicitationService = elicitationService;
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
    public String prompt() {
        return """
                Use this tool when you need to ask the user questions during execution. \
                This allows you to:
                1. Gather user preferences or requirements
                2. Clarify ambiguous instructions
                3. Get decisions on implementation choices as you work
                4. Offer choices to the user about what direction to take.
                
                Usage notes:
                - Users will always be able to select "Other" to provide custom text input
                - Set multiSelect on each question: true allows multiple answers; false or omission allows only one.
                - Keep question wording consistent with multiSelect. If the question says "可多选" or \
                allows multiple answers, you must set that question's multiSelect to true. Wording alone does not enable it.
                - If you recommend a specific option, make that the first option in the list and \
                add "(Recommended)" at the end of the label
                
                Multiple-selection example:
                {"questions":[{"question":"希望包含哪些内容？（可多选）","multiSelect":true,"options":[
                  {"label":"历史背景","description":"介绍时代背景"},
                  {"label":"制度变化","description":"介绍制度演进"}]}]}

                Plan mode note: In plan mode, use this tool to clarify requirements or choose \
                between approaches BEFORE finalizing your plan. Do NOT use this tool to ask \
                "Is my plan ready?" or "Should I proceed?" - use ExitPlanMode for plan approval. \
                IMPORTANT: Do not reference "the plan" in your questions because the user cannot \
                see the plan in the UI until you call ExitPlanMode.
                """;
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
                                                "multiSelect", Map.of(
                                                        "type", "boolean",
                                                        "description", "Whether this question allows multiple selected options. " +
                                                                "True allows multiple answers; false or omission allows only one. " +
                                                                "Set true when the question says 可多选 or otherwise allows multiple answers."
                                                )
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
            return ToolResult.validationError("ELICITATION_QUESTION_COUNT_INVALID", "Must provide 1-4 questions.");
        }
        for (Map<String, Object> q : questions) {
            List<Map<String, String>> options = (List<Map<String, String>>) q.get("options");
            if (options == null || options.size() < 2 || options.size() > 4) {
                return ToolResult.validationError("ELICITATION_OPTION_COUNT_INVALID",
                        "Each question must have 2-4 options. Got: "
                                + (options == null ? 0 : options.size()));
            }
        }

        // 2. 通过 ElicitationService 逐个提问，复用前端已有的 ElicitationDialog
        Map<String, Object> allAnswers = new LinkedHashMap<>();
        for (int i = 0; i < questions.size(); i++) {
            Map<String, Object> q = questions.get(i);
            String questionText = (String) q.get("question");
            boolean multiSelect = Boolean.TRUE.equals(q.get("multiSelect"));
            List<Map<String, String>> rawOptions = (List<Map<String, String>>) q.get("options");

            // 转换为 ElicitationOption 格式
            List<ElicitationOption> elicitOptions = new ArrayList<>();
            for (Map<String, String> opt : rawOptions) {
                String label = opt.getOrDefault("label", "");
                String desc = opt.getOrDefault("description", "");
                elicitOptions.add(new ElicitationOption(label, label, desc));
            }

            log.info("AskUserQuestion: sending question {}/{}: '{}'", i + 1, questions.size(), questionText);

            ElicitationResponse response = elicitationService.requestAndWait(
                    context.sessionId(), context.currentRunId(), questionText, elicitOptions,
                    multiSelect, QUESTION_TIMEOUT_MS);

            switch (response.status()) {
                case SUCCESS -> allAnswers.put("q" + (i + 1), response.value());
                case CANCELLED -> {
                    return ToolResult.cancelled("ELICITATION_CANCELLED", "User cancelled the question.",
                            ToolResult.EffectState.NOT_STARTED);
                }
                case TIMEOUT -> {
                    return ToolResult.internalError("ELICITATION_EXPIRED",
                            "The client acknowledged receipt, but no answer was received before the decision deadline.", ToolResult.EffectState.NOT_STARTED);
                }
                case UNDELIVERABLE -> {
                    return ToolResult.internalError("ELICITATION_UNDELIVERABLE",
                            response.error(), ToolResult.EffectState.NOT_STARTED);
                }
                case ERROR -> {
                    return ToolResult.internalError("ELICITATION_FAILED",
                            "Error: " + response.error(), ToolResult.EffectState.NOT_STARTED);
                }
            }
        }

        // 3. 构建结果
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("questions", questions);
            result.put("answers", allAnswers);
            return ToolResult.success(MAPPER.writeValueAsString(result));
        } catch (Exception e) {
            return ToolResult.internalError("ELICITATION_RESULT_SERIALIZATION_FAILED",
                    "Error serializing result: " + e.getMessage(), ToolResult.EffectState.NONE);
        }
    }
}
