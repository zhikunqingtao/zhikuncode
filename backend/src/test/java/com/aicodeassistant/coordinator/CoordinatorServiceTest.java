package com.aicodeassistant.coordinator;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CoordinatorService 单元测试 — 验证 getenv/setProperty 命名空间 bug 修复。
 * <p>
 * 核心验证点：matchSessionMode() 写入后，isCoordinatorMode() 必须能读到。
 */
class CoordinatorServiceTest {

    private FeatureFlagService featureFlags;
    private ToolRegistry toolRegistry;
    private CoordinatorService coordinatorService;

    @BeforeEach
    void setUp() {
        featureFlags = mock(FeatureFlagService.class);
        toolRegistry = mock(ToolRegistry.class);
        coordinatorService = new CoordinatorService(featureFlags, toolRegistry, null);
    }

    @Test
    @DisplayName("matchSessionMode(coordinator) 后 isCoordinatorMode() 应返回 true")
    void matchSessionMode_coordinator_then_isCoordinatorMode_returns_true() {
        when(featureFlags.isEnabled("COORDINATOR_MODE")).thenReturn(true);

        // 模拟会话恢复：将模式设为 coordinator
        String msg = coordinatorService.matchSessionMode("coordinator");
        assertNotNull(msg);
        assertTrue(msg.contains("Entered coordinator mode"));

        // 关键断言：isCoordinatorMode() 必须返回 true
        assertTrue(coordinatorService.isCoordinatorMode(),
                "isCoordinatorMode() should return true after matchSessionMode(\"coordinator\")");
    }

    @Test
    @DisplayName("matchSessionMode(normal) 后 isCoordinatorMode() 应返回 false")
    void matchSessionMode_normal_then_isCoordinatorMode_returns_false() {
        when(featureFlags.isEnabled("COORDINATOR_MODE")).thenReturn(true);

        // 先进入 coordinator 模式
        coordinatorService.matchSessionMode("coordinator");
        assertTrue(coordinatorService.isCoordinatorMode());

        // 再切回 normal 模式
        String msg = coordinatorService.matchSessionMode("normal");
        assertNotNull(msg);
        assertTrue(msg.contains("Exited coordinator mode"));

        // 关键断言：isCoordinatorMode() 必须返回 false
        assertFalse(coordinatorService.isCoordinatorMode(),
                "isCoordinatorMode() should return false after matchSessionMode(\"normal\")");
    }

    @Test
    @DisplayName("FeatureFlag 未启用时 isCoordinatorMode() 始终返回 false")
    void featureFlag_disabled_then_always_false() {
        when(featureFlags.isEnabled("COORDINATOR_MODE")).thenReturn(false);

        // 即使 runtimeEnv 中有值，FeatureFlag 禁用则返回 false
        coordinatorService.matchSessionMode("coordinator");
        assertFalse(coordinatorService.isCoordinatorMode());
    }

    @Test
    @DisplayName("相同模式无需切换时返回 null")
    void matchSessionMode_same_mode_returns_null() {
        when(featureFlags.isEnabled("COORDINATOR_MODE")).thenReturn(true);

        // 默认非 coordinator 模式，matchSessionMode("normal") 无需切换
        String msg = coordinatorService.matchSessionMode("normal");
        assertNull(msg, "Should return null when current mode matches target");
    }

    @Test
    @DisplayName("null sessionMode 返回 null")
    void matchSessionMode_null_returns_null() {
        String msg = coordinatorService.matchSessionMode(null);
        assertNull(msg);
    }

    @Test
    @DisplayName("shouldSuggestCoordinator 关键词启发式判断")
    void shouldSuggestCoordinator_heuristic() {
        when(featureFlags.isEnabled("COORDINATOR_MODE")).thenReturn(true);

        // 少于 2 个信号 → false
        assertFalse(coordinatorService.shouldSuggestCoordinator("refactor this file"));

        // >= 2 个信号 → true
        assertTrue(coordinatorService.shouldSuggestCoordinator(
                "refactor and migrate multiple files in parallel"));
    }

    @Test
    @DisplayName("shouldSuggestCoordinator FeatureFlag 禁用时返回 false")
    void shouldSuggestCoordinator_disabled() {
        when(featureFlags.isEnabled("COORDINATOR_MODE")).thenReturn(false);
        assertFalse(coordinatorService.shouldSuggestCoordinator(
                "refactor and migrate multiple files in parallel"));
    }
}
