package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ReactiveCompact 条带剥离策略 — 定位并替换最大消息块。
 * 当 API 返回 413 时，找到最占 token 的消息块进行压缩替换。
 */
public class ReactiveStripStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReactiveStripStrategy.class);
    private final TokenCounter tokenCounter;

    public ReactiveStripStrategy(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    /**
     * 找到最大的消息块并替换为摘要占位符。
     *
     * @param messages 消息列表
     * @param protectedTailCount 受保护的尾部消息数量（不被剥离）
     * @return 处理后的消息列表
     */
    public List<Message> stripLargestBlock(List<Message> messages, int protectedTailCount) {
        if (messages == null || messages.isEmpty()) return messages != null ? messages : List.of();
        if (protectedTailCount < 0) protectedTailCount = 0;

        int strippableEnd = messages.size() - protectedTailCount;
        if (strippableEnd <= 1) return messages;

        // 找到可剥离范围的起始位置（跳过SystemMessage）
        int startIdx = 0;
        for (int i = 0; i < Math.min(messages.size(), 3); i++) {
            if (messages.get(i) instanceof Message.SystemMessage) {
                startIdx = i + 1;
                break;
            }
        }

        if (startIdx >= strippableEnd) {
            log.debug("No strippable messages: startIdx={} >= strippableEnd={}", startIdx, strippableEnd);
            return messages;
        }

        // 找到token最大的消息
        int maxIndex = -1;
        int maxTokens = 0;
        for (int i = startIdx; i < strippableEnd; i++) {
            var msg = messages.get(i);
            if (msg == null) continue;
            int tokens = tokenCounter.estimateTokens(List.of(msg));
            if (tokens > maxTokens) {
                maxTokens = tokens;
                maxIndex = i;
            }
        }

        if (maxIndex < 0) return messages;

        // 替换为摘要占位
        List<Message> result = new ArrayList<>(messages);
        result.set(maxIndex, new Message.UserMessage(
                UUID.randomUUID().toString(), Instant.now(),
                List.of(new ContentBlock.TextBlock(
                        "[Content compressed: original " + maxTokens + " tokens stripped for context recovery]")),
                null, null));

        log.info("Stripped largest block at index {} ({} tokens) for context recovery", maxIndex, maxTokens);
        return result;
    }
}
