package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * /login — OAuth 登录或 API Key 输入。
 *
 * @see <a href="SPEC §3.3.2">/login 命令</a>
 */
@Component
public class LoginCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(LoginCommand.class);

    @Override public String getName() { return "login"; }
    @Override public String getDescription() { return "Sign in to your account"; }
    @Override public CommandType getType() { return CommandType.LOCAL_JSX; }
    @Override public boolean isSensitive() { return true; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        if (context.isAuthenticated()) {
            return CommandResult.jsx(Map.of(
                    "action", "switchAccount",
                    "currentlyAuthenticated", true
            ));
        }

        log.info("Login requested — showing API key input");
        return CommandResult.jsx(Map.of(
                "action", "login",
                "currentlyAuthenticated", false
        ));
    }
}
