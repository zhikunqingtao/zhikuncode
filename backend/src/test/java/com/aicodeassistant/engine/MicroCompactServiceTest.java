package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * MicroCompactService 单元测试 — 覆盖基于白名单的旧工具结果清除。
 * <p>
 * Given-When-Then 模式，JUnit 5 + Mockito。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MicroCompactService 单元测试")
class MicroCompactServiceTest {

    @Mock
    private TokenCounter tokenCounter;

    private MicroCompactService service;

    @BeforeEach
    void setUp() {
        service = new MicroCompactService(tokenCounter);
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════════

    /** 创建带 ToolUseBlock 的 AssistantMessage */
    private Message.AssistantMessage assistantWithTool(String uuid, String toolUseId, String toolName) {
        return new Message.AssistantMessage(uuid, Instant.now(),
                List.of(new ContentBlock.ToolUseBlock(toolUseId, toolName, null)),
                "end_turn", new Usage(10, 20, 0, 0));
    }

    /** 创建带 toolUseResult 的 UserMessage，通过 sourceToolAssistantUUID 关联 */
    private Message.UserMessage userWithToolResult(String uuid, String toolResult, String sourceAssistantUuid) {
        return new Message.UserMessage(uuid, Instant.now(),
                List.of(), toolResult, sourceAssistantUuid);
    }

    // ═══════════════════════════════════════════════════════════════
    // 测试
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("compactMessages - 消息微压缩")
    class CompactMessages {

        @Test
        @DisplayName("少量消息（全在保护区）不触发压缩")
        void shouldNotCompactWhenAllInProtectedTail() {
            // Given: 3 条消息，protectedTailSize = 5
            List<Message> messages = List.of(
                    new Message.SystemMessage("s1", Instant.now(), "system prompt", null),
                    assistantWithTool("a1", "tu-1", "BashTool"),
                    userWithToolResult("u1", "bash output content", "a1")
            );

            // When
            var result = service.compactMessages(messages, 5);

            // Then: 无压缩
            assertEquals(3, result.messages().size());
            assertEquals(0, result.tokensFreed());
        }

        @Test
        @DisplayName("保护区外的白名单工具结果被清除")
        void shouldCompactOldCompactableToolResults() {
            // Given: 多条消息，前面的在保护区外
            when(tokenCounter.estimateTokens(anyString())).thenReturn(100);

            List<Message> messages = new ArrayList<>();
            // 旧消息（保护区外）
            messages.add(assistantWithTool("a1", "tu-1", "BashTool"));
            messages.add(userWithToolResult("u1", "old bash output that should be cleared", "a1"));
            messages.add(assistantWithTool("a2", "tu-2", "FileReadTool"));
            messages.add(userWithToolResult("u2", "old file read output", "a2"));
            // 新消息（保护区内，protectedTailSize=2）
            messages.add(assistantWithTool("a3", "tu-3", "BashTool"));
            messages.add(userWithToolResult("u3", "recent bash output should be kept", "a3"));

            // When: protectedTailSize=2, 最后 2 条在保护区
            var result = service.compactMessages(messages, 2);

            // Then: 旧的工具结果被清除
            assertEquals(6, result.messages().size());
            assertTrue(result.tokensFreed() > 0, "Should have freed tokens");

            // 验证保护区内的消息未被清除
            Message.UserMessage lastUser = (Message.UserMessage) result.messages().get(5);
            assertEquals("recent bash output should be kept", lastUser.toolUseResult());
        }

        @Test
        @DisplayName("非白名单工具的结果不被清除")
        void shouldNotCompactNonCompactableTools() {
            // Given: 使用非白名单工具
            List<Message> messages = new ArrayList<>();
            messages.add(assistantWithTool("a1", "tu-1", "CustomTool"));
            messages.add(userWithToolResult("u1", "custom tool output should be kept", "a1"));
            // 填充足够消息让旧消息在保护区外
            for (int i = 2; i <= 7; i++) {
                messages.add(new Message.SystemMessage("s" + i, Instant.now(), "filler " + i, null));
            }

            // When
            var result = service.compactMessages(messages, 3);

            // Then: CustomTool 的结果应保留
            Message.UserMessage userMsg = (Message.UserMessage) result.messages().get(1);
            assertEquals("custom tool output should be kept", userMsg.toolUseResult());
            assertEquals(0, result.tokensFreed());
        }

        @Test
        @DisplayName("已清除的消息不重复清除")
        void shouldNotReCompactAlreadyClearedMessages() {
            // Given: 已清除的消息
            List<Message> messages = new ArrayList<>();
            messages.add(assistantWithTool("a1", "tu-1", "BashTool"));
            messages.add(new Message.UserMessage("u1", Instant.now(),
                    List.of(), "[Old tool result content cleared]", "a1"));
            // 填充消息
            for (int i = 2; i <= 7; i++) {
                messages.add(new Message.SystemMessage("s" + i, Instant.now(), "filler", null));
            }

            // When
            var result = service.compactMessages(messages, 3);

            // Then: 不释放额外 token
            assertEquals(0, result.tokensFreed());
        }
    }

    @Nested
    @DisplayName("createBoundaryMessage - 压缩边界消息")
    class BoundaryMessage {

        @Test
        @DisplayName("创建边界消息包含压缩计数")
        void shouldCreateBoundaryMessageWithCount() {
            // When
            Message msg = service.createBoundaryMessage(5);

            // Then
            assertInstanceOf(Message.UserMessage.class, msg);
            Message.UserMessage userMsg = (Message.UserMessage) msg;
            assertNotNull(userMsg.content());
            assertFalse(userMsg.content().isEmpty());
            ContentBlock.TextBlock textBlock = (ContentBlock.TextBlock) userMsg.content().getFirst();
            assertTrue(textBlock.text().contains("5"), "Should contain cleared count");
            assertTrue(textBlock.text().contains("compacted"), "Should mention compaction");
        }
    }
}
