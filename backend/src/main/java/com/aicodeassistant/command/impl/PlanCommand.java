package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.websocket.WebSocketController;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * /plan 命令 — 显示或隐藏前端计划面板，不改变会话权限模式。
 * 用法:
 *   /plan on [planName]  — 显示计划面板
 *   /plan off            — 隐藏计划面板
 *   /plan [planName]     — 显示计划面板
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
    public String getDescription() { return "Show or hide the planning UI panel; session permissions are unchanged"; }

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
            return CommandResult.text("Planning panel opened: " + planName + ". Session permissions are unchanged.");
        } else if (trimmed.equals("off")) {
            wsController.sendPlanUpdate(sessionId, Map.of("isPlanMode", false));
            return CommandResult.text("Planning panel closed. Session permissions are unchanged.");
        } else {
            // 默认显示面板；既有 UI 事件不切换权限模式。
            wsController.sendPlanUpdate(sessionId, Map.of(
                    "isPlanMode", true,
                    "planName", trimmed.isEmpty() ? "New Plan" : trimmed,
                    "planOverview", ""
            ));
            return CommandResult.text("Planning panel opened. Session permissions are unchanged.");
        }
    }
}
