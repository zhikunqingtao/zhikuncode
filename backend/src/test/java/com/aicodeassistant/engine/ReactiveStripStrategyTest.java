package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

class ReactiveStripStrategyTest {

    private TokenCounter mockTokenCounter;
    private ReactiveStripStrategy strategy;

    @BeforeEach
    void setUp() {
        mockTokenCounter = Mockito.mock(TokenCounter.class);
        strategy = new ReactiveStripStrategy(mockTokenCounter);
    }

    @Test
    @DisplayName("正确找到最大消息并替换")
    void stripLargestBlock_replacesLargestMessage() {
        var msg1 = createUserMessage("short");
        var msg2 = createUserMessage("this is a much longer message with more content");
        var msg3 = createUserMessage("medium message");

        when(mockTokenCounter.estimateTokens(List.of(msg1))).thenReturn(10);
        when(mockTokenCounter.estimateTokens(List.of(msg2))).thenReturn(100);
        when(mockTokenCounter.estimateTokens(List.of(msg3))).thenReturn(30);

        List<Message> messages = new ArrayList<>(List.of(msg1, msg2, msg3));
        List<Message> result = strategy.stripLargestBlock(messages, 0);

        assertEquals(3, result.size());
        // msg2（index 1）应被替换
        assertNotEquals(msg2, result.get(1));
        // 其他消息保持不变
        assertEquals(msg1, result.get(0));
        assertEquals(msg3, result.get(2));
    }

    @Test
    @DisplayName("保护尾部消息不被剥离")
    void stripLargestBlock_protectsTailMessages() {
        var msg1 = createUserMessage("small");
        var msg2 = createUserMessage("medium");
        var msg3 = createUserMessage("largest message in the tail");

        when(mockTokenCounter.estimateTokens(List.of(msg1))).thenReturn(10);
        when(mockTokenCounter.estimateTokens(List.of(msg2))).thenReturn(30);
        when(mockTokenCounter.estimateTokens(List.of(msg3))).thenReturn(100);

        // protectedTailCount=1, msg3不可剥离
        List<Message> result = strategy.stripLargestBlock(List.of(msg1, msg2, msg3), 1);

        // msg2（index 1）应被替换（因为在可剥离范围内最大）
        assertEquals(msg1, result.get(0));
        assertNotEquals(msg2, result.get(1)); // 被替换
        assertEquals(msg3, result.get(2)); // 受保护
    }

    @Test
    @DisplayName("空列表防御")
    void stripLargestBlock_emptyList_returnsEmpty() {
        List<Message> result = strategy.stripLargestBlock(List.of(), 0);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("null列表防御")
    void stripLargestBlock_nullList_returnsEmpty() {
        List<Message> result = strategy.stripLargestBlock(null, 0);
        assertNotNull(result);
    }

    @Test
    @DisplayName("单消息列表不剥离")
    void stripLargestBlock_singleMessage_returnsUnchanged() {
        var msg = createUserMessage("single");
        when(mockTokenCounter.estimateTokens(anyList())).thenReturn(50);

        List<Message> result = strategy.stripLargestBlock(List.of(msg), 0);
        assertEquals(1, result.size());
    }

    private Message.UserMessage createUserMessage(String text) {
        return new Message.UserMessage(
                UUID.randomUUID().toString(),
                Instant.now(),
                List.of(new ContentBlock.TextBlock(text)),
                null, null);
    }
}
