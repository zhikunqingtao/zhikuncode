package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.model.PermissionRule;
import com.aicodeassistant.state.AppStateStore;
import com.aicodeassistant.state.PermissionState;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * /permissions (别名: /allowed-tools) — 管理 allow/deny 工具权限规则。
 *
 * @see <a href="SPEC §3.3.4a.7">/permissions 命令</a>
 */
@Component
public class PermissionsCommand implements Command {

    private final AppStateStore appStateStore;

    public PermissionsCommand(AppStateStore appStateStore) {
        this.appStateStore = appStateStore;
    }

    @Override public String getName() { return "permissions"; }
    @Override public List<String> getAliases() { return List.of("allowed-tools"); }
    @Override public String getDescription() { return "View and manage tool permissions"; }
    @Override public CommandType getType() { return CommandType.LOCAL_JSX; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        PermissionState permissions = appStateStore.getState().permissions();

        StringBuilder sb = new StringBuilder();
        sb.append("Permission Settings:\n\n");
        sb.append("  Mode: ").append(permissions.permissionMode()).append("\n");
        sb.append("  Bypass: ").append(permissions.isBypassPermissions()).append("\n\n");

        // Allow rules
        sb.append("  Always Allow Rules:\n");
        Map<String, List<PermissionRule>> allowRules = permissions.alwaysAllowRules();
        if (allowRules.isEmpty()) {
            sb.append("    (none)\n");
        } else {
            for (var entry : allowRules.entrySet()) {
                for (PermissionRule rule : entry.getValue()) {
                    sb.append("    ").append(entry.getKey())
                            .append(" → ").append(rule.ruleValue().toolName()).append("\n");
                }
            }
        }

        // Deny rules
        sb.append("\n  Always Deny Rules:\n");
        Map<String, List<PermissionRule>> denyRules = permissions.alwaysDenyRules();
        if (denyRules.isEmpty()) {
            sb.append("    (none)\n");
        } else {
            for (var entry : denyRules.entrySet()) {
                for (PermissionRule rule : entry.getValue()) {
                    sb.append("    ").append(entry.getKey())
                            .append(" → ").append(rule.ruleValue().toolName()).append("\n");
                }
            }
        }

        return CommandResult.text(sb.toString());
    }
}
