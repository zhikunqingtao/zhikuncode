package com.aicodeassistant.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 插件加载器 — 从多种来源加载插件。
 * <p>
 * 加载顺序:
 * <ol>
 *     <li>内置插件 (classpath SPI)</li>
 *     <li>本地 JAR 插件 (~/.qoder/plugins/*.jar)</li>
 *     <li>市场插件 (未来扩展)</li>
 * </ol>
 *
 * @see <a href="SPEC §4.6.2">插件来源与加载</a>
 */
@Service
public class PluginLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

    /** 宿主插件 API 版本 */
    public static final String PLUGIN_API_VERSION = "1.0.0";

    /** 插件目录 */
    private static final String PLUGINS_DIR = ".qoder/plugins";

    /**
     * 加载所有插件 — 含内置与外部插件。
     *
     * @return 加载结果
     */
    public PluginLoadResult loadAllPlugins() {
        List<LoadedPlugin> enabled = new ArrayList<>();
        List<LoadedPlugin> disabled = new ArrayList<>();
        List<PluginError> errors = new ArrayList<>();

        // 1. 加载内置插件 (SPI)
        loadBuiltinPlugins(enabled, disabled, errors);

        // 2. 加载本地 JAR 插件
        loadLocalPlugins(enabled, disabled, errors);

        log.info("PluginLoader: loaded {} enabled, {} disabled, {} errors",
                enabled.size(), disabled.size(), errors.size());

        return new PluginLoadResult(
                Collections.unmodifiableList(enabled),
                Collections.unmodifiableList(disabled),
                Collections.unmodifiableList(errors)
        );
    }

    /**
     * 通过 Java SPI 加载内置插件。
     */
    private void loadBuiltinPlugins(List<LoadedPlugin> enabled,
                                     List<LoadedPlugin> disabled,
                                     List<PluginError> errors) {
        ServiceLoader<PluginExtension> loader = ServiceLoader.load(PluginExtension.class);
        for (PluginExtension ext : loader) {
            try {
                // API 版本兼容检查
                if (!isApiCompatible(ext)) {
                    errors.add(PluginError.of(ext.name(),
                            PluginError.PluginErrorType.API_VERSION_INCOMPATIBLE,
                            String.format("Plugin '%s' requires API %s-%s, host is %s",
                                    ext.name(), ext.minApiVersion(), ext.maxApiVersion(),
                                    PLUGIN_API_VERSION)));
                    continue;
                }

                // 创建上下文并加载
                PluginContext ctx = createContext(ext.name());
                ext.onLoad(ctx);

                LoadedPlugin plugin = LoadedPlugin.builtin(ext.name(), ext);
                if (ext.isEnabledByDefault()) {
                    enabled.add(plugin);
                } else {
                    disabled.add(plugin.withDisabled());
                }

                log.info("Loaded builtin plugin: {} v{}", ext.name(), ext.version());
            } catch (Exception e) {
                log.error("Failed to load builtin plugin: {}", ext.name(), e);
                errors.add(PluginError.fromException(ext.name(),
                        PluginError.PluginErrorType.COMPONENT_LOAD_FAILED, e));
            }
        }
    }

    /**
     * 从 ~/.qoder/plugins/ 目录加载本地 JAR 插件。
     */
    private void loadLocalPlugins(List<LoadedPlugin> enabled,
                                   List<LoadedPlugin> disabled,
                                   List<PluginError> errors) {
        String home = System.getProperty("user.home");
        if (home == null) return;

        Path pluginsDir = Path.of(home, PLUGINS_DIR);
        if (!Files.isDirectory(pluginsDir)) {
            log.debug("No plugins directory found at {}", pluginsDir);
            return;
        }

        try (Stream<Path> jars = Files.list(pluginsDir)) {
            jars.filter(p -> p.toString().endsWith(".jar"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach(jarPath -> loadJarPlugin(jarPath, enabled, disabled, errors));
        } catch (IOException e) {
            log.warn("Failed to scan plugins directory: {}", pluginsDir, e);
        }
    }

    /**
     * 从 JAR 文件加载单个插件。
     */
    private void loadJarPlugin(Path jarPath,
                                List<LoadedPlugin> enabled,
                                List<LoadedPlugin> disabled,
                                List<PluginError> errors) {
        String jarName = jarPath.getFileName().toString();
        try {
            URL jarUrl = jarPath.toUri().toURL();
            PluginClassLoader classLoader = new PluginClassLoader(jarUrl,
                    getClass().getClassLoader());

            ServiceLoader<PluginExtension> loader = ServiceLoader.load(
                    PluginExtension.class, classLoader);

            for (PluginExtension ext : loader) {
                // API 版本兼容检查
                if (!isApiCompatible(ext)) {
                    errors.add(PluginError.of(ext.name(),
                            PluginError.PluginErrorType.API_VERSION_INCOMPATIBLE,
                            String.format("Plugin '%s' requires API %s-%s, host is %s",
                                    ext.name(), ext.minApiVersion(), ext.maxApiVersion(),
                                    PLUGIN_API_VERSION)));
                    continue;
                }

                PluginContext ctx = createContext(ext.name());
                ext.onLoad(ctx);

                LoadedPlugin plugin = LoadedPlugin.local(
                        ext.name(), jarPath.toString(), ext, ext.isEnabledByDefault());

                if (plugin.enabled()) {
                    enabled.add(plugin);
                } else {
                    disabled.add(plugin);
                }

                log.info("Loaded JAR plugin: {} v{} from {}", ext.name(), ext.version(), jarName);
            }
        } catch (Exception e) {
            log.error("Failed to load JAR plugin: {}", jarName, e);
            errors.add(PluginError.of(jarName,
                    PluginError.PluginErrorType.CLASS_LOAD_FAILED,
                    "Failed to load plugin from " + jarName + ": " + e.getMessage()));
        }
    }

    /**
     * API 版本兼容检查。
     */
    boolean isApiCompatible(PluginExtension ext) {
        return compareVersions(PLUGIN_API_VERSION, ext.minApiVersion()) >= 0
                && compareVersions(PLUGIN_API_VERSION, ext.maxApiVersion()) <= 0;
    }

    /**
     * 简化的语义版本比较。
     */
    static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int len = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < len; i++) {
            int n1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int n2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (n1 != n2) return Integer.compare(n1, n2);
        }
        return 0;
    }

    /**
     * 创建插件上下文。
     */
    private PluginContext createContext(String pluginName) {
        return new DefaultPluginContext(pluginName);
    }

    /**
     * 默认插件上下文实现。
     */
    private static class DefaultPluginContext implements PluginContext {
        private final String pluginName;
        private final Logger logger;

        DefaultPluginContext(String pluginName) {
            this.pluginName = pluginName;
            this.logger = LoggerFactory.getLogger("plugin." + pluginName);
        }

        @Override
        public Logger getLogger() { return logger; }

        @Override
        public Optional<String> getConfig(String key) { return Optional.empty(); }

        @Override
        public Path getDataDirectory() {
            String home = System.getProperty("user.home", ".");
            Path dataDir = Path.of(home, ".qoder", "plugins", pluginName, "data");
            try {
                Files.createDirectories(dataDir);
            } catch (IOException e) {
                // 忽略
            }
            return dataDir;
        }

        @Override
        public String getHostApiVersion() { return PLUGIN_API_VERSION; }
    }
}
