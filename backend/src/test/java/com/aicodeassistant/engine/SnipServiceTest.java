package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SnipService 单元测试 — 覆盖工具结果截断的核心场景。
 * <p>
 * Given-When-Then 模式，JUnit 5。
 */
@DisplayName("SnipService 单元测试")
class SnipServiceTest {

    private SnipService snipService;

    @BeforeEach
    void setUp() {
        snipService = new SnipService();
    }

    // ═══════════════════════════════════════════════════════════════
    // 1. snipIfNeeded(String, int) — 单内容截断
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("snipIfNeeded - 单工具结果截断")
    class SnipIfNeeded {

        @Test
        @DisplayName("短内容不触发截断 — 原样返回")
        void shouldPreserveContentBelowThreshold() {
            // Given: 内容长度 < 预算
            String content = "short output";
            // When
            String result = snipService.snipIfNeeded(content, 1000);
            // Then: 原样返回
            assertEquals(content, result);
        }

        @Test
        @DisplayName("超长内容正确截断并保留首尾")
        void shouldSnipContentExceedingBudget() {
            // Given: 1000 字符，预算 200
            String content = "H".repeat(500) + "T".repeat(500);
            // When
            String result = snipService.snipIfNeeded(content, 200);
            // Then
            assertTrue(result.length() <= 300, "Snipped result should be within budget (with marker)");
            assertTrue(result.startsWith("H"), "Should preserve head");
            assertTrue(result.contains("truncated"), "Should contain truncation marker");
        }

        @Test
        @DisplayName("null 内容安全返回 null")
        void shouldHandleNullContent() {
            assertNull(snipService.snipIfNeeded(null, 1000));
        }

        @Test
        @DisplayName("空字符串不截断")
        void shouldHandleEmptyContent() {
            assertEquals("", snipService.snipIfNeeded("", 1000));
        }

        @Test
        @DisplayName("预算为 0 返回空字符串")
        void shouldReturnEmptyForZeroBudget() {
            String result = snipService.snipIfNeeded("some content", 0);
            assertEquals("", result);
        }

        @Test
        @DisplayName("截断后保留关键信息（首尾可见）")
        void shouldPreserveHeadAndTail() {
            // Given: 明确标记首尾
            String head = "HEAD_MARKER_START";
            String middle = "x".repeat(10000);
            String tail = "TAIL_MARKER_END";
            String content = head + middle + tail;

            // When: 预算 500
            String result = snipService.snipIfNeeded(content, 500);

            // Then: 首部和尾部标记应可见
            assertTrue(result.startsWith("HEAD_MARKER"), "Head should be preserved");
            assertTrue(result.endsWith("TAIL_MARKER_END"), "Tail should be preserved");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. snipToolResults(List<Message>, int) — 消息列表截断
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("snipToolResults - 消息列表工具结果截断")
    class SnipToolResults {

        @Test
        @DisplayName("短工具结果不截断")
        void shouldPreserveShortToolResults() {
            // Given
            Message msg = new Message.UserMessage("u1", Instant.now(),
                    List.of(), "short result", null);

            // When
            List<Message> result = snipService.snipToolResults(List.of(msg), 1000);

            // Then
            assertEquals(1, result.size());
            Message.UserMessage um = (Message.UserMessage) result.getFirst();
            assertEquals("short result", um.toolUseResult());
        }

        @Test
        @DisplayName("超长工具结果被截断")
        void shouldSnipLongToolResults() {
            // Given: 5000 字符结果，预算 200
            String longResult = "x".repeat(5000);
            Message msg = new Message.UserMessage("u1", Instant.now(),
                    List.of(), longResult, null);

            // When
            List<Message> result = snipService.snipToolResults(List.of(msg), 200);

            // Then
            Message.UserMessage um = (Message.UserMessage) result.getFirst();
            assertNotEquals(longResult, um.toolUseResult(), "Should be snipped");
            assertTrue(um.toolUseResult().length() < longResult.length(),
                    "Snipped result should be shorter");
        }

        @Test
        @DisplayName("非 UserMessage 和无 toolUseResult 的消息不受影响")
        void shouldNotAffectNonToolMessages() {
            // Given
            Message sys = new Message.SystemMessage("s1", Instant.now(), "hello", null);
            Message user = new Message.UserMessage("u1", Instant.now(),
                    List.of(new ContentBlock.TextBlock("text")), null, null);

            // When
            List<Message> result = snipService.snipToolResults(List.of(sys, user), 100);

            // Then: 原样返回
            assertEquals(2, result.size());
            assertSame(sys, result.get(0));
            assertSame(user, result.get(1));
        }
    }
}
