package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

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
 */
@Service
public class ContextCollapseService {

    private static final Logger log = LoggerFactory.getLogger(ContextCollapseService.class);
    private static final int FINGERPRINT_HEX_LENGTH = 16;

    /** 保护尾部消息数，尾部 N 条消息不参与折叠，保留最近上下文完整性 */
    private final int defaultProtectedTail;

    /** 助手消息文本截断阈值（字符数），超过此长度的文本块将被截断 */
    private final int textTruncateThreshold;

    /** 截断后保留的前缀字符数 */
    private final int textTruncateKeep;

    // ★ 新增：渐进折叠默认级别
    private static final List<CollapseLevel> DEFAULT_LEVELS = List.of(
            new CollapseLevel.FullRetention(),      // 尾部 10 条完整保留
            new CollapseLevel.SummaryRetention(),    // 10-30 条摘要保留
            new CollapseLevel.SkeletonRetention()    // 30+ 条骨架化
    );

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

            if (msg instanceof Message.UserMessage userMsg && MessageContentAccessor.legacyToolResult(userMsg) != null) {
                // 工具结果消息：保留 toolUseId，内容替换为 [collapsed]
                String originalContent = MessageContentAccessor.legacyToolResult(userMsg);
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
                        // ★ 修复：Math.min 保护边界，防止 textTruncateKeep > text.length()
                        int keepLen = Math.min(textTruncateKeep, t.text().length());
                        String truncated = t.text().substring(0, keepLen)
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

    // ── 三级渐进折叠 ──────────────────────────────────────────────

    /**
     * 渐进式折叠 — 按消息距尾部距离分三级处理。
     * 关键规则：所有 UserMessage（非 toolUseResult）永远保留原文，
     * 防止模型丢失用户反馈（如"不要用 Redux"）。
     *
     * @param messages 消息列表
     * @param levels   折叠级别列表（可为 null 使用默认）
     * @return 折叠结果
     */
    public CollapseResult progressiveCollapse(List<Message> messages, List<CollapseLevel> levels) {
        long startedAt = System.nanoTime();
        if (messages == null || messages.isEmpty()) {
            logProgressiveDiagnostics("NO_CANDIDATE", 0, 0, 0,
                    0, 0, 0, 0, 0,
                    "empty", "empty", elapsedMicros(startedAt));
            return new CollapseResult(messages != null ? messages : List.of(), 0, 0);
        }
        List<CollapseLevel> sortedLevels = levels != null ? levels : DEFAULT_LEVELS;
        int totalMessages = messages.size();
        List<Message> result = new ArrayList<>(totalMessages);
        int collapsedCount = 0;
        int estimatedCharsFreed = 0;
        int changedCount = 0;
        int sameLengthChangedCount = 0;
        int positiveCount = 0;
        int zeroCount = 0;
        int negativeCount = 0;
        for (int i = 0; i < totalMessages; i++) {
            Message msg = messages.get(i);
            int distanceFromTail = totalMessages - 1 - i;

            // 规则：UserMessage（非工具结果）永远保留原文
            if (msg instanceof Message.UserMessage userMsg && MessageContentAccessor.legacyToolResult(userMsg) == null) {
                result.add(msg);
                continue;
            }

            // 确定该消息的折叠级别
            CollapseLevel level = sortedLevels.stream()
                    .filter(l -> distanceFromTail < l.maxAgeMessages())
                    .findFirst()
                    .orElse(sortedLevels.get(sortedLevels.size() - 1));

            if (level instanceof CollapseLevel.FullRetention) {
                result.add(msg); // 完整保留
            } else {
                // 对工具结果和助手消息执行折叠
                Message collapsedMsg = collapseMessage(msg, level);
                int charsBefore = estimateMessageChars(msg);
                int charsAfter = estimateMessageChars(collapsedMsg);
                int charsFreed = charsBefore - charsAfter;
                estimatedCharsFreed += charsFreed;
                if (diagnosticallyDifferent(msg, collapsedMsg)) {
                    changedCount++;
                    if (charsBefore == charsAfter) sameLengthChangedCount++;
                }
                if (charsFreed > 0) {
                    positiveCount++;
                } else if (charsFreed == 0) {
                    zeroCount++;
                } else {
                    negativeCount++;
                }
                result.add(collapsedMsg);
                collapsedCount++;
            }
        }

        String outcome = classifyOutcome(collapsedCount, positiveCount, negativeCount,
                estimatedCharsFreed);
        try {
            boolean diagnosticsEnabled = negativeCount > 0 ? log.isWarnEnabled() : log.isDebugEnabled();
            boolean fingerprintNeeded = negativeCount > 0 || zeroCount > 0 || sameLengthChangedCount > 0;
            String inputFingerprint = diagnosticsEnabled && fingerprintNeeded ? fingerprint(messages) : "not_sampled";
            String outputFingerprint = diagnosticsEnabled && fingerprintNeeded ? fingerprint(result) : "not_sampled";
            logProgressiveDiagnostics(outcome, totalMessages, collapsedCount,
                    changedCount, sameLengthChangedCount, positiveCount, zeroCount,
                    negativeCount, estimatedCharsFreed, inputFingerprint, outputFingerprint,
                    elapsedMicros(startedAt));
        } catch (RuntimeException ignored) {
            // 诊断失败不得改变折叠结果。
        }
        return new CollapseResult(result, collapsedCount, estimatedCharsFreed);
    }

    /**
     * 使用默认级别的渐进折叠。
     */
    public CollapseResult progressiveCollapse(List<Message> messages) {
        return progressiveCollapse(messages, null);
    }

    /**
     * 对单条消息执行折叠——根据消息类型分别处理。
     * 保留消息的 role、toolUseId 结构，仅折叠内容部分。
     */
    private Message collapseMessage(Message msg, CollapseLevel level) {
        if (msg instanceof Message.AssistantMessage am && am.content() != null) {
            // 助手消息：折叠每个 content block
            List<ContentBlock> collapsedBlocks = am.content().stream()
                .map(block -> {
                    if (block instanceof ContentBlock.TextBlock t && t.text() != null) {
                        return (ContentBlock) new ContentBlock.TextBlock(level.collapse(t.text()));
                    }
                    return block; // ToolUseBlock 等保留原样（保留 toolUseId 结构）
                })
                .toList();
            return new Message.AssistantMessage(
                    am.uuid(), am.timestamp(), collapsedBlocks, am.stopReason(), am.usage());
        }
        if (msg instanceof Message.UserMessage um && MessageContentAccessor.legacyToolResult(um) != null) {
            // 工具结果消息：折叠 toolUseResult 内容但保留结构
            String collapsedContent = level.collapse(MessageContentAccessor.legacyToolResult(um));
            return new Message.UserMessage(
                    um.uuid(), um.timestamp(), um.content(),
                    collapsedContent, um.sourceToolAssistantUUID());
        }
        return msg; // 其他消息类型原样返回
    }

    /** 估算消息字符数（用于统计释放量） */
    private int estimateMessageChars(Message msg) {
        if (msg instanceof Message.AssistantMessage am && am.content() != null) {
            return am.content().stream()
                .mapToInt(b -> b instanceof ContentBlock.TextBlock t
                        ? (t.text() != null ? t.text().length() : 0) : 0)
                .sum();
        }
        if (msg instanceof Message.UserMessage um && MessageContentAccessor.legacyToolResult(um) != null) {
            return MessageContentAccessor.legacyToolResult(um).length();
        }
        return 0;
    }

    private String classifyOutcome(int candidateCount, int positiveCount,
                                   int negativeCount, int netCharsFreed) {
        if (candidateCount == 0) return "NO_CANDIDATE";
        if (netCharsFreed < 0) return "NEGATIVE_NET";
        if (negativeCount > 0) return "MIXED_WITH_NEGATIVE";
        if (positiveCount > 0) return "POSITIVE_ONLY";
        return "NO_GAIN";
    }

    private boolean diagnosticallyDifferent(Message before, Message after) {
        try {
            return !Objects.equals(before, after);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void logProgressiveDiagnostics(
            String outcome, int totalMessages, int candidateCount,
            int changedCount, int sameLengthChangedCount,
            int positiveCount, int zeroCount, int negativeCount,
            int netCharsFreed, String inputFingerprint,
            String outputFingerprint, long elapsedMicros) {
        try {
            String format = "event=context_collapse_candidates contextEvalId={} sessionId={} runId={} turn={} " +
                    "outcome={} totalMessages={} candidates={} changed={} sameLengthChanged={} " +
                    "positiveCount={} zeroCount={} negativeCount={} netCharsFreed={} " +
                    "inputFingerprint={} outputFingerprint={} elapsedMicros={}";
            Object[] args = {
                    mdcValue("contextEvalId"), mdcValue("sessionId"), mdcValue("runId"), mdcValue("turn"),
                    outcome, totalMessages, candidateCount, changedCount, sameLengthChangedCount,
                    positiveCount, zeroCount, negativeCount, netCharsFreed,
                    inputFingerprint, outputFingerprint, elapsedMicros
            };
            if (negativeCount > 0) {
                log.warn(format, args);
            } else {
                log.debug(format, args);
            }
        } catch (RuntimeException ignored) {
            // 日志后端失败不得影响折叠主流程。
        }
    }

    private String mdcValue(String key) {
        String value = org.slf4j.MDC.get(key);
        return value != null ? value : "none";
    }

    private String fingerprint(List<Message> messages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (Writer writer = new OutputStreamWriter(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest),
                    StandardCharsets.UTF_8)) {
                for (Message message : messages) {
                    updateDigest(writer, message.getClass().getSimpleName());
                    updateDigest(writer, message.uuid());
                    if (message instanceof Message.AssistantMessage assistant && assistant.content() != null) {
                        for (ContentBlock block : assistant.content()) {
                            if (block instanceof ContentBlock.TextBlock text) {
                                updateDigest(writer, text.text());
                            }
                        }
                    } else if (message instanceof Message.UserMessage user) {
                        updateDigest(writer, MessageContentAccessor.legacyToolResult(user));
                    } else if (message instanceof Message.SystemMessage system) {
                        updateDigest(writer, system.content());
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, FINGERPRINT_HEX_LENGTH);
        } catch (NoSuchAlgorithmException | IOException | RuntimeException e) {
            // 诊断信息不得影响压缩主流程。
            return "unavailable";
        }
    }

    private void updateDigest(Writer writer, String value) throws IOException {
        if (value != null) writer.write(value);
        writer.write('\0');
    }

    private long elapsedMicros(long startedAt) {
        return TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startedAt);
    }

    // ── 结果 DTO ──────────────────────────────────────────────

    /** 折叠结果 */
    public record CollapseResult(
            List<Message> messages,
            int collapsedCount,
            int estimatedCharsFreed
    ) {}
}
