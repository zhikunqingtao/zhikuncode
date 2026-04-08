package com.aicodeassistant.plugin;

/**
 * 插件来源类型 — v1.45.0 精简为 3 种。
 *
 * @see <a href="SPEC §4.6.2">插件来源</a>
 */
public enum PluginSourceType {
    /** 本地 JAR 目录 (~/.qoder/plugins/*.jar) */
    LOCAL,
    /** 市场插件 (plugin@marketplace → 下载 JAR) */
    MARKETPLACE,
    /** 内置插件 (classpath 内的 SPI 实现) */
    BUILTIN
}
