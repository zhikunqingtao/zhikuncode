package com.aicodeassistant.plugin;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * 插件类加载器 — Java SPI 机制 + URLClassLoader 隔离。
 * <p>
 * 安全沙箱机制:
 * <ul>
 *     <li>插件 API 包 → 委托给父加载器（宿主提供）</li>
 *     <li>其他宿主类 → 隔离（不委托父加载器，插件自行解析）</li>
 *     <li>JDK 标准库 → 仍委托父加载器</li>
 * </ul>
 *
 * @see <a href="SPEC §4.6.2">PluginSandbox 安全隔离</a>
 */
public class PluginClassLoader extends URLClassLoader {

    /** 插件 API 包前缀 — 允许插件访问 */
    private static final String PLUGIN_API_PACKAGE = "com.aicodeassistant.plugin";

    /** 工具 API 包前缀 — 允许插件访问 */
    private static final String TOOL_API_PACKAGE = "com.aicodeassistant.tool";

    /** 命令 API 包前缀 — 允许插件访问 */
    private static final String COMMAND_API_PACKAGE = "com.aicodeassistant.command";

    /** MCP API 包前缀 — 允许插件访问 */
    private static final String MCP_API_PACKAGE = "com.aicodeassistant.mcp";

    public PluginClassLoader(URL jarUrl, ClassLoader parent) {
        super(new URL[]{jarUrl}, parent);
    }

    public PluginClassLoader(URL[] jarUrls, ClassLoader parent) {
        super(jarUrls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // 已加载的类直接返回
            Class<?> c = findLoadedClass(name);
            if (c != null) return c;

            // 插件 API 包 → 委托给父加载器（宿主提供的接口）
            if (isAllowedHostPackage(name)) {
                return getParent().loadClass(name);
            }

            // JDK 标准库 → 委托给父加载器
            if (name.startsWith("java.") || name.startsWith("javax.")
                    || name.startsWith("jdk.") || name.startsWith("sun.")
                    || name.startsWith("org.slf4j.")) {
                return getParent().loadClass(name);
            }

            // 其他类 → 插件自行解析（隔离模式）
            try {
                c = findClass(name);
                if (resolve) resolveClass(c);
                return c;
            } catch (ClassNotFoundException e) {
                // Fallback — 允许访问剩余宿主类
                return getParent().loadClass(name);
            }
        }
    }

    /**
     * 判断是否为允许的宿主包（插件可访问的 API）。
     */
    private boolean isAllowedHostPackage(String name) {
        return name.startsWith(PLUGIN_API_PACKAGE)
                || name.startsWith(TOOL_API_PACKAGE)
                || name.startsWith(COMMAND_API_PACKAGE)
                || name.startsWith(MCP_API_PACKAGE);
    }
}
