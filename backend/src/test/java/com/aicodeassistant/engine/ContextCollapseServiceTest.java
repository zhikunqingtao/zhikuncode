package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextCollapseService 单元测试 — 覆盖上下文折叠的核心场景。
 * <p>
 * Given-When-Then 模式，JUnit 5。
 */
@DisplayName("ContextCollapseService 单元测试")
class ContextCollapseServiceTest {

    private ContextCollapseService service;

    @BeforeEach
    void setUp() {
        service = new ContextCollapseService();
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════════

    private Message.UserMessage userWithToolResult(String uuid, String toolResult) {
        return new Message.UserMessage(uuid, Instant.now(), List.of(), toolResult, null);
    }

    private Message.AssistantMessage assistantWithText(String uuid, String text) {
        return new Message.AssistantMessage(uuid, Instant.now(),
                List.of(new ContentBlock.TextBlock(text)),
                "end_turn", new Usage(10, 20, 0, 0));
    }

    // ═══════════════════════════════════════════════════════════════
    // 测试
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("collapseMessages - 上下文折叠")
    class CollapseMessages {

        @Test
        @DisplayName("消息数 <= protectedTail 时不折叠")
        void shouldNotCollapseWhenWithinProtectedTail() {
            // Given: 3 条消息
            List<Message> messages = List.of(
                    new Message.SystemMessage("s1", Instant.now(), "prompt", null),
                    assistantWithText("a1", "short reply"),
                    userWithToolResult("u1", "short result")
            );

            // When: protectedTail=6
            var result = service.collapseMessages(messages, 6);

            // Then: 不折叠
            assertEquals(3, result.messages().size());
            assertEquals(0, result.collapsedCount());
            assertEquals(0, result.estimatedCharsFreed());
        }

        @Test
        @DisplayName("null 消息列表安全返回空列表")
        void shouldHandleNullMessages() {
            var result = service.collapseMessages(null, 6);
            assertNotNull(result.messages());
            assertTrue(result.messages().isEmpty());
        }

        @Test
        @DisplayName("长工具结果在保护区外被折叠为 [collapsed]")
        void shouldCollapseOldLongToolResults() {
            // Given: 大量消息，前面的工具结果较长
            List<Message> messages = new ArrayList<>();
            // 旧消息（保护区外）— 长工具结果(> 50 chars)
            messages.add(userWithToolResult("u1", "x".repeat(200)));
            messages.add(userWithToolResult("u2", "y".repeat(300)));
            // 填充到保护区
            for (int i = 0; i < 8; i++) {
                messages.add(new Message.SystemMessage("s" + i, Instant.now(), "filler", null));
            }

            // When: protectedTail=6 (默认)
            var result = service.collapseMessages(messages);

            // Then: 旧消息被折叠
            assertTrue(result.collapsedCount() > 0, "Should have collapsed some messages");
            assertTrue(result.estimatedCharsFreed() > 0, "Should have freed chars");

            // 验证折叠后的内容
            Message.UserMessage collapsed = (Message.UserMessage) result.messages().get(0);
            assertEquals("[collapsed]", collapsed.toolUseResult());
        }

        @Test
        @DisplayName("短工具结果(<=50 chars)不折叠")
        void shouldNotCollapseShortToolResults() {
            // Given
            List<Message> messages = new ArrayList<>();
            messages.add(userWithToolResult("u1", "short")); // 5 chars, <= 50
            for (int i = 0; i < 8; i++) {
                messages.add(new Message.SystemMessage("s" + i, Instant.now(), "filler", null));
            }

            // When
            var result = service.collapseMessages(messages);

            // Then: 短结果不折叠
            Message.UserMessage um = (Message.UserMessage) result.messages().get(0);
            assertEquals("short", um.toolUseResult());
        }

        @Test
        @DisplayName("保护区内的消息不受折叠影响")
        void shouldPreserveProtectedTailMessages() {
            // Given
            List<Message> messages = new ArrayList<>();
            // 旧消息
            for (int i = 0; i < 4; i++) {
                messages.add(new Message.SystemMessage("s" + i, Instant.now(), "old", null));
            }
            // 保护区消息：包含长工具结果
            String longToolResult = "important_recent_output_" + "z".repeat(200);
            messages.add(userWithToolResult("u-tail", longToolResult));
            for (int i = 0; i < 5; i++) {
                messages.add(new Message.SystemMessage("st" + i, Instant.now(), "tail", null));
            }

            // When: protectedTail=6
            var result = service.collapseMessages(messages, 6);

            // Then: 保护区的工具结果保留
            // 最后 6 条在保护区(index 4-9)
            Message.UserMessage tailUser = (Message.UserMessage) result.messages().get(4);
            assertEquals(longToolResult, tailUser.toolUseResult());
        }

        @Test
        @DisplayName("助手消息中的超长文本被截断")
        void shouldTruncateLongAssistantText() {
            // Given: 超长助手消息(> 2000 chars)
            List<Message> messages = new ArrayList<>();
            String longText = "x".repeat(5000);
            messages.add(assistantWithText("a1", longText));
            // 填充到保护区外
            for (int i = 0; i < 8; i++) {
                messages.add(new Message.SystemMessage("s" + i, Instant.now(), "filler", null));
            }

            // When
            var result = service.collapseMessages(messages);

            // Then: 长文本被截断
            Message.AssistantMessage am = (Message.AssistantMessage) result.messages().get(0);
            ContentBlock.TextBlock tb = (ContentBlock.TextBlock) am.content().getFirst();
            assertTrue(tb.text().length() < longText.length(),
                    "Truncated text should be shorter than original");
            assertTrue(tb.text().contains("collapsed"), "Should contain collapsed marker");
        }

        @Test
        @DisplayName("系统消息和纯用户消息不受折叠影响")
        void shouldNotCollapseSystemOrPlainUserMessages() {
            // Given
            List<Message> messages = new ArrayList<>();
            Message sys = new Message.SystemMessage("s1", Instant.now(), "system prompt content", null);
            Message user = new Message.UserMessage("u1", Instant.now(),
                    List.of(new ContentBlock.TextBlock("user question")), null, null);
            messages.add(sys);
            messages.add(user);
            for (int i = 0; i < 8; i++) {
                messages.add(new Message.SystemMessage("s" + i, Instant.now(), "filler", null));
            }

            // When
            var result = service.collapseMessages(messages);

            // Then
            assertSame(sys, result.messages().get(0));
            assertSame(user, result.messages().get(1));
        }
    }

    @Nested
    @DisplayName("collapseMessages(messages) - 默认参数重载")
    class DefaultOverload {

        @Test
        @DisplayName("无参数重载使用默认保护尾部数")
        void shouldUseDefaultProtectedTail() {
            // Given: 足够多的消息
            List<Message> messages = new ArrayList<>();
            messages.add(userWithToolResult("u1", "a".repeat(200)));
            for (int i = 0; i < 10; i++) {
                messages.add(new Message.SystemMessage("s" + i, Instant.now(), "msg", null));
            }

            // When: 使用默认重载
            var result = service.collapseMessages(messages);

            // Then: 应正常工作
            assertNotNull(result);
            assertEquals(messages.size(), result.messages().size());
        }
    }
}
