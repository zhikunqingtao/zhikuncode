package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Context Collapse 精细化服务 — 保留消息骨架(role+toolUseId)，清空内容。
 * <p>
 * 在 Level 2 (AutoCompact) 之前插入，作为轻量级上下文瘦身手段。
 * 比 MicroCompact 更激进：不仅清除工具结果，还骨架化旧的助手消息。
 * <p>
 * 策略：
 * - 保护尾部 N 条消息不被处理（保留最近上下文）
 * - 工具结果消息：保留 toolUseId，内容替换为 "[collapsed]"
 * - 助手消息中的文本块：超长时截断保留摘要
 * - 系统消息和用户消息不受影响
 *
 * @see <a href="SPEC §11">Context Collapse 精细化</a>
 */
@Service
public class ContextCollapseService {

    private static final Logger log = LoggerFactory.getLogger(ContextCollapseService.class);

    /** 保护尾部消息数，尾部 N 条消息不参与折叠，保留最近上下文完整性 */
    private final int defaultProtectedTail;

    /** 助手消息文本截断阈值（字符数），超过此长度的文本块将被截断 */
    private final int textTruncateThreshold;

    /** 截断后保留的前缀字符数 */
    private final int textTruncateKeep;

    public ContextCollapseService(
            @Value("${zhiku.context.collapse.protected-tail:6}") int defaultProtectedTail,
            @Value("${zhiku.context.collapse.threshold:2000}") int textTruncateThreshold,
            @Value("${zhiku.context.collapse.keep:500}") int textTruncateKeep) {
        this.defaultProtectedTail = defaultProtectedTail;
        this.textTruncateThreshold = textTruncateThreshold;
        this.textTruncateKeep = textTruncateKeep;
    }

    /**
     * 折叠消息列表 — 保留骨架，清空旧内容。
     *
     * @param messages      消息列表
     * @param protectedTail 保护尾部消息数
     * @return 折叠后的消息列表 + 统计
     */
    public CollapseResult collapseMessages(List<Message> messages, int protectedTail) {
        if (messages == null || messages.size() <= protectedTail) {
            return new CollapseResult(messages != null ? messages : List.of(), 0, 0);
        }

        int tail = Math.max(protectedTail, defaultProtectedTail);
        int collapseEnd = messages.size() - tail;

        List<Message> result = new ArrayList<>(messages.size());
        int collapsedCount = 0;
        int estimatedCharsFreed = 0;

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);

            if (i >= collapseEnd) {
                // 保护区：原样保留
                result.add(msg);
                continue;
            }

            if (msg instanceof Message.UserMessage userMsg && userMsg.toolUseResult() != null) {
                // 工具结果消息：保留 toolUseId，内容替换为 [collapsed]
                String originalContent = userMsg.toolUseResult();
                if (originalContent.length() > 50) {
                    Message.UserMessage collapsed = new Message.UserMessage(
                            userMsg.uuid(), userMsg.timestamp(),
                            userMsg.content(), "[collapsed]", userMsg.sourceToolAssistantUUID());
                    result.add(collapsed);
                    estimatedCharsFreed += originalContent.length() - 11;
                    collapsedCount++;
                } else {
                    result.add(msg);
                }
            } else if (msg instanceof Message.AssistantMessage assistant) {
                // 助手消息：长文本截断
                if (assistant.content() != null && hasLongText(assistant.content())) {
                    List<ContentBlock> truncatedBlocks = truncateBlocks(assistant.content());
                    int freed = estimateContentLength(assistant.content()) - estimateContentLength(truncatedBlocks);
                    Message.AssistantMessage collapsed = new Message.AssistantMessage(
                            assistant.uuid(), assistant.timestamp(),
                            truncatedBlocks, assistant.stopReason(), assistant.usage());
                    result.add(collapsed);
                    if (freed > 0) {
                        estimatedCharsFreed += freed;
                        collapsedCount++;
                    }
                } else {
                    result.add(msg);
                }
            } else {
                // 用户消息/系统消息：原样保留
                result.add(msg);
            }
        }

        if (collapsedCount > 0) {
            log.info("ContextCollapse: collapsed {} messages, ~{} chars freed",
                    collapsedCount, estimatedCharsFreed);
        }

        return new CollapseResult(result, collapsedCount, estimatedCharsFreed);
    }

    /**
     * 使用默认保护尾部数折叠。
     */
    public CollapseResult collapseMessages(List<Message> messages) {
        return collapseMessages(messages, defaultProtectedTail);
    }

    // ── 内部方法 ──────────────────────────────────────────────

    private boolean hasLongText(List<ContentBlock> blocks) {
        return blocks.stream()
                .anyMatch(b -> b instanceof ContentBlock.TextBlock t
                        && t.text() != null && t.text().length() > textTruncateThreshold);
    }

    private List<ContentBlock> truncateBlocks(List<ContentBlock> blocks) {
        return blocks.stream()
                .map(block -> {
                    if (block instanceof ContentBlock.TextBlock t
                            && t.text() != null && t.text().length() > textTruncateThreshold) {
                        String truncated = t.text().substring(0, textTruncateKeep)
                                + "\n...[collapsed: " + t.text().length() + " chars]";
                        return (ContentBlock) new ContentBlock.TextBlock(truncated);
                    }
                    return block;
                })
                .toList();
    }

    private int estimateContentLength(List<ContentBlock> blocks) {
        if (blocks == null) return 0;
        return blocks.stream()
                .mapToInt(b -> {
                    if (b instanceof ContentBlock.TextBlock t) return t.text() != null ? t.text().length() : 0;
                    if (b instanceof ContentBlock.ToolUseBlock tu) return tu.input() != null ? tu.input().toString().length() : 20;
                    return 20;
                })
                .sum();
    }

    // ── 结果 DTO ──────────────────────────────────────────────

    /** 折叠结果 */
    public record CollapseResult(
            List<Message> messages,
            int collapsedCount,
            int estimatedCharsFreed
    ) {}
}
