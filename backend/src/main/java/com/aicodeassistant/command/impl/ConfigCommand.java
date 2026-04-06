package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.state.AppStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * /config [key] [value] — 查看/设置配置。
 * <p>
 * 流程:
 * <ol>
 *     <li>无参数 → 显示所有配置项</li>
 *     <li>仅 key → 显示该配置值</li>
 *     <li>key+value → 设置配置</li>
 *     <li>受保护配置 (如 apiKey) → 仅显示 "***" 掩码</li>
 * </ol>
 *
 * @see <a href="SPEC §3.3.2">/config 命令</a>
 */
@Component
public class ConfigCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(ConfigCommand.class);

    private static final List<String> SENSITIVE_KEYS = List.of("apiKey", "llm.apiKey");

    private final AppStateStore appStateStore;

    public ConfigCommand(AppStateStore appStateStore) {
        this.appStateStore = appStateStore;
    }

    @Override public String getName() { return "config"; }
    @Override public List<String> getAliases() { return List.of("settings"); }
    @Override public String getDescription() { return "View or set configuration"; }
    @Override public CommandType getType() { return CommandType.LOCAL_JSX; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        if (args == null || args.isBlank()) {
            return showAllConfig(context);
        }

        String[] parts = args.trim().split("\\s+", 2);
        String key = parts[0];

        if (parts.length == 1) {
            return showConfigValue(key, context);
        }

        String value = parts[1];
        return setConfigValue(key, value, context);
    }

    private CommandResult showAllConfig(CommandContext context) {
        var state = context.appState();
        StringBuilder sb = new StringBuilder("Current Configuration:\n\n");

        sb.append("  model:         ").append(state.session().currentModel()).append("\n");
        sb.append("  workingDir:    ").append(state.session().workingDirectory()).append("\n");
        sb.append("  permissionMode: ").append(state.permissions().permissionMode()).append("\n");
        sb.append("  autoCompact:   ").append("true").append("\n");
        sb.append("  theme:         ").append(state.ui().theme()).append("\n");

        sb.append("\nUse /config <key> <value> to update a setting.");
        return CommandResult.text(sb.toString());
    }

    private CommandResult showConfigValue(String key, CommandContext context) {
        if (SENSITIVE_KEYS.contains(key)) {
            return CommandResult.text(key + " = ***");
        }

        var state = context.appState();
        String value = switch (key) {
            case "model" -> state.session().currentModel();
            case "workingDir" -> state.session().workingDirectory();
            case "permissionMode" -> String.valueOf(state.permissions().permissionMode());
            case "theme" -> String.valueOf(state.ui().theme());
            default -> null;
        };

        if (value == null) {
            return CommandResult.error("Unknown config key: " + key);
        }

        return CommandResult.text(key + " = " + value);
    }

    private CommandResult setConfigValue(String key, String value, CommandContext context) {
        log.info("Setting config: {} = {}", key,
                SENSITIVE_KEYS.contains(key) ? "***" : value);

        return CommandResult.jsx(Map.of(
                "action", "setConfig",
                "key", key,
                "value", value
        ));
    }
}
