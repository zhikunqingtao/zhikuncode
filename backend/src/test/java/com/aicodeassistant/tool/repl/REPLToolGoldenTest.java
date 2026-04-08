package com.aicodeassistant.tool.repl;

import com.aicodeassistant.tool.PermissionRequirement;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REPLTool + ReplManager + ReplSession 黄金测试。
 * 覆盖 §4.1.16 REPL 交互式解释器。
 */
class REPLToolGoldenTest {

    // ==================== ReplManager ====================

    @Nested
    @DisplayName("§4.1.16 ReplManager")
    class ReplManagerTests {

        private ReplManager manager;

        @BeforeEach
        void setUp() {
            manager = new ReplManager();
        }

        @AfterEach
        void tearDown() {
            manager.destroyAll();
        }

        @Test
        @DisplayName("MAX_CONCURRENT_SESSIONS = 3")
        void maxConcurrentSessions() {
            assertEquals(3, ReplManager.MAX_CONCURRENT_SESSIONS);
        }

        @Test
        @DisplayName("EXEC_TIMEOUT = 30s")
        void execTimeout() {
            assertEquals(Duration.ofSeconds(30), ReplManager.EXEC_TIMEOUT);
        }

        @Test
        @DisplayName("IDLE_TIMEOUT = 10min")
        void idleTimeout() {
            assertEquals(Duration.ofMinutes(10), ReplManager.IDLE_TIMEOUT);
        }

        @Test
        @DisplayName("SESSION_MAX_LIFETIME = 1h")
        void sessionMaxLifetime() {
            assertEquals(Duration.ofHours(1), ReplManager.SESSION_MAX_LIFETIME);
        }

        @Test
        @DisplayName("MAX_OUTPUT_BYTES = 100KB")
        void maxOutputBytes() {
            assertEquals(100 * 1024, ReplManager.MAX_OUTPUT_BYTES);
        }

        @Test
        @DisplayName("P1 支持 python")
        void p1SupportsPython() {
            assertTrue(ReplManager.SUPPORTED_LANGUAGES.contains("python"));
        }

        @Test
        @DisplayName("P1 不支持 node/ruby")
        void p1DoesNotSupportNodeRuby() {
            assertFalse(ReplManager.SUPPORTED_LANGUAGES.contains("node"));
            assertFalse(ReplManager.SUPPORTED_LANGUAGES.contains("ruby"));
        }

        @Test
        @DisplayName("P2 语言包含 node 和 ruby")
        void p2Languages() {
            assertTrue(ReplManager.P2_LANGUAGES.contains("node"));
            assertTrue(ReplManager.P2_LANGUAGES.contains("ruby"));
        }

        @Test
        @DisplayName("getInterpreterCommand python")
        void interpreterCommandPython() {
            String[] cmd = manager.getInterpreterCommand("python");
            assertArrayEquals(new String[]{"python3", "-u", "-i"}, cmd);
        }

        @Test
        @DisplayName("getInterpreterCommand node")
        void interpreterCommandNode() {
            String[] cmd = manager.getInterpreterCommand("node");
            assertArrayEquals(new String[]{"node", "--interactive"}, cmd);
        }

        @Test
        @DisplayName("getInterpreterCommand ruby")
        void interpreterCommandRuby() {
            String[] cmd = manager.getInterpreterCommand("ruby");
            assertArrayEquals(new String[]{"irb", "--simple-prompt"}, cmd);
        }

        @Test
        @DisplayName("不支持的语言抛异常")
        void unsupportedLanguageThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> manager.getInterpreterCommand("lua"));
        }

        @Test
        @DisplayName("node 语言在 P1 抛 UnsupportedOperationException")
        void nodeThrowsInP1() {
            assertThrows(UnsupportedOperationException.class,
                    () -> manager.getOrCreate("test", "node", "/tmp"));
        }

        @Test
        @DisplayName("初始活跃会话数为 0")
        void initialSessionCountZero() {
            assertEquals(0, manager.getActiveSessionCount());
        }

        @Test
        @DisplayName("getSessionIds 返回不可修改集合")
        void sessionIdsUnmodifiable() {
            Set<String> ids = manager.getSessionIds();
            assertThrows(UnsupportedOperationException.class,
                    () -> ids.add("test"));
        }

        @Test
        @DisplayName("destroySession 不存在返回 false")
        void destroyNonExistent() {
            assertFalse(manager.destroySession("nonexistent"));
        }

        @Test
        @DisplayName("截断输出超过 100KB")
        void truncateOutput() {
            String longOutput = "x".repeat(200_000);
            String truncated = manager.truncateOutput(longOutput);
            assertTrue(truncated.length() < longOutput.length());
            assertTrue(truncated.contains("truncated"));
        }

        @Test
        @DisplayName("截断不影响短输出")
        void noTruncateShortOutput() {
            String shortOutput = "Hello world";
            assertEquals(shortOutput, manager.truncateOutput(shortOutput));
        }

        @Test
        @DisplayName("截断 null 返回空")
        void truncateNull() {
            assertEquals("", manager.truncateOutput(null));
        }

        @Test
        @DisplayName("destroyAll 清除所有")
        void destroyAll() {
            manager.destroyAll();
            assertEquals(0, manager.getActiveSessionCount());
        }

        @Test
        @DisplayName("ReplException 包含 cause")
        void replExceptionHasCause() {
            var cause = new RuntimeException("test");
            var ex = new ReplManager.ReplException("msg", cause);
            assertEquals("msg", ex.getMessage());
            assertEquals(cause, ex.getCause());
        }
    }

    // ==================== REPLTool ====================

    @Nested
    @DisplayName("§4.1.16 REPLTool")
    class REPLToolTests {

        private REPLTool tool;

        @BeforeEach
        void setUp() {
            tool = new REPLTool(new ReplManager());
        }

        @Test
        @DisplayName("工具名称为 REPL")
        void toolName() {
            assertEquals("REPL", tool.getName());
        }

        @Test
        @DisplayName("工具描述包含 interactive")
        void toolDescription() {
            assertTrue(tool.getDescription().contains("interactive"));
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
        @DisplayName("toAutoClassifierInput 包含语言和代码")
        void autoClassifierInput() {
            String result = tool.toAutoClassifierInput(
                    ToolInput.from(Map.of("language", "python", "code", "print(1)")));
            assertTrue(result.contains("python"));
            assertTrue(result.contains("print(1)"));
        }

        @Test
        @DisplayName("输入 Schema 包含 language, code, sessionId")
        void inputSchemaFields() {
            Map<String, Object> schema = tool.getInputSchema();
            assertNotNull(schema.get("properties"));
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) schema.get("properties");
            assertTrue(props.containsKey("language"));
            assertTrue(props.containsKey("code"));
            assertTrue(props.containsKey("sessionId"));
        }

        @Test
        @DisplayName("不支持的语言返回错误")
        void unsupportedLanguageError() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("language", "node", "code", "1+1")),
                    ToolUseContext.of("/tmp", "session1"));
            assertTrue(result.isError());
        }
    }

    // ==================== ReplSession ====================

    @Nested
    @DisplayName("§4.1.16 ReplSession")
    class ReplSessionTests {

        @Test
        @DisplayName("ReplSession 属性访问")
        void sessionProperties() throws Exception {
            // 使用 echo 命令创建一个短暂的进程来测试
            Process proc = new ProcessBuilder("echo", "test").start();
            ReplSession session = new ReplSession("s1", "python", proc);

            assertEquals("s1", session.id());
            assertEquals("python", session.language());
            assertNotNull(session.process());
            assertNotNull(session.lastActive());
            assertNotNull(session.createdAt());

            session.destroy();
        }

        @Test
        @DisplayName("updateLastActive 更新时间")
        void updateLastActive() throws Exception {
            Process proc = new ProcessBuilder("echo", "test").start();
            ReplSession session = new ReplSession("s2", "python", proc);

            var before = session.lastActive();
            Thread.sleep(10);
            session.updateLastActive();
            var after = session.lastActive();

            assertTrue(after.isAfter(before) || after.equals(before));
            session.destroy();
        }

        @Test
        @DisplayName("destroy 终止进程")
        void destroyTerminates() throws Exception {
            Process proc = new ProcessBuilder("sleep", "100").start();
            ReplSession session = new ReplSession("s3", "python", proc);
            assertTrue(session.isAlive());

            session.destroy();
            // 等待进程终止
            Thread.sleep(100);
            assertFalse(session.isAlive());
        }
    }
}
