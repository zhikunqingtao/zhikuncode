package com.aicodeassistant.tool.powershell;

import com.aicodeassistant.tool.PermissionRequirement;
import com.aicodeassistant.tool.ToolInput;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PowerShellTool + WindowsCondition 黄金测试。
 * 覆盖 §4.1.10 PowerShellTool Windows 支持。
 */
class PowerShellToolGoldenTest {

    // ==================== WindowsCondition ====================

    @Nested
    @DisplayName("§4.1.10 WindowsCondition")
    class WindowsConditionTests {

        @Test
        @DisplayName("isWindows 在非 Windows 上返回 false")
        void isWindowsOnNonWindows() {
            String osName = System.getProperty("os.name", "").toLowerCase();
            if (!osName.contains("windows")) {
                assertFalse(WindowsCondition.isWindows());
            }
        }

        @Test
        @DisplayName("WindowsCondition 可实例化")
        void canInstantiate() {
            assertNotNull(new WindowsCondition());
        }
    }

    // ==================== PowerShellTool ====================

    @Nested
    @DisplayName("§4.1.10 PowerShellTool")
    class PowerShellToolAttributeTests {

        private PowerShellTool tool;

        @BeforeEach
        void setUp() {
            tool = new PowerShellTool();
        }

        @Test
        @DisplayName("工具名称为 PowerShell")
        void toolName() {
            assertEquals("PowerShell", tool.getName());
        }

        @Test
        @DisplayName("工具描述包含 PowerShell")
        void toolDescription() {
            assertTrue(tool.getDescription().contains("PowerShell"));
        }

        @Test
        @DisplayName("权限要求 ALWAYS_ASK")
        void permissionAlwaysAsk() {
            assertEquals(PermissionRequirement.ALWAYS_ASK,
                    tool.getPermissionRequirement());
        }

        @Test
        @DisplayName("isDestructive = true")
        void isDestructive() {
            assertTrue(tool.isDestructive(ToolInput.from(Map.of())));
        }

        @Test
        @DisplayName("isOpenWorld = true")
        void isOpenWorld() {
            assertTrue(tool.isOpenWorld());
        }

        @Test
        @DisplayName("isConcurrencySafe = false")
        void notConcurrencySafe() {
            assertFalse(tool.isConcurrencySafe(ToolInput.from(Map.of())));
        }

        @Test
        @DisplayName("isReadOnly = false")
        void notReadOnly() {
            assertFalse(tool.isReadOnly(ToolInput.from(Map.of())));
        }

        @Test
        @DisplayName("分组为 bash")
        void groupIsBash() {
            assertEquals("bash", tool.getGroup());
        }

        @Test
        @DisplayName("DEFAULT_TIMEOUT_MS = 120000")
        void defaultTimeout() {
            assertEquals(120_000, PowerShellTool.DEFAULT_TIMEOUT_MS);
        }

        @Test
        @DisplayName("toAutoClassifierInput 包含命令")
        void autoClassifierInput() {
            String result = tool.toAutoClassifierInput(
                    ToolInput.from(Map.of("command", "Get-Process")));
            assertTrue(result.contains("PowerShell"));
            assertTrue(result.contains("Get-Process"));
        }

        @Test
        @DisplayName("输入 Schema 包含 command 和 timeout")
        void inputSchemaFields() {
            Map<String, Object> schema = tool.getInputSchema();
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) schema.get("properties");
            assertTrue(props.containsKey("command"));
            assertTrue(props.containsKey("timeout"));
        }

        @Test
        @DisplayName("非 Windows 平台 detectPowerShellExecutable 返回 null 或有效值")
        void detectPowerShell() {
            // 在 macOS/Linux 上可能有 pwsh (跨平台版本)，也可能没有
            String exe = tool.detectPowerShellExecutable();
            // 不强制断言具体值，只确认方法不抛异常
            assertTrue(exe == null || !exe.isEmpty());
        }
    }
}
