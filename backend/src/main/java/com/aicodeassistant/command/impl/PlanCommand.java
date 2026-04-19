package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.websocket.WebSocketController;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * /plan 命令 — 切换 Plan Mode 规划模式。
 * 用法:
 *   /plan on [planName]  — 进入规划模式
 *   /plan off            — 退出规划模式
 *   /plan                — 切换当前模式
 */
@Component
public class PlanCommand implements Command {

    private final WebSocketController wsController;

    public PlanCommand(@Lazy WebSocketController wsController) {
        this.wsController = wsController;
    }

    @Override
    public String getName() { return "plan"; }

    @Override
    public String getDescription() { return "Toggle Plan Mode for step-by-step task planning"; }

    @Override
    public CommandType getType() { return CommandType.LOCAL; }

    @Override
    public CommandResult execute(String args, CommandContext ctx) {
        String sessionId = ctx.sessionId();
        String trimmed = (args != null) ? args.trim() : "";

        if (trimmed.startsWith("on")) {
            String planName = trimmed.length() > 2 ? trimmed.substring(3).trim() : "New Plan";
            wsController.sendPlanUpdate(sessionId, Map.of(
                    "isPlanMode", true,
                    "planName", planName,
                    "planOverview", ""
            ));
            return CommandResult.text("Plan Mode enabled: " + planName);
        } else if (trimmed.equals("off")) {
            wsController.sendPlanUpdate(sessionId, Map.of("isPlanMode", false));
            return CommandResult.text("Plan Mode disabled");
        } else {
            // toggle
            wsController.sendPlanUpdate(sessionId, Map.of(
                    "isPlanMode", true,
                    "planName", trimmed.isEmpty() ? "New Plan" : trimmed,
                    "planOverview", ""
            ));
            return CommandResult.text("Plan Mode toggled");
        }
    }
}
