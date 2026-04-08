package com.aicodeassistant.plugin;

/**
 * 插件错误 — 记录插件加载/运行时的错误信息。
 * <p>
 * 错误类型包含 20+ 种场景（路径/网络/解析/验证/MCP/LSP 等）。
 *
 * @param pluginName 插件名称
 * @param errorType  错误类型
 * @param message    错误描述
 * @param cause      原始异常（可选）
 * @see <a href="SPEC §4.6.6.2">插件错误类型系统</a>
 */
public record PluginError(
        String pluginName,
        PluginErrorType errorType,
        String message,
        Throwable cause
) {

    /**
     * 插件错误类型枚举。
     */
    public enum PluginErrorType {
        PATH_NOT_FOUND,
        MANIFEST_PARSE_ERROR,
        MANIFEST_VALIDATION_ERROR,
        PLUGIN_NOT_FOUND,
        API_VERSION_INCOMPATIBLE,
        HOOK_LOAD_FAILED,
        COMPONENT_LOAD_FAILED,
        DEPENDENCY_UNSATISFIED,
        MCP_CONFIG_INVALID,
        LSP_CONFIG_INVALID,
        CLASS_LOAD_FAILED,
        GENERIC_ERROR
    }

    /** 简化构造 — 无原始异常 */
    public static PluginError of(String pluginName, PluginErrorType type, String message) {
        return new PluginError(pluginName, type, message, null);
    }

    /** 从异常创建 */
    public static PluginError fromException(String pluginName, PluginErrorType type, Throwable cause) {
        return new PluginError(pluginName, type, cause.getMessage(), cause);
    }
}
