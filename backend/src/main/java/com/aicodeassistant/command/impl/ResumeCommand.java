package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.model.SessionSummary;
import com.aicodeassistant.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * /resume [session_id] — 恢复历史会话。
 * <p>
 * 无参数 → 显示最近会话列表；有参数 → 直接加载指定会话。
 *
 * @see <a href="SPEC §3.3.2">/resume 命令</a>
 */
@Component
public class ResumeCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(ResumeCommand.class);

    private final SessionManager sessionManager;

    public ResumeCommand(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override public String getName() { return "resume"; }
    @Override public List<String> getAliases() { return List.of("continue"); }
    @Override public String getDescription() { return "Resume a previous session"; }
    @Override public CommandType getType() { return CommandType.LOCAL_JSX; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        if (args != null && !args.isBlank()) {
            // 直接恢复指定会话
            String sessionId = args.trim();
            var loaded = sessionManager.resumeSession(sessionId);
            if (loaded.isPresent()) {
                log.info("Session resumed: {}", sessionId);
                return CommandResult.text("Session resumed: " + sessionId);
            } else {
                return CommandResult.error("Session not found: " + sessionId);
            }
        }

        // 显示最近会话列表
        List<SessionSummary> sessions = sessionManager.listSessions(20);
        if (sessions.isEmpty()) {
            return CommandResult.text("No previous sessions found.");
        }

        StringBuilder sb = new StringBuilder("Recent Sessions:\n\n");
        for (SessionSummary session : sessions) {
            sb.append("  ").append(session.id().substring(0, 8)).append("  ");
            sb.append(String.format("%-30s", session.title() != null ? session.title() : "(untitled)"));
            sb.append("  ").append(session.messageCount()).append(" msgs");
            sb.append("  ").append(session.updatedAt()).append("\n");
        }

        sb.append("\nUsage: /resume <session_id>");
        return CommandResult.text(sb.toString());
    }
}
