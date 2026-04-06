package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.state.AppStateStore;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * /session — 显示会话信息。
 *
 * @see <a href="SPEC §3.3.2">/session 命令</a>
 */
@Component
public class SessionCommand implements Command {

    private final AppStateStore appStateStore;

    public SessionCommand(AppStateStore appStateStore) {
        this.appStateStore = appStateStore;
    }

    @Override public String getName() { return "session"; }
    @Override public String getDescription() { return "Show session information"; }
    @Override public CommandType getType() { return CommandType.LOCAL_JSX; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        var state = appStateStore.getState();

        StringBuilder sb = new StringBuilder();
        sb.append("Session Information:\n\n");
        sb.append("  ID:        ").append(context.sessionId()).append("\n");
        sb.append("  Model:     ").append(context.currentModel()).append("\n");
        sb.append("  Directory: ").append(context.workingDir()).append("\n");
        sb.append("  Turns:     ").append(state.session().turnCount()).append("\n");
        sb.append("  Messages:  ").append(state.session().messages().size()).append("\n");

        return CommandResult.text(sb.toString());
    }
}
