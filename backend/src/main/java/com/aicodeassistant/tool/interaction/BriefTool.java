package com.aicodeassistant.tool.interaction;

import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * BriefTool — 以 Markdown 展示当前执行上下文中的基础信息。
 * <p>
 * 支持三种 scope:
 * <ul>
 *   <li>project: 工作目录与会话 ID</li>
 *   <li>session: 当前会话 ID</li>
 *   <li>custom: 用户提供的主题与工作目录</li>
 * </ul>
 * <p>
 * 不采集 Git 状态、提交历史或会话历史，也不生成主题分析。
 *
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
        return "Return basic context in Markdown: working directory, session ID, or a supplied topic. " +
                "Does not collect Git or session history or generate topic analysis.";
    }

    @Override
    public String prompt() {
        return """
                Display basic execution context in Markdown format.
                
                ## Parameters
                 - `scope`: The context fields to display. One of:
                   - `project` (default): Working directory and session ID.
                   - `session`: Current session ID.
                   - `custom`: The supplied topic and working directory. Requires `topic` parameter.
                 - `topic`: Required when `scope=custom`. Topic text to display; no analysis is generated.
                
                ## Output
                Returns Markdown headings and the context values listed above. \
                Git status, commit history, and session history are not collected. \
                The result does not summarize tool calls or actions or generate topic analysis.
                
                ## Images
                To display images in your reply, use standard Markdown image syntax \
                `![description](path-or-url)` directly in your response text. \
                Do NOT use this tool to display images.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "scope", Map.of(
                                "type", "string",
                                "enum", List.of("project", "session", "custom"),
                                "description", "Context fields to display (default: project); no Git or session history is collected"),
                        "topic", Map.of(
                                "type", "string",
                                "description", "Topic text to display (required when scope=custom); no analysis is generated")
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

        log.info("Returning brief context: scope={}, topic={}", scope, topic);

        // 展示执行上下文与输入中已有的值。
        StringBuilder contextBuilder = new StringBuilder();
        switch (scope) {
            case "project" -> {
                contextBuilder.append("## Project Brief\n\n");
                contextBuilder.append("Working directory: ").append(context.workingDirectory()).append("\n");
                contextBuilder.append("Session: ").append(context.sessionId()).append("\n\n");
                contextBuilder.append("*Context only: Git status and commit history have not been collected.*\n");
            }
            case "session" -> {
                contextBuilder.append("## Session Brief\n\n");
                contextBuilder.append("Session: ").append(context.sessionId()).append("\n\n");
                contextBuilder.append("*Context only: session history, tool calls, and actions have not been collected.*\n");
            }
            case "custom" -> {
                if (topic.isBlank()) {
                    return ToolResult.validationError("BRIEF_TOPIC_REQUIRED", "'topic' is required for custom scope.");
                }
                contextBuilder.append("## Custom Brief: ").append(topic).append("\n\n");
                contextBuilder.append("Working directory: ").append(context.workingDirectory()).append("\n\n");
                contextBuilder.append("*Context only: the supplied topic is displayed without generated analysis.*\n");
            }
            default -> {
                return ToolResult.validationError("BRIEF_SCOPE_INVALID", "Unknown scope: " + scope + ". Use: project, session, or custom.");
            }
        }

        return ToolResult.success(contextBuilder.toString());
    }
}
