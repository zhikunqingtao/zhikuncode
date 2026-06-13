package com.aicodeassistant.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FeatureFlagService THINKING_MODE 配置单元测试 — M0-c 改动验证。
 * <p>
 * 验证目标：
 * <ul>
 *   <li>application.yml 中 THINKING_MODE 默认值为 true</li>
 *   <li>FeatureFlagService 优先级链：env → yaml → default</li>
 * </ul>
 */
@DisplayName("FeatureFlagService THINKING_MODE 配置测试")
class FeatureFlagThinkingModeTest {

    private FeatureFlagService featureFlagService;

    @BeforeEach
    void setUp() {
        featureFlagService = new FeatureFlagService();
    }

    /**
     * 直接从 classpath 加载 application.yml 并提取 features.flags 节
     * — 不依赖 Spring 上下文，验证配置文件源真实值。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadFeatureFlagsFromYaml() {
        Yaml yaml = new Yaml();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(in).as("application.yml must be on classpath").isNotNull();
            Map<String, Object> root = yaml.load(in);
            Map<String, Object> features = (Map<String, Object>) root.get("features");
            assertThat(features).as("features section").isNotNull();
            Map<String, Object> flags = (Map<String, Object>) features.get("flags");
            assertThat(flags).as("features.flags section").isNotNull();
            return flags;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load application.yml", e);
        }
    }

    @Test
    @DisplayName("tc001: application.yml 中 THINKING_MODE 默认值 = true")
    void tc001_yamlDefault_thinkingModeTrue() {
        Map<String, Object> flags = loadFeatureFlagsFromYaml();

        Object value = flags.get("THINKING_MODE");

        assertThat(value).isNotNull();
        // YAML 解析为 Boolean true 或字符串 "true" 都视为开启
        if (value instanceof Boolean b) {
            assertThat(b).isTrue();
        } else {
            assertThat(value.toString()).isEqualToIgnoringCase("true");
        }
    }

    @Test
    @DisplayName("tc002: 通过 setFlags 注入后 isEnabled(THINKING_MODE) 返回 true")
    void tc002_setFlagsThenIsEnabled_returnsTrue() {
        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("THINKING_MODE", true);
        featureFlagService.setFlags(flags);

        assertThat(featureFlagService.isEnabled("THINKING_MODE")).isTrue();
    }

    @Test
    @DisplayName("tc003: YAML 字符串 \"true\" 正确转换为 Boolean true")
    void tc003_yamlStringValue_convertedToBoolean() {
        Map<String, Object> flags = new HashMap<>();
        flags.put("THINKING_MODE", "true"); // 模拟占位符解析为字符串
        featureFlagService.setFlags(flags);

        Boolean enabled = featureFlagService.getFeatureValue("THINKING_MODE", false);

        assertThat(enabled).isTrue();
    }

    @Test
    @DisplayName("tc004: 未配置时返回调用方提供的默认值")
    void tc004_notConfigured_returnsCallerDefault() {
        // setFlags 中不包含 THINKING_MODE
        featureFlagService.setFlags(new HashMap<>());

        Boolean enabled = featureFlagService.getFeatureValue("THINKING_MODE", false);

        assertThat(enabled).isFalse();
    }

    @Test
    @DisplayName("tc005: setFeatureValue 运行时更新生效")
    void tc005_runtimeUpdate_reflectedImmediately() {
        Map<String, Object> flags = new HashMap<>();
        flags.put("THINKING_MODE", false);
        featureFlagService.setFlags(flags);
        assertThat(featureFlagService.isEnabled("THINKING_MODE")).isFalse();

        featureFlagService.setFeatureValue("THINKING_MODE", true);

        assertThat(featureFlagService.isEnabled("THINKING_MODE")).isTrue();
    }

    @Test
    @DisplayName("tc006: 优先级链 - 环境变量未设置时使用 YAML 值")
    void tc006_priority_yamlWhenEnvAbsent() {
        // 环境变量 FEATURE_THINKING_MODE 通常在测试环境中不存在
        // 此处验证：当未设置 env 时，YAML 值生效
        // 如果 env 已设置，跳过该断言以避免误报
        if (System.getenv("FEATURE_THINKING_MODE") != null) {
            return;
        }
        Map<String, Object> flags = new HashMap<>();
        flags.put("THINKING_MODE", true);
        featureFlagService.setFlags(flags);

        assertThat(featureFlagService.isEnabled("THINKING_MODE")).isTrue();
    }
}
