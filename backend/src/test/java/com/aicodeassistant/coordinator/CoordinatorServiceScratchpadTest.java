package com.aicodeassistant.coordinator;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CoordinatorService.getScratchpadDir 路径穿越防护单元测试（CWE-22 深度防御）。
 * <p>与 SwarmController.TEAM_NAME_PATTERN 策略对齐：
 * 仅接受 {@code ^[A-Za-z0-9_-]{1,128}$}，其余一律回退 {@code "default"}。
 *
 * <p>测试约束：
 * <ul>
 *   <li>通过 {@code user.dir} 系统属性重定向目标根，避免污染真实 workspace</li>
 *   <li>每个用例结束后彻底清理临时 scratchpad 目录</li>
 * </ul>
 */
class CoordinatorServiceScratchpadTest {

    private CoordinatorService coordinatorService;
    private String originalUserDir;

    @TempDir
    Path tempWorkspace;

    @BeforeEach
    void setUp() {
        FeatureFlagService featureFlags = mock(FeatureFlagService.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        coordinatorService = new CoordinatorService(featureFlags, toolRegistry, null);

        // 重定向 user.dir 到 JUnit @TempDir，确保任何路径穿越都被 TempDir 隔离
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempWorkspace.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        System.setProperty("user.dir", originalUserDir);
        // 清理 tempWorkspace 下残留
        if (Files.exists(tempWorkspace)) {
            try (var stream = Files.walk(tempWorkspace)) {
                stream.sorted(Comparator.reverseOrder())
                      .filter(p -> !p.equals(tempWorkspace))
                      .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignore) {} });
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 恶意 sessionId —— 必须回退到 "default"
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("路径穿越 ../../../tmp/pwned → 回退 default，不创建穿越目录")
    void rejectPathTraversalDotDot() {
        Path dir = coordinatorService.getScratchpadDir("../../../tmp/pwned");

        assertTrue(dir.endsWith(Path.of(".zhikun", "scratchpad", "default")),
                "必须回退到 default 目录，而非路径穿越");
        assertTrue(Files.exists(dir), "default 目录应被实际创建");
        // 负向：tempWorkspace 外层父目录下不应出现 pwned 目录
        assertFalse(Files.exists(tempWorkspace.getParent().resolve("tmp").resolve("pwned")),
                "必须不能在 workspace 外部创建 pwned 目录");
    }

    @Test
    @DisplayName("绝对路径 /etc/passwd 风格 → 回退 default")
    void rejectAbsolutePathStyle() {
        Path dir = coordinatorService.getScratchpadDir("/etc/passwd");
        assertTrue(dir.endsWith(Path.of(".zhikun", "scratchpad", "default")));
    }

    @Test
    @DisplayName("包含正斜杠的 sessionId → 回退 default")
    void rejectForwardSlash() {
        Path dir = coordinatorService.getScratchpadDir("abc/def");
        assertTrue(dir.endsWith(Path.of(".zhikun", "scratchpad", "default")));
    }

    @Test
    @DisplayName("包含反斜杠的 sessionId → 回退 default")
    void rejectBackslash() {
        Path dir = coordinatorService.getScratchpadDir("abc\\def");
        assertTrue(dir.endsWith(Path.of(".zhikun", "scratchpad", "default")));
    }

    @Test
    @DisplayName("空白字符的 sessionId → 回退 default")
    void rejectWhitespace() {
        Path dir = coordinatorService.getScratchpadDir("abc def");
        assertTrue(dir.endsWith(Path.of(".zhikun", "scratchpad", "default")));
    }

    @Test
    @DisplayName("空字符串 → 回退 default（不匹配 {1,128}）")
    void rejectEmptyString() {
        Path dir = coordinatorService.getScratchpadDir("");
        assertTrue(dir.endsWith(Path.of(".zhikun", "scratchpad", "default")));
    }

    @Test
    @DisplayName("超长 sessionId (>128) → 回退 default")
    void rejectTooLong() {
        String tooLong = "a".repeat(129);
        Path dir = coordinatorService.getScratchpadDir(tooLong);
        assertTrue(dir.endsWith(Path.of(".zhikun", "scratchpad", "default")));
    }

    // ═══════════════════════════════════════════════════════════════
    // 合法 sessionId —— 必须原样保留
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("null sessionId → 回退 default（向后兼容原行为）")
    void nullSessionId_returns_default() {
        Path dir = coordinatorService.getScratchpadDir(null);
        assertTrue(dir.endsWith(Path.of(".zhikun", "scratchpad", "default")));
    }

    @Test
    @DisplayName("合法 UUID 风格 sessionId → 目录使用原值")
    void acceptValidUuidStyle() {
        String sid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        Path dir = coordinatorService.getScratchpadDir(sid);
        assertTrue(dir.endsWith(Path.of(".zhikun", "scratchpad", sid)));
        assertTrue(Files.exists(dir));
    }

    @Test
    @DisplayName("合法下划线/中划线混合 → 目录使用原值")
    void acceptValidUnderscoreDash() {
        String sid = "sess_01-A9";
        Path dir = coordinatorService.getScratchpadDir(sid);
        assertTrue(dir.endsWith(Path.of(".zhikun", "scratchpad", sid)));
    }

    @Test
    @DisplayName("边界长度 128 字符纯字母数字 → 接受")
    void acceptBoundaryLength128() {
        String sid = "a".repeat(128);
        Path dir = coordinatorService.getScratchpadDir(sid);
        assertTrue(dir.endsWith(Path.of(".zhikun", "scratchpad", sid)));
    }
}
