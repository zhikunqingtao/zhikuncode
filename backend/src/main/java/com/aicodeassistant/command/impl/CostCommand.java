package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.state.AppStateStore;
import com.aicodeassistant.state.CostState;
import org.springframework.stereotype.Component;

/**
 * /cost — 显示当前会话成本。
 *
 * @see <a href="SPEC §3.3.2">/cost 命令</a>
 */
@Component
public class CostCommand implements Command {

    private final AppStateStore appStateStore;

    public CostCommand(AppStateStore appStateStore) {
        this.appStateStore = appStateStore;
    }

    @Override public String getName() { return "cost"; }
    @Override public String getDescription() { return "Show session cost and token usage"; }
    @Override public CommandType getType() { return CommandType.LOCAL; }
    @Override public boolean supportsNonInteractive() { return true; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        CostState cost = appStateStore.getState().cost();
        var usage = cost.totalUsage();

        StringBuilder sb = new StringBuilder();
        sb.append("Session Cost Summary:\n\n");
        sb.append("  Total Cost:     $").append(String.format("%.4f", cost.totalCostUsd())).append("\n");
        sb.append("  Session Cost:   $").append(String.format("%.4f", cost.sessionCostUsd())).append("\n");
        sb.append("  Input Tokens:   ").append(usage.inputTokens()).append("\n");
        sb.append("  Output Tokens:  ").append(usage.outputTokens()).append("\n");
        sb.append("  Cache Read:     ").append(usage.cacheReadInputTokens()).append("\n");
        sb.append("  Cache Create:   ").append(usage.cacheCreationInputTokens()).append("\n");

        return CommandResult.text(sb.toString());
    }
}
