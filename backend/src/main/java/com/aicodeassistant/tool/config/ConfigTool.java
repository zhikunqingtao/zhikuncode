package com.aicodeassistant.tool.config;

import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ConfigTool — 读取和修改工具自身的运行时存储，不应用到其他组件。
 * <p>
 * 支持三种操作:
 * <ul>
 *   <li>get: 读取单个配置项</li>
 *   <li>set: 设置配置项（"default" 值重置为默认）</li>
 *   <li>list: 列出所有支持的配置项</li>
 * </ul>
 *
 */
@Component
public class ConfigTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ConfigTool.class);

    /** 支持的配置项及默认值 */
    private static final Map<String, Object> DEFAULTS = Map.of(
            "theme", "system",
            "model", "standard",
            "maxTokens", 8192,
            "autoCompact", true,
            "verboseLogging", false,
            "maxTurns", 100,
            "language", "auto"
    );

    /** 配置项可选值（null 表示无限制） */
    private static final Map<String, List<String>> OPTIONS = Map.of(
            "theme", List.of("system", "light", "dark"),
            "model", List.of("light", "standard", "premium"),
            "language", List.of("auto", "en", "zh", "ja", "ko", "fr", "de", "es")
    );

    /** 运行时配置存储 */
    private final ConcurrentMap<String, Object> store = new ConcurrentHashMap<>(DEFAULTS);

    private final LlmProviderRegistry providerRegistry;

    public ConfigTool(LlmProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    /** 动态获取模型选项列表（内置别名 + 实际可用模型） */
    private List<String> getModelOptions() {
        List<String> options = new ArrayList<>(providerRegistry.getBuiltinAliases());
        try {
            providerRegistry.listAvailableModels().stream()
                    .filter(m -> !options.contains(m))
                    .forEach(options::add);
        } catch (Exception e) {
            log.debug("Failed to fetch available models for config options: {}", e.getMessage());
        }
        return options;
    }

    @Override
    public String getName() {
        return "Config";
    }

    @Override
    public String getDescription() {
        return "Read and modify this tool's runtime store with get, set, and list actions; " +
                "values are not applied to application settings.";
    }

    @Override
    public String prompt() {
        return """
                Read or update values in this tool's runtime store, not the application's active settings.
                
                Note: changes apply only to this tool's runtime store; they are not persisted \
                and other components do not consume them. In particular, the `model` key \
                does not switch the session model — use the model selector.

                ## Usage
                - **Get stored value:** use action "get" with the key
                - **Set stored value:** use action "set" with the key and the new value
                - **List stored values:** use action "list"
                
                ## Stored keys (none apply changes to other components)
                - theme: "system", "light", "dark"
                - model: built-in aliases such as "light", "standard", "premium", or an available model ID
                - maxTokens: integer
                - autoCompact: true/false
                - verboseLogging: true/false
                - maxTurns: integer
                - language: "auto", "en", "zh", etc.
                
                ## Examples
                - Read stored theme: { "action": "get", "key": "theme" }
                - Store "dark" without changing the UI theme: { "action": "set", "key": "theme", "value": "dark" }
                - Store "en" without changing the UI language: { "action": "set", "key": "language", "value": "en" }
                """;
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
                StringBuilder sb = new StringBuilder(
                        "Stored values (runtime store only; not applied to other components):\n");
                store.forEach((k, v) ->
                        sb.append(String.format("  %s = %s%n", k, v)));
                yield ToolResult.success(sb.toString());
            }
            case "get" -> {
                String key = input.getString("key");
                if (!DEFAULTS.containsKey(key)) {
                    yield ToolResult.validationError("CONFIG_SETTING_UNKNOWN", "Unknown setting: " + key);
                }
                Object value = store.getOrDefault(key, DEFAULTS.get(key));
                yield ToolResult.success(
                        String.format("Setting '%s' = %s (runtime store only; not applied to other components)",
                                key, value));
            }
            case "set" -> {
                String key = input.getString("key");
                String value = input.getString("value");

                if (!DEFAULTS.containsKey(key)) {
                    yield ToolResult.validationError("CONFIG_SETTING_UNKNOWN", "Unknown setting: " + key);
                }

                // "default" → 重置
                if ("default".equals(value)) {
                    Object defaultVal = DEFAULTS.get(key);
                    store.put(key, defaultVal);
                    yield ToolResult.success(
                            "Setting '" + key + "' reset to default: " + defaultVal
                                    + " (runtime store only; not applied to other components)");
                }

                // 选项验证（模型选项动态获取）
                List<String> options = "model".equals(key)
                        ? getModelOptions()
                        : OPTIONS.get(key);
                if (options != null && !options.contains(value)) {
                    yield ToolResult.validationError("CONFIG_VALUE_INVALID",
                            "Invalid value for '" + key + "'. Options: " + options);
                }

                // 类型强制
                Object typedValue = coerceType(key, value);
                Object previousValue = store.get(key);
                store.put(key, typedValue);

                log.info("Config updated: {} = {} → {}", key, previousValue, typedValue);
                yield ToolResult.success(String.format(
                        "Setting '%s' updated: %s → %s (runtime store only; not applied to other components)",
                        key, previousValue, typedValue));
            }
            default -> ToolResult.validationError("CONFIG_ACTION_INVALID",
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
