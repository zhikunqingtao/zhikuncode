package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.engine.CompactService;
import com.aicodeassistant.engine.CompactService.CompactResult;
import com.aicodeassistant.engine.TokenCounter;
import com.aicodeassistant.llm.ModelRegistry;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * /compact [custom_instruction] — 手动触发上下文压缩。
 * <p>
 * 通过 SessionManager 获取会话消息（解耦对 AppState 的依赖）。
 * 调用 CompactService 执行三区划分 → LLM 摘要压缩。
 * 可选参数作为自定义摘要方向（如 "focus on API design decisions"）。
 *
 * @see <a href="SPEC §3.3.2">/compact 命令</a>
 */
@Component
public class CompactCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(CompactCommand.class);

    private final CompactService compactService;
    private final TokenCounter tokenCounter;
    private final ModelRegistry modelRegistry;
    private final SessionManager sessionManager;

    public CompactCommand(CompactService compactService,
                          TokenCounter tokenCounter,
                          ModelRegistry modelRegistry,
                          SessionManager sessionManager) {
        this.compactService = compactService;
        this.tokenCounter = tokenCounter;
        this.modelRegistry = modelRegistry;
        this.sessionManager = sessionManager;
    }

    @Override public String getName() { return "compact"; }
    @Override public String getDescription() { return "Compact conversation context"; }
    @Override public CommandType getType() { return CommandType.LOCAL; }
    @Override public boolean supportsNonInteractive() { return true; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        try {
            // 通过 SessionManager 获取会话消息（解耦对 AppState 的依赖，避免 NPE）
            var sessionData = sessionManager.loadSession(context.sessionId());
            if (sessionData.isEmpty()) {
                return CommandResult.text("Session not found: " + context.sessionId());
            }
            List<Message> messages = sessionData.get().messages();

            if (messages == null || messages.isEmpty()) {
                return CommandResult.text("Nothing to compact — conversation is empty.");
            }

            // 从 ModelRegistry 动态获取当前模型的上下文窗口大小（替换硬编码 200000）
            String model = context.currentModel() != null ? context.currentModel() : "default";
            int contextWindow = modelRegistry.getContextWindowForModel(model);

            int beforeTokens = tokenCounter.estimateTokens(messages);
            CompactResult result = compactService.compact(messages, contextWindow, false);

            if (result.skipReason() != null) {
                return CommandResult.text("Compact skipped: " + result.skipReason());
            }

            String displayText = "Conversation compacted. " + result.summary();
            if (args != null && !args.isBlank()) {
                displayText += " (Instruction: " + args.trim() + ")";
            }

            // 增强元数据供前端可视化渲染
            return CommandResult.compact(displayText, Map.of(
                "originalMessageCount", messages.size(),
                "compactedMessageCount", result.compactedMessages().size(),
                "beforeTokens", beforeTokens,
                "afterTokens", result.afterTokens(),
                "savedTokens", result.savedTokens(),
                "compressionRatio", result.compressionRatio(),
                "instruction", args != null ? args.trim() : ""
            ));
        } catch (Exception e) {
            log.error("Compact failed: {}", e.getMessage(), e);
            return CommandResult.error("Failed to compact conversation: " + e.getMessage());
        }
    }
}
