package com.aicodeassistant.tool.config;

import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 配置与消息工具集黄金测试 — 覆盖 ConfigTool、SendMessageTool、SyntheticOutputTool。
 */
class ConfigMessageToolGoldenTest {

    // ===== 1. ConfigTool 测试 =====

    @Nested
    @DisplayName("1. ConfigTool")
    class ConfigToolTests {

        private ConfigTool tool;

        @BeforeEach
        void setUp() {
            tool = new ConfigTool(null);
        }

        @Test
        @DisplayName("1.1 工具名称和分组")
        void nameAndGroup() {
            assertEquals("Config", tool.getName());
            assertEquals("config", tool.getGroup());
            assertTrue(tool.shouldDefer());
        }

        @Test
        @DisplayName("1.2 list 操作 — 列出所有配置")
        void listSettings() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("action", "list")),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("Available settings"));
            assertTrue(result.content().contains("theme"));
            assertTrue(result.content().contains("model"));
        }

        @Test
        @DisplayName("1.3 get 操作 — 读取已有配置")
        void getSetting() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("action", "get", "key", "theme")),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("system"));
        }

        @Test
        @DisplayName("1.4 get 操作 — 未知配置返回错误")
        void getUnknown() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("action", "get", "key", "nonexistent")),
                    ToolUseContext.of("/tmp", "s1"));
            assertTrue(result.isError());
            assertTrue(result.content().contains("Unknown setting"));
        }

        @Test
        @DisplayName("1.5 set 操作 — 设置有效值")
        void setValid() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("action", "set", "key", "theme", "value", "dark")),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("updated"));
            assertEquals("dark", tool.getValue("theme"));
        }

        @Test
        @DisplayName("1.6 set 操作 — 无效选项返回错误")
        void setInvalidOption() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("action", "set", "key", "theme", "value", "neon")),
                    ToolUseContext.of("/tmp", "s1"));
            assertTrue(result.isError());
            assertTrue(result.content().contains("Invalid value"));
        }

        @Test
        @DisplayName("1.7 set 操作 — 'default' 重置")
        void setDefault() {
            // 先修改
            tool.call(ToolInput.from(Map.of("action", "set", "key", "theme", "value", "dark")),
                    ToolUseContext.of("/tmp", "s1"));
            assertEquals("dark", tool.getValue("theme"));

            // 重置
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("action", "set", "key", "theme", "value", "default")),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("reset to default"));
            assertEquals("system", tool.getValue("theme"));
        }

        @Test
        @DisplayName("1.8 set 操作 — boolean 类型强制")
        void setBooleanCoerce() {
            tool.call(ToolInput.from(Map.of("action", "set", "key", "autoCompact", "value", "false")),
                    ToolUseContext.of("/tmp", "s1"));
            assertEquals(false, tool.getValue("autoCompact"));
        }

        @Test
        @DisplayName("1.9 set 操作 — integer 类型强制")
        void setIntegerCoerce() {
            tool.call(ToolInput.from(Map.of("action", "set", "key", "maxTokens", "value", "4096")),
                    ToolUseContext.of("/tmp", "s1"));
            assertEquals(4096, tool.getValue("maxTokens"));
        }

        @Test
        @DisplayName("1.10 未知操作返回错误")
        void unknownAction() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("action", "delete")),
                    ToolUseContext.of("/tmp", "s1"));
            assertTrue(result.isError());
            assertTrue(result.content().contains("Unknown action"));
        }

        @Test
        @DisplayName("1.11 并发安全 — get 安全, set 不安全")
        void concurrencySafety() {
            assertTrue(tool.isConcurrencySafe(ToolInput.from(Map.of("action", "get"))));
            assertTrue(tool.isConcurrencySafe(ToolInput.from(Map.of("action", "list"))));
            assertFalse(tool.isConcurrencySafe(ToolInput.from(Map.of("action", "set"))));
        }
    }

    // ===== 2. SendMessageTool 测试 =====

    @Nested
    @DisplayName("2. SendMessageTool")
    class SendMessageTests {

        private SendMessageTool tool;

        @BeforeEach
        void setUp() {
            tool = new SendMessageTool(mock(SimpMessagingTemplate.class));
        }

        @Test
        @DisplayName("2.1 工具名称和标记")
        void nameAndFlags() {
            assertEquals("SendMessage", tool.getName());
            assertEquals("agent", tool.getGroup());
            assertTrue(tool.isConcurrencySafe(ToolInput.from(Map.of())));
        }

        @Test
        @DisplayName("2.2 单播 — 目标不存在返回错误")
        void unicastNotFound() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("to", "agent-1", "message", "hello")),
                    ToolUseContext.of("/tmp", "s1"));
            assertTrue(result.isError());
            assertTrue(result.content().contains("Agent not found"));
        }

        @Test
        @DisplayName("2.3 单播 — 已注册代理成功发送")
        void unicastSuccess() {
            tool.registerAgent("agent-1");

            ToolResult result = tool.call(
                    ToolInput.from(Map.of("to", "agent-1", "message", "hello")),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("Message sent to agent-1"));

            // 验证邮箱中有消息
            List<SendMessageTool.MailboxMessage> messages = tool.readMailbox("agent-1");
            assertEquals(1, messages.size());
            assertEquals("hello", messages.get(0).message());
        }

        @Test
        @DisplayName("2.4 广播 — 发送给所有已注册代理")
        void broadcast() {
            tool.registerAgent("agent-A");
            tool.registerAgent("agent-B");

            ToolResult result = tool.call(
                    ToolInput.from(Map.of("to", "*", "message", "broadcast msg")),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("broadcast to 2 agents"));

            assertEquals(1, tool.readMailbox("agent-A").size());
            assertEquals(1, tool.readMailbox("agent-B").size());
        }

        @Test
        @DisplayName("2.5 广播 — 无已注册代理")
        void broadcastEmpty() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("to", "*", "message", "empty broadcast")),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("broadcast to 0 agents"));
        }

        @Test
        @DisplayName("2.6 注销代理后无法发送")
        void unregister() {
            tool.registerAgent("agent-X");
            tool.unregisterAgent("agent-X");

            ToolResult result = tool.call(
                    ToolInput.from(Map.of("to", "agent-X", "message", "hi")),
                    ToolUseContext.of("/tmp", "s1"));
            assertTrue(result.isError());
        }
    }

    // ===== 3. SyntheticOutputTool 测试 =====

    @Nested
    @DisplayName("3. SyntheticOutputTool")
    class SyntheticOutputTests {

        private SyntheticOutputTool tool;

        @BeforeEach
        void setUp() {
            tool = new SyntheticOutputTool();
        }

        @Test
        @DisplayName("3.1 工具名称和标记")
        void nameAndFlags() {
            assertEquals("SyntheticOutput", tool.getName());
            assertEquals("config", tool.getGroup());
            assertTrue(tool.isReadOnly(ToolInput.from(Map.of())));
            assertTrue(tool.isConcurrencySafe(ToolInput.from(Map.of())));
        }

        @Test
        @DisplayName("3.2 无 Schema — 仍接受数据")
        void noSchema() {
            assertNull(tool.getCurrentSchema());
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("key", "value")),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("Structured output provided"));
            assertNotNull(result.metadata().get("structured_output"));
        }

        @Test
        @DisplayName("3.3 有 Schema — 成功返回")
        void withSchema() {
            tool.setSchema(Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "message", Map.of("type", "string")
                    )));

            ToolResult result = tool.call(
                    ToolInput.from(Map.of("message", "hello")),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());

            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) result.metadata().get("structured_output");
            assertEquals("hello", output.get("message"));
        }

        @Test
        @DisplayName("3.4 空数据返回错误")
        void emptyData() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of()),
                    ToolUseContext.of("/tmp", "s1"));
            assertTrue(result.isError());
            assertTrue(result.content().contains("Empty"));
        }

        @Test
        @DisplayName("3.5 setSchema/clearSchema 生命周期")
        void schemaLifecycle() {
            assertNull(tool.getCurrentSchema());

            Map<String, Object> schema = Map.of("type", "object");
            tool.setSchema(schema);
            assertEquals(schema, tool.getCurrentSchema());

            tool.clearSchema();
            assertNull(tool.getCurrentSchema());
        }

        @Test
        @DisplayName("3.6 动态 InputSchema 跟随 currentSchema")
        void dynamicInputSchema() {
            // 无 Schema — 默认
            Map<String, Object> defaultSchema = tool.getInputSchema();
            assertTrue(defaultSchema.containsKey("additionalProperties"));

            // 设置 Schema
            Map<String, Object> custom = Map.of("type", "object", "properties", Map.of());
            tool.setSchema(custom);
            assertEquals(custom, tool.getInputSchema());
        }
    }
}
