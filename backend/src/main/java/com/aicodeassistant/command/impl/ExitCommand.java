package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * /exit — 退出应用。
 *
 * @see <a href="SPEC §3.3.2">/exit 命令</a>
 */
@Component
public class ExitCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(ExitCommand.class);

    @Override public String getName() { return "exit"; }
    @Override public String getDescription() { return "Exit the application"; }
    @Override public CommandType getType() { return CommandType.LOCAL; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        log.info("Exit requested for session: {}", context.sessionId());
        // Web 场景: 关闭 STOMP 连接，由前端处理重定向
        return CommandResult.text("Goodbye!");
    }
}
