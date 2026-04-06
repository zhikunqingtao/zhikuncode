package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token 计数器 — 估算消息列表的 token 数量。
 * <p>
 * P0 阶段使用字符数 / 4 的启发式估算，后续可对接 tiktoken 等精确计算。
 *
 * @see <a href="SPEC §3.1.5">CompactService 压缩算法</a>
 */
@Component
public class TokenCounter {

    /** 平均每个 token 对应的字符数 (英文约 4，中文约 2) */
    private static final double CHARS_PER_TOKEN = 3.5;

    /**
     * 估算消息列表的总 token 数。
     */
    public int estimateTokens(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return 0;

        int totalChars = 0;
        for (Message msg : messages) {
            totalChars += estimateMessageChars(msg);
        }
        // 消息边界开销: 每条消息约 4 token
        return (int) (totalChars / CHARS_PER_TOKEN) + messages.size() * 4;
    }

    /**
     * 估算单条文本的 token 数。
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) (text.length() / CHARS_PER_TOKEN);
    }

    /**
     * 估算单条消息的字符数。
     */
    private int estimateMessageChars(Message message) {
        return switch (message) {
            case Message.UserMessage user -> {
                int chars = 0;
                if (user.content() != null) {
                    for (ContentBlock block : user.content()) {
                        chars += estimateBlockChars(block);
                    }
                }
                if (user.toolUseResult() != null) {
                    chars += user.toolUseResult().length();
                }
                yield chars;
            }
            case Message.AssistantMessage assistant -> {
                int chars = 0;
                if (assistant.content() != null) {
                    for (ContentBlock block : assistant.content()) {
                        chars += estimateBlockChars(block);
                    }
                }
                yield chars;
            }
            case Message.SystemMessage system -> {
                yield system.content() != null ? system.content().length() : 0;
            }
        };
    }

    /**
     * 估算内容块的字符数。
     */
    private int estimateBlockChars(ContentBlock block) {
        return switch (block) {
            case ContentBlock.TextBlock text -> text.text() != null ? text.text().length() : 0;
            case ContentBlock.ToolUseBlock toolUse ->
                    (toolUse.name() != null ? toolUse.name().length() : 0)
                    + (toolUse.input() != null ? toolUse.input().toString().length() : 0)
                    + 20; // JSON 结构开销
            case ContentBlock.ToolResultBlock result ->
                    (result.content() != null ? result.content().length() : 0) + 10;
            case ContentBlock.ImageBlock image -> 85; // 图片固定 token 估算
            case ContentBlock.ThinkingBlock thinking ->
                    thinking.thinking() != null ? thinking.thinking().length() : 0;
            case ContentBlock.RedactedThinkingBlock redacted -> 10;
        };
    }
}
