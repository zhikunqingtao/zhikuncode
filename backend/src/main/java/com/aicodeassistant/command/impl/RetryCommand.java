package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class RetryCommand implements PromptCommand {

    private final SessionManager sessionManager;

    public RetryCommand(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override public String getName() { return "retry"; }
    @Override public List<String> getAliases() { return List.of(); }
    @Override public String getDescription() { return "重试上一次失败的查询"; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        var sessionOpt = sessionManager.loadSession(context.sessionId());
        if (sessionOpt.isEmpty() || sessionOpt.get().messages().isEmpty()) {
            return CommandResult.error("当前会话无消息，无法重试");
        }
        var messages = sessionOpt.get().messages();
        String lastUserMessage = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof Message.UserMessage userMsg) {
                lastUserMessage = userMsg.content().stream()
                        .filter(b -> b instanceof ContentBlock.TextBlock)
                        .map(b -> ((ContentBlock.TextBlock) b).text())
                        .collect(Collectors.joining("\n"));
                if (!lastUserMessage.isBlank()) break;
            }
        }
        if (lastUserMessage == null || lastUserMessage.isBlank()) {
            return CommandResult.error("未找到用户消息，无法重试");
        }
        return CommandResult.text(lastUserMessage);
    }
}
