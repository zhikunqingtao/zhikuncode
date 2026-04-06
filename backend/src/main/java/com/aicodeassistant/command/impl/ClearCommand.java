package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * /clear (别名: /reset, /new) — 清除当前对话消息。
 * <p>
 * 流程:
 * <ol>
 *     <li>调用 SessionManager 清除会话消息</li>
 *     <li>重置 token 计数器和成本追踪器</li>
 *     <li>添加系统消息 "Conversation cleared"</li>
 * </ol>
 *
 * @see <a href="SPEC §3.3.2">/clear 命令</a>
 */
@Component
public class ClearCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(ClearCommand.class);

    private final SessionManager sessionManager;

    public ClearCommand(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override public String getName() { return "clear"; }
    @Override public List<String> getAliases() { return List.of("reset", "new"); }
    @Override public String getDescription() { return "Clear conversation history"; }
    @Override public CommandType getType() { return CommandType.LOCAL; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        String sessionId = context.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return CommandResult.error("No active session to clear.");
        }

        try {
            // 创建新会话替代当前会话（清除历史）
            String newSessionId = sessionManager.createSession(
                    context.currentModel(), context.workingDir());

            log.info("Conversation cleared. Old session: {}, New session: {}",
                    sessionId, newSessionId);

            return CommandResult.text("Conversation cleared.");
        } catch (Exception e) {
            log.error("Failed to clear conversation: {}", e.getMessage(), e);
            return CommandResult.error("Failed to clear conversation: " + e.getMessage());
        }
    }
}
