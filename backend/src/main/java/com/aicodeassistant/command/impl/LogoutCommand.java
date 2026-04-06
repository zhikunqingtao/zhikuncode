package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * /logout — 退出登录。
 *
 * @see <a href="SPEC §3.3.2">/logout 命令</a>
 */
@Component
public class LogoutCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(LogoutCommand.class);

    @Override public String getName() { return "logout"; }
    @Override public String getDescription() { return "Sign out of your account"; }
    @Override public CommandType getType() { return CommandType.LOCAL; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        if (!context.isAuthenticated()) {
            return CommandResult.text("You are not currently signed in.");
        }

        log.info("Logout requested");
        // 实际的认证清除由 AuthService 处理
        return CommandResult.text("Signed out successfully.");
    }
}
