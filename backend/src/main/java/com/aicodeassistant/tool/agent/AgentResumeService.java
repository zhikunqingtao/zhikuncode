package com.aicodeassistant.tool.agent;

import com.aicodeassistant.engine.*;
import com.aicodeassistant.llm.ThinkingConfig;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.prompt.EffectiveSystemPromptBuilder;
import com.aicodeassistant.prompt.SystemPromptConfig;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolRegistry;
import com.aicodeassistant.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 恢复服务 — 对齐原版 resumeAgent.ts。
 * 从快照恢复子代理执行。
 */
@Service
public class AgentResumeService {

    private static final Logger log = LoggerFactory.getLogger(AgentResumeService.class);

    private final AgentMemorySnapshot memorySnapshot;
    private final QueryEngine queryEngine;
    private final ToolRegistry toolRegistry;
    private final EffectiveSystemPromptBuilder systemPromptBuilder;

    public AgentResumeService(
            AgentMemorySnapshot memorySnapshot,
            QueryEngine queryEngine,
            ToolRegistry toolRegistry,
            EffectiveSystemPromptBuilder systemPromptBuilder) {
        this.memorySnapshot = memorySnapshot;
        this.queryEngine = queryEngine;
        this.toolRegistry = toolRegistry;
        this.systemPromptBuilder = systemPromptBuilder;
    }

    public QueryEngine.QueryResult resume(String agentId, String additionalContext)
            throws IOException {
        AgentMemorySnapshot.Snapshot snapshot = memorySnapshot.load(agentId);
        if (snapshot == null) {
            throw new IllegalArgumentException("No snapshot found for agent: " + agentId);
        }

        log.info("Resuming agent: id={}, task={}, messages={}, nestingDepth={}",
            agentId, snapshot.taskDescription(),
            snapshot.messages().size(), snapshot.nestingDepth());

        List<Message> messages = new ArrayList<>(snapshot.messages());

        String resumeText = "[Resumed] Continuing previous task: " + snapshot.taskDescription();
        if (additionalContext != null && !additionalContext.isBlank()) {
            resumeText += "\n\nAdditional context: " + additionalContext;
        }
        messages.add(new Message.UserMessage(
            UUID.randomUUID().toString(), Instant.now(),
            List.of(new ContentBlock.TextBlock(resumeText)),
            null, null));

        List<Tool> tools = toolRegistry.getSubAgentTools();
        List<Map<String, Object>> toolDefs = tools.stream()
            .map(Tool::toToolDefinition).toList();

        String model = snapshot.model() != null ? snapshot.model() : "default";
        String sessionId = snapshot.parentSessionId();
        String systemPrompt = systemPromptBuilder.buildEffectiveSystemPrompt(
            SystemPromptConfig.defaults().withSessionId(sessionId), tools, model,
            Path.of(snapshot.workingDirectory() != null
                ? snapshot.workingDirectory()
                : System.getProperty("user.dir")));

        QueryConfig config = QueryConfig.withDefaults(
            model, systemPrompt, tools, toolDefs,
            QueryConfig.DEFAULT_MAX_TOKENS, 200000,
            new ThinkingConfig.Disabled(),
            30,
            "resumed-agent-" + agentId
        );

        ToolUseContext toolContext = ToolUseContext.of(
            snapshot.workingDirectory() != null
                ? snapshot.workingDirectory()
                : System.getProperty("user.dir"),
            snapshot.parentSessionId()
        ).withNestingDepth(snapshot.nestingDepth());

        QueryLoopState state = new QueryLoopState(messages, toolContext);

        QueryEngine.QueryResult result = queryEngine.execute(
            config, state, new ResumeAgentMessageHandler());

        memorySnapshot.delete(agentId);
        log.info("Agent resumed and completed: id={}, stopReason={}",
            agentId, result.stopReason());

        return result;
    }

    public boolean hasResumableAgent(String agentId) {
        try {
            return memorySnapshot.load(agentId) != null;
        } catch (IOException e) {
            return false;
        }
    }

    public List<String> listResumableAgents() throws IOException {
        return memorySnapshot.listSnapshots();
    }

    private static class ResumeAgentMessageHandler implements QueryMessageHandler {
        @Override public void onTextDelta(String text) { }
        @Override public void onToolUseStart(String toolUseId, String toolName) { }
        @Override public void onToolUseComplete(String toolUseId, ContentBlock.ToolUseBlock toolUse) { }
        @Override public void onToolResult(String toolUseId, ContentBlock.ToolResultBlock result) { }
        @Override public void onAssistantMessage(Message.AssistantMessage message) { }
        @Override public void onError(Throwable error) {
            LoggerFactory.getLogger(ResumeAgentMessageHandler.class)
                .warn("Resumed agent error: {}", error.getMessage());
        }
    }
}
