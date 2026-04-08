package com.aicodeassistant.tool.interaction;

import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * BriefTool — 生成项目状态简报。
 * <p>
 * 支持三种 scope:
 * <ul>
 *   <li>project: Git status + 最近提交</li>
 *   <li>session: 当前会话工具调用摘要</li>
 *   <li>custom: 自定义主题</li>
 * </ul>
 * <p>
 * P1 占位实现: LLM 调用将在 LlmClient 集成后完善。
 *
 * @see <a href="SPEC §4.1.13">BriefTool</a>
 */
@Component
public class BriefTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BriefTool.class);

    @Override
    public String getName() {
        return "Brief";
    }

    @Override
    public String getDescription() {
        return "Generate a project status brief in Markdown format. " +
                "Supports project, session, and custom scopes.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "scope", Map.of(
                                "type", "string",
                                "enum", List.of("project", "session", "custom"),
                                "description", "Scope of the brief (default: project)"),
                        "topic", Map.of(
                                "type", "string",
                                "description", "Custom topic (required when scope=custom)")
                )
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
    public boolean isConcurrencySafe(ToolInput input) {
        return true;
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String scope = input.getString("scope", "project");
        String topic = input.getString("topic", "");

        log.info("Generating brief: scope={}, topic={}", scope, topic);

        // 1. 收集上下文
        StringBuilder contextBuilder = new StringBuilder();
        switch (scope) {
            case "project" -> {
                contextBuilder.append("## Project Brief\n\n");
                contextBuilder.append("Working directory: ").append(context.workingDirectory()).append("\n");
                contextBuilder.append("Session: ").append(context.sessionId()).append("\n\n");
                contextBuilder.append("*Git status and recent changes will be available after GitService integration.*\n");
            }
            case "session" -> {
                contextBuilder.append("## Session Brief\n\n");
                contextBuilder.append("Session: ").append(context.sessionId()).append("\n\n");
                contextBuilder.append("*Session summary will be available after SessionService integration.*\n");
            }
            case "custom" -> {
                if (topic.isBlank()) {
                    return ToolResult.error("'topic' is required for custom scope.");
                }
                contextBuilder.append("## Custom Brief: ").append(topic).append("\n\n");
                contextBuilder.append("Working directory: ").append(context.workingDirectory()).append("\n\n");
                contextBuilder.append("*Detailed analysis will be available after LlmClient integration.*\n");
            }
            default -> {
                return ToolResult.error("Unknown scope: " + scope + ". Use: project, session, or custom.");
            }
        }

        // P1 占位: LLM 调用将在 LlmClient 集成后完善
        // String briefContent = llmClient.generateWithFastModel(prompt);

        return ToolResult.success(contextBuilder.toString());
    }
}
