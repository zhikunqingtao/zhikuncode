package com.aicodeassistant.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 特性标志服务 — 管理应用级特性开关。
 * <p>
 * 3 级优先级链:
 * <ol>
 *     <li>环境变量覆盖 (FEATURE_&lt;KEY&gt; 格式，最高优先级)</li>
 *     <li>application.yml 配置 (features.flags.&lt;key&gt;)</li>
 *     <li>默认值 (调用方提供)</li>
 * </ol>
 * <p>
 * 已知特性标志:
 * <pre>
 * | 标志名                  | 类型      | 默认值  | 使用方                |
 * |------------------------|----------|--------|----------------------|
 * | THINKING_MODE          | boolean  | false  | QueryEngine          |
 * | TOOL_SEARCH            | boolean  | false  | ToolRegistry         |
 * | SANDBOX_DEFAULT_ON     | boolean  | false  | SandboxManager       |
 * | CLASSIFIER_V2          | boolean  | false  | PermissionSystem     |
 * | ENABLE_AGENT_SWARMS    | boolean  | false  | QueryEngine          |
 * </pre>
 *
 * @see <a href="SPEC section 2.3">启动流程 — 特性标志初始化</a>
 */
@Service
@ConfigurationProperties(prefix = "features")
public class FeatureFlagService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);

    /**
     * 从 application.yml 的 features.flags 节自动绑定。
     * Spring Boot 在启动时自动将 YAML 配置注入此 Map。
     */
    private Map<String, Object> flags = new ConcurrentHashMap<>();

    public Map<String, Object> getFlags() {
        return flags;
    }

    public void setFlags(Map<String, Object> flags) {
        this.flags = new ConcurrentHashMap<>(flags);
    }

    // ═══════════════════════════════════════════════════════════════
    // 核心访问方法 — 3 级优先级链
    // ═══════════════════════════════════════════════════════════════

    /**
     * 获取特性值 — 非阻塞，同步返回。
     * <p>
     * 优先级链:
     *   1. 环境变量覆盖 (FEATURE_&lt;KEY&gt; 格式，最高优先级)
     *   2. application.yml 配置 (features.flags.&lt;key&gt;)
     *   3. 默认值 (调用方提供)
     *
     * @param featureKey   特性标志名称
     * @param defaultValue 默认值（同时决定返回类型）
     * @return 特性值
     */
    @SuppressWarnings("unchecked")
    public <T> T getFeatureValue(String featureKey, T defaultValue) {
        // 1. 环境变量覆盖 — FEATURE_ 前缀 + key 大写
        String envKey = "FEATURE_" + featureKey.toUpperCase();
        String envVal = System.getenv(envKey);
        if (envVal != null) {
            return (T) convertEnvValue(envVal, defaultValue);
        }

        // 2. YAML 配置
        Object yamlVal = flags.get(featureKey);
        if (yamlVal != null) {
            // 处理 YAML 占位符解析后类型不匹配的情况（如 ${KEY:true} 解析为 String "true"）
            if (defaultValue instanceof Boolean && yamlVal instanceof String) {
                return (T) Boolean.valueOf((String) yamlVal);
            }
            return (T) yamlVal;
        }

        // 3. 默认值
        return defaultValue;
    }

    /**
     * 门控检查 — 快速路径，布尔值特化。
     *
     * @param gateKey 门控键名
     * @return 是否开启
     */
    public boolean checkGate(String gateKey) {
        return getFeatureValue(gateKey, false);
    }

    /**
     * 特性是否启用 — 布尔值快捷方法。
     *
     * @param featureKey 特性键名
     * @return 是否启用
     */
    public boolean isEnabled(String featureKey) {
        return getFeatureValue(featureKey, false);
    }

    /**
     * 运行时更新特性标志 — 供 /config API 使用。
     *
     * @param key   标志键名
     * @param value 标志值
     */
    public void setFeatureValue(String key, Object value) {
        flags.put(key, value);
        log.info("Feature flag updated: {} = {}", key, value);
    }

    /**
     * 获取所有已配置的特性标志（只读视图）。
     */
    public Map<String, Object> getAllFlags() {
        return Collections.unmodifiableMap(flags);
    }

    /**
     * 环境变量字符串到目标类型转换。
     */
    private Object convertEnvValue(String envVal, Object defaultValue) {
        if (defaultValue instanceof Boolean) {
            return Boolean.parseBoolean(envVal);
        } else if (defaultValue instanceof Integer) {
            try {
                return Integer.parseInt(envVal);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        } else if (defaultValue instanceof Long) {
            try {
                return Long.parseLong(envVal);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        } else if (defaultValue instanceof Double) {
            try {
                return Double.parseDouble(envVal);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return envVal; // String 类型直接返回
    }
}
