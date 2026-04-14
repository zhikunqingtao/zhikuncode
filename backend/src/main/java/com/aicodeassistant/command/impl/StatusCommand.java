package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.session.SessionManager;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StatusCommand implements Command {

    private final SessionManager sessionManager;

    public StatusCommand(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override public String getName() { return "status"; }
    @Override public List<String> getAliases() { return List.of("info"); }
    @Override public String getDescription() { return "显示当前会话和系统状态"; }
    @Override public CommandType getType() { return CommandType.LOCAL; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        var sessionOpt = sessionManager.loadSession(context.sessionId());
        StringBuilder sb = new StringBuilder("## 系统状态\n\n");
        sb.append("- 会话 ID: ").append(context.sessionId()).append("\n");
        if (sessionOpt.isPresent()) {
            var session = sessionOpt.get();
            sb.append("- 消息数: ").append(session.messages().size()).append("\n");
            sb.append("- Token 用量: ").append(session.totalUsage()).append("\n");
            sb.append("- 工作目录: ").append(session.workingDir()).append("\n");
            sb.append("- 当前模型: ").append(session.model()).append("\n");
        } else {
            sb.append("- 会话状态: 未找到\n");
        }
        return CommandResult.text(sb.toString());
    }
}
