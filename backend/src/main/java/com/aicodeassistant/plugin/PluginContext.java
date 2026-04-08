package com.aicodeassistant.plugin;

import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 插件上下文 — 传递给 onLoad()，提供受限的宿主 API 访问。
 *
 * @see <a href="SPEC §4.6.2">插件上下文</a>
 */
public interface PluginContext {

    /** 日志记录器（插件隔离的 Logger） */
    Logger getLogger();

    /** 读取配置值 */
    Optional<String> getConfig(String key);

    /** 插件数据目录 (~/.qoder/plugins/{pluginName}/data/) */
    Path getDataDirectory();

    /** 宿主 API 版本 */
    String getHostApiVersion();
}
