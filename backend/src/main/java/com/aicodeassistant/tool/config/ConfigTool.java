package com.aicodeassistant.tool.config;

import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ConfigTool — 读取和修改运行时配置。
 * <p>
 * 支持三种操作:
 * <ul>
 *   <li>get: 读取单个配置项</li>
 *   <li>set: 设置配置项（"default" 值重置为默认）</li>
 *   <li>list: 列出所有支持的配置项</li>
 * </ul>
 *
 * @see <a href="SPEC §4.1.11">ConfigTool</a>
 */
@Component
public class ConfigTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ConfigTool.class);

    /** 支持的配置项及默认值 */
    private static final Map<String, Object> DEFAULTS = Map.of(
            "theme", "system",
            "model", "sonnet",
            "maxTokens", 8192,
            "autoCompact", true,
            "verboseLogging", false,
            "maxTurns", 100,
            "language", "auto"
    );

    /** 配置项可选值（null 表示无限制） */
    private static final Map<String, List<String>> OPTIONS = Map.of(
            "theme", List.of("system", "light", "dark"),
            "model", List.of("sonnet", "opus", "haiku"),
            "language", List.of("auto", "en", "zh", "ja", "ko", "fr", "de", "es")
    );

    /** 运行时配置存储 */
    private final ConcurrentMap<String, Object> store = new ConcurrentHashMap<>(DEFAULTS);

    @Override
    public String getName() {
        return "Config";
    }

    @Override
    public String getDescription() {
        return "Read and modify runtime configuration settings. " +
                "Supports get, set, and list actions.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of(
                                "type", "string",
                                "enum", List.of("get", "set", "list"),
                                "description", "Action to perform (default: get)"),
                        "key", Map.of(
                                "type", "string",
                                "description", "Configuration key (required for get/set)"),
                        "value", Map.of(
                                "type", "string",
                                "description", "Value to set (required for set action)")
                )
        );
    }

    @Override
    public String getGroup() {
        return "config";
    }

    @Override
    public boolean shouldDefer() {
        return true;
    }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.NONE;
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        String action = input.getString("action", "get");
        return !"set".equals(action);
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        String action = input.getString("action", "get");
        return !"set".equals(action);
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String action = input.getString("action", "get");

        return switch (action) {
            case "list" -> {
                StringBuilder sb = new StringBuilder("Available settings:\n");
                store.forEach((k, v) ->
                        sb.append(String.format("  %s = %s%n", k, v)));
                yield ToolResult.success(sb.toString());
            }
            case "get" -> {
                String key = input.getString("key");
                if (!DEFAULTS.containsKey(key)) {
                    yield ToolResult.error("Unknown setting: " + key);
                }
                Object value = store.getOrDefault(key, DEFAULTS.get(key));
                yield ToolResult.success(
                        String.format("Setting '%s' = %s", key, value));
            }
            case "set" -> {
                String key = input.getString("key");
                String value = input.getString("value");

                if (!DEFAULTS.containsKey(key)) {
                    yield ToolResult.error("Unknown setting: " + key);
                }

                // "default" → 重置
                if ("default".equals(value)) {
                    Object defaultVal = DEFAULTS.get(key);
                    store.put(key, defaultVal);
                    yield ToolResult.success(
                            "Setting '" + key + "' reset to default: " + defaultVal);
                }

                // 选项验证
                List<String> options = OPTIONS.get(key);
                if (options != null && !options.contains(value)) {
                    yield ToolResult.error(
                            "Invalid value for '" + key + "'. Options: " + options);
                }

                // 类型强制
                Object typedValue = coerceType(key, value);
                Object previousValue = store.get(key);
                store.put(key, typedValue);

                log.info("Config updated: {} = {} → {}", key, previousValue, typedValue);
                yield ToolResult.success(String.format(
                        "Setting '%s' updated: %s → %s", key, previousValue, typedValue));
            }
            default -> ToolResult.error(
                    "Unknown action: " + action + ". Expected: get, set, list.");
        };
    }

    /** 类型强制转换 */
    private Object coerceType(String key, String value) {
        Object defaultVal = DEFAULTS.get(key);
        if (defaultVal instanceof Boolean) {
            return Boolean.parseBoolean(value);
        }
        if (defaultVal instanceof Integer) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return value;
            }
        }
        return value;
    }

    /** 获取当前配置值（测试用） */
    Object getValue(String key) {
        return store.get(key);
    }
}
