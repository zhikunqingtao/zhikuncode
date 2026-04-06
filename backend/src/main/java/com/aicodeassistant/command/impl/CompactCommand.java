package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.engine.CompactService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * /compact [custom_instruction] — 压缩当前对话上下文。
 * <p>
 * 调用 CompactService 执行三区划分 → LLM 摘要压缩。
 * 可选参数作为自定义摘要方向（如 "focus on API design decisions"）。
 *
 * @see <a href="SPEC §3.3.2">/compact 命令</a>
 */
@Component
public class CompactCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(CompactCommand.class);

    private final CompactService compactService;

    public CompactCommand(CompactService compactService) {
        this.compactService = compactService;
    }

    @Override public String getName() { return "compact"; }
    @Override public String getDescription() { return "Compact conversation context"; }
    @Override public CommandType getType() { return CommandType.LOCAL; }
    @Override public boolean supportsNonInteractive() { return true; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        try {
            var state = context.appState();
            var messages = state.session().messages();

            if (messages == null || messages.isEmpty()) {
                return CommandResult.text("Nothing to compact — conversation is empty.");
            }

            // 获取上下文窗口大小 (默认 200k)
            int contextWindow = 200_000;

            CompactService.CompactResult result = compactService.compact(
                    messages, contextWindow, false);

            String displayText = "Conversation compacted. " + result.summary();
            if (args != null && !args.isBlank()) {
                displayText += " (Instruction: " + args.trim() + ")";
            }

            return CommandResult.compact(displayText, Map.of(
                    "originalMessageCount", messages.size(),
                    "compactedMessageCount", result.compactedMessages().size(),
                    "savedTokens", result.savedTokens()
            ));
        } catch (Exception e) {
            log.error("Compact failed: {}", e.getMessage(), e);
            return CommandResult.error("Failed to compact conversation: " + e.getMessage());
        }
    }
}
