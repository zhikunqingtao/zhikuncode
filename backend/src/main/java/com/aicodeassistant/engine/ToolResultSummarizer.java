package com.aicodeassistant.engine;

import com.aicodeassistant.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具结果摘要器 — 压缩过大的工具结果，防止上下文溢出。
 * <p>
 * 三级策略：
 * 1. 结果 ≤ SOFT_LIMIT → 保持原样
 * 2. SOFT_LIMIT < 结果 ≤ HARD_LIMIT → 截断 + 尾部摘要提示
 * 3. 结果 > HARD_LIMIT → 硬截断（只保留头尾）
 * <p>
 * 对照原版 Function Result Clearing + Summarize Tool Results 机制。
 *
 * @see <a href="SPEC §3.1.5">CompactService 压缩算法</a>
 */
@Component
public class ToolResultSummarizer {

    private static final Logger log = LoggerFactory.getLogger(ToolResultSummarizer.class);

    /** 软限制：超过此字符数时进行截断（约 5000 token） */
    private static final int SOFT_LIMIT_CHARS = 18_000;

    /** 硬限制：超过此字符数时触发硬截断（约 15000 token） */
    private static final int HARD_LIMIT_CHARS = 50_000;

    /** 截断后保留的头部字符数 */
    private static final int TRUNCATE_HEAD_CHARS = 12_000;

    /** 截断后保留的尾部字符数 */
    private static final int TRUNCATE_TAIL_CHARS = 3_000;

    /** 旧轮次清理阈值：超过此轮次数的工具结果将被标记为可清理 */
    private static final int STALE_TURN_THRESHOLD = 8;

    private final TokenCounter tokenCounter;

    public ToolResultSummarizer(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    /**
     * 处理当前轮次的工具结果 — 截断过大的结果。
     *
     * @param messages    当前消息列表（包含最新的工具结果）
     * @param currentTurn 当前轮次
     * @return 处理后的消息列表
     */
    public List<Message> processToolResults(List<Message> messages, int currentTurn) {
        if (messages == null || messages.isEmpty()) return messages;

        List<Message> processed = new ArrayList<>(messages.size());
        boolean changed = false;
        for (Message msg : messages) {
            if (msg instanceof Message.UserMessage userMsg
                    && userMsg.toolUseResult() != null
                    && userMsg.toolUseResult().length() > SOFT_LIMIT_CHARS) {
                processed.add(truncateToolResult(userMsg));
                changed = true;
            } else {
                processed.add(msg);
            }
        }
        return changed ? processed : messages;
    }

    /**
     * 清理旧轮次的工具结果 — 替换为占位标记。
     * 在上下文接近限制时由 CompactService / tryAutoCompact 调用。
     *
     * @param messages    消息列表
     * @param currentTurn 当前轮次
     * @return 清理后的消息列表
     */
    public List<Message> clearStaleToolResults(List<Message> messages, int currentTurn) {
        if (messages == null || messages.isEmpty()) return messages;

        List<Message> cleaned = new ArrayList<>(messages.size());
        boolean changed = false;
        int messageIndex = 0;
        for (Message msg : messages) {
            if (msg instanceof Message.UserMessage userMsg
                    && userMsg.toolUseResult() != null
                    && (currentTurn - estimateTurn(messageIndex, messages.size(), currentTurn))
                        > STALE_TURN_THRESHOLD) {
                // 替换旧工具结果为清理标记
                cleaned.add(new Message.UserMessage(
                        userMsg.uuid(), userMsg.timestamp(),
                        userMsg.content(),
                        "[tool result cleared — write down important information from tool " +
                        "results to ensure you don't lose it]",
                        userMsg.sourceToolAssistantUUID()));
                changed = true;
                log.debug("Cleared stale tool result at message index {}", messageIndex);
            } else {
                cleaned.add(msg);
            }
            messageIndex++;
        }
        return changed ? cleaned : messages;
    }

    /**
     * 截断过大的工具结果，保留头尾和截断提示。
     */
    private Message.UserMessage truncateToolResult(Message.UserMessage msg) {
        String result = msg.toolUseResult();
        if (result.length() <= SOFT_LIMIT_CHARS) return msg;

        String truncated;
        if (result.length() > HARD_LIMIT_CHARS) {
            // 硬截断：只保留头部 + 尾部
            truncated = result.substring(0, TRUNCATE_HEAD_CHARS)
                    + "\n\n... [TRUNCATED: result was "
                    + result.length() + " chars, showing first "
                    + TRUNCATE_HEAD_CHARS + " chars. "
                    + "Write down any important information you need.] ...\n\n"
                    + result.substring(result.length() - TRUNCATE_TAIL_CHARS);
        } else {
            // 软截断：保留头尾
            truncated = result.substring(0, TRUNCATE_HEAD_CHARS)
                    + "\n\n... [TRUNCATED: "
                    + (result.length() - TRUNCATE_HEAD_CHARS - TRUNCATE_TAIL_CHARS)
                    + " chars omitted] ...\n\n"
                    + result.substring(result.length() - TRUNCATE_TAIL_CHARS);
        }

        log.info("Truncated tool result: {} → {} chars", result.length(), truncated.length());
        return new Message.UserMessage(
                msg.uuid(), msg.timestamp(), msg.content(), truncated,
                msg.sourceToolAssistantUUID());
    }

    /**
     * 估算消息所在的轮次（简单启发式：按消息索引和总轮次比例）。
     */
    private int estimateTurn(int messageIndex, int totalMessages, int currentTurn) {
        if (totalMessages <= 0) return currentTurn;
        return (int) ((double) messageIndex / totalMessages * currentTurn);
    }

    /**
     * 检查是否需要注入摘要提示（当上下文接近限制时）。
     *
     * @param messages     当前消息列表
     * @param contextLimit 上下文 token 限制
     * @return true 如果需要提示模型记录关键信息
     */
    public boolean shouldInjectSummarizeHint(List<Message> messages, int contextLimit) {
        if (contextLimit <= 0) return false;
        int currentTokens = tokenCounter.estimateTokens(messages);
        // 当使用了 70% 以上的上下文时，提示模型记录关键信息
        return currentTokens > contextLimit * 0.7;
    }
}
