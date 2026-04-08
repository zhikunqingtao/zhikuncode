package com.aicodeassistant.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FeatureFlagService 单元测试。
 */
class FeatureFlagServiceTest {

    private FeatureFlagService service;

    @BeforeEach
    void setUp() {
        service = new FeatureFlagService();
        service.setFlags(Map.of(
                "THINKING_MODE", true,
                "MAX_RETRIES", 5,
                "MODEL_NAME", "gpt-4o",
                "TEMPERATURE", 0.7
        ));
    }

    @Test
    @DisplayName("getFeatureValue - YAML 配置存在时返回 YAML 值")
    void getFeatureValue_yamlConfigExists() {
        assertTrue(service.getFeatureValue("THINKING_MODE", false));
        assertEquals(5, (int) service.getFeatureValue("MAX_RETRIES", 3));
        assertEquals("gpt-4o", service.getFeatureValue("MODEL_NAME", "default"));
    }

    @Test
    @DisplayName("getFeatureValue - YAML 配置不存在时返回默认值")
    void getFeatureValue_fallbackToDefault() {
        assertFalse(service.getFeatureValue("NON_EXISTENT", false));
        assertEquals(10, (int) service.getFeatureValue("UNKNOWN_INT", 10));
        assertEquals("fallback", service.getFeatureValue("UNKNOWN_STR", "fallback"));
    }

    @Test
    @DisplayName("checkGate - 布尔值特化快速路径")
    void checkGate_booleanShortcut() {
        assertTrue(service.checkGate("THINKING_MODE"));
        assertFalse(service.checkGate("NON_EXISTENT_GATE"));
    }

    @Test
    @DisplayName("isEnabled - 布尔值快捷方法")
    void isEnabled_booleanShortcut() {
        assertTrue(service.isEnabled("THINKING_MODE"));
        assertFalse(service.isEnabled("CLASSIFIER_V2"));
    }

    @Test
    @DisplayName("setFeatureValue - 运行时更新特性标志")
    void setFeatureValue_runtimeUpdate() {
        assertFalse(service.isEnabled("NEW_FEATURE"));
        service.setFeatureValue("NEW_FEATURE", true);
        assertTrue(service.isEnabled("NEW_FEATURE"));
    }

    @Test
    @DisplayName("getAllFlags - 返回只读视图")
    void getAllFlags_unmodifiableView() {
        Map<String, Object> allFlags = service.getAllFlags();
        assertNotNull(allFlags);
        assertEquals(4, allFlags.size());
        assertThrows(UnsupportedOperationException.class,
                () -> allFlags.put("NEW", "value"));
    }

    @Test
    @DisplayName("setFlags - 批量设置标志")
    void setFlags_batchUpdate() {
        service.setFlags(Map.of("A", true, "B", false));
        assertTrue(service.isEnabled("A"));
        assertFalse(service.isEnabled("B"));
        // 之前的标志应该被替换
        assertFalse(service.isEnabled("THINKING_MODE"));
    }

    @Test
    @DisplayName("getFeatureValue - 类型兼容性")
    void getFeatureValue_typeCompatibility() {
        service.setFeatureValue("INT_VAL", 42);
        service.setFeatureValue("STR_VAL", "hello");
        service.setFeatureValue("BOOL_VAL", true);

        assertEquals(42, (int) service.getFeatureValue("INT_VAL", 0));
        assertEquals("hello", service.getFeatureValue("STR_VAL", ""));
        assertTrue(service.getFeatureValue("BOOL_VAL", false));
    }

    @Test
    @DisplayName("并发安全 - ConcurrentHashMap 保证线程安全")
    void concurrentSafety() throws InterruptedException {
        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                service.setFeatureValue("CONCURRENT_" + idx, true);
                service.getFeatureValue("CONCURRENT_" + idx, false);
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        for (int i = 0; i < threads.length; i++) {
            assertTrue(service.isEnabled("CONCURRENT_" + i));
        }
    }
}
