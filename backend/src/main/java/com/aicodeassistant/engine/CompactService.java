package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.SystemMessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * CompactService — 对话历史压缩引擎。
 * <p>
 * 两种触发模式:
 *   1. 自动压缩 (Auto Compact): 消息 token 数接近模型上下文窗口时主动触发
 *   2. 反应式压缩 (Reactive Compact): API 返回 413 prompt_too_long 时紧急触发
 * <p>
 * 对照源码: CompactService 通过消息三区划分法（冻结区/压缩区/保留区）确定压缩范围，
 * 然后通过 3 级降级策略生成摘要。
 *
 * @see <a href="SPEC §3.1.5">CompactService 压缩算法</a>
 */
@Service
public class CompactService {

    private static final Logger log = LoggerFactory.getLogger(CompactService.class);

    // ============ 压缩常量 ============

    /** 自动压缩触发阈值: 消息 token 数 / 模型上下文窗口 */
    private static final double AUTO_COMPACT_THRESHOLD = 0.85;

    /** 压缩后目标 token 占比 */
    private static final double COMPACT_TARGET_RATIO = 0.50;

    /** 摘要生成的最大 token 预算 */
    private static final int SUMMARY_MAX_TOKENS = 4096;

    /** 保留的最近消息轮次数 (常规压缩) */
    private static final int PRESERVED_RECENT_TURNS = 3;

    /** 反应式压缩保留轮次数 */
    private static final int REACTIVE_PRESERVED_TURNS = 1;

    /** 自动压缩缓冲 token 数 (ContextCascade 用) */
    private static final int AUTOCOMPACT_BUFFER_TOKENS = 13000;

    private final TokenCounter tokenCounter;

    public CompactService(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    // ============ 压缩计划 ============

    /**
     * 压缩计划 — 三区划分结果。
     */
    public record CompactionPlan(
            List<Message> frozenMessages,
            List<Message> compactionMessages,
            List<Message> preservedMessages,
            int compactionTokens,
            int targetSummaryTokens
    ) {}

    /**
     * 压缩结果。
     */
    public record CompactResult(
            List<Message> compactedMessages,
            int beforeTokens,
            int afterTokens,
            int compactedMessageCount,
            double compressionRatio,
            String skipReason,
            int consecutiveFailures
    ) {
        /** 5 参数便捷构造器 */
        public CompactResult(List<Message> compactedMessages, int beforeTokens,
                             int afterTokens, int compactedMessageCount, double compressionRatio) {
            this(compactedMessages, beforeTokens, afterTokens,
                    compactedMessageCount, compressionRatio, null, 0);
        }

        public int savedTokens() { return beforeTokens - afterTokens; }

        public String summary() {
            return String.format("压缩 %d 条消息: %d → %d tokens (%.1f%% 压缩率)",
                    compactedMessageCount, beforeTokens, afterTokens, compressionRatio * 100);
        }

        public static CompactResult skipped(String reason) {
            return new CompactResult(List.of(), 0, 0, 0, 0.0, reason, 0);
        }

        public static CompactResult notNeeded() {
            return new CompactResult(List.of(), 0, 0, 0, 0.0, "not_needed", 0);
        }

        public static CompactResult success(List<Message> compacted, int beforeTokens, int afterTokens) {
            int saved = beforeTokens - afterTokens;
            double ratio = beforeTokens > 0 ? (double) saved / beforeTokens : 0.0;
            return new CompactResult(compacted, beforeTokens, afterTokens,
                    compacted.size(), ratio, null, 0);
        }

        public static CompactResult failed(int consecutiveFailures) {
            return new CompactResult(List.of(), 0, 0, 0, 0.0, "compact_failed", consecutiveFailures);
        }
    }

    // ============ 压缩触发判断 ============

    /**
     * 检查是否需要自动压缩。
     */
    public boolean shouldAutoCompact(List<Message> messages, int contextWindowSize) {
        int estimatedTokens = tokenCounter.estimateTokens(messages);
        return (double) estimatedTokens / contextWindowSize > AUTO_COMPACT_THRESHOLD;
    }

    /**
     * 基于 buffer 的精确阈值检查 (ContextCascade 算法)。
     */
    public boolean shouldAutoCompactBufferBased(List<Message> messages, int contextWindowSize) {
        int effectiveWindow = contextWindowSize - contextWindowSize / 4;
        int threshold = effectiveWindow - AUTOCOMPACT_BUFFER_TOKENS;
        int estimatedTokens = tokenCounter.estimateTokens(messages);
        return estimatedTokens > threshold;
    }

    // ============ 核心压缩 ============

    /**
     * 执行压缩 — 3 级降级策略。
     * <p>
     * Level 1: LLM 摘要 (P0 阶段使用关键消息选择替代)
     * Level 2: 关键消息选择 (按优先级保留)
     * Level 3: 尾部截断 (保留最近 1/3 消息)
     */
    public CompactResult compact(List<Message> messages, int contextWindowSize, boolean isReactive) {
        int preserveTurns = isReactive ? REACTIVE_PRESERVED_TURNS : PRESERVED_RECENT_TURNS;
        CompactionPlan plan = planCompaction(messages, contextWindowSize, preserveTurns);

        if (plan.compactionMessages().isEmpty()) {
            return CompactResult.notNeeded();
        }

        int beforeTokens = tokenCounter.estimateTokens(messages);

        // ---- P0: 使用关键消息选择 (跳过 LLM 摘要) ----
        try {
            int tokenBudget = (int) (contextWindowSize * COMPACT_TARGET_RATIO);
            List<Message> selected = fallbackKeyMessageSelection(plan.compactionMessages(), tokenBudget);
            if (!selected.isEmpty()) {
                List<Message> compactedMessages = new ArrayList<>();
                compactedMessages.addAll(plan.frozenMessages());

                // 插入压缩边界标记
                Message boundary = new Message.SystemMessage(
                        UUID.randomUUID().toString(), Instant.now(),
                        "[对话历史已压缩] 保留 " + selected.size() + "/" + plan.compactionMessages().size() + " 条关键消息",
                        SystemMessageType.COMPACT_SUMMARY);
                compactedMessages.add(boundary);
                compactedMessages.addAll(selected);
                compactedMessages.addAll(plan.preservedMessages());

                int afterTokens = tokenCounter.estimateTokens(compactedMessages);
                double ratio = beforeTokens > 0 ? (double) (beforeTokens - afterTokens) / beforeTokens : 0.0;
                log.info("压缩完成 (关键消息选择): {} → {} tokens, 压缩率 {:.1f}%",
                        beforeTokens, afterTokens, ratio * 100);
                return new CompactResult(compactedMessages, beforeTokens, afterTokens,
                        plan.compactionMessages().size() - selected.size(), ratio);
            }
        } catch (RuntimeException e) {
            log.error("关键消息选择失败，进入尾部截断", e);
        }

        // ---- Level 3: 尾部截断 (最后手段) ----
        log.warn("降级策略: 尾部截断 — 保留最近消息，丢弃最早消息");
        int keepCount = Math.max(plan.preservedMessages().size(), messages.size() / 3);
        List<Message> truncated = new ArrayList<>(
                messages.subList(messages.size() - keepCount, messages.size()));

        int afterTokens = tokenCounter.estimateTokens(truncated);
        double ratio = beforeTokens > 0 ? (double) (beforeTokens - afterTokens) / beforeTokens : 0.0;
        return new CompactResult(truncated, beforeTokens, afterTokens,
                messages.size() - keepCount, ratio);
    }

    /**
     * 反应式压缩 — 紧急模式，用于 413 错误恢复。
     */
    public CompactResult reactiveCompact(List<Message> messages, int contextWindowSize,
                                          boolean hasAttempted) {
        if (hasAttempted) {
            log.error("反应式压缩已尝试过，拒绝再次执行以防死亡螺旋");
            return CompactResult.failed(1);
        }
        return compact(messages, contextWindowSize, true);
    }

    // ============ 三区划分 ============

    /**
     * 计算压缩计划 — 确定三区边界。
     */
    public CompactionPlan planCompaction(List<Message> messages, int contextWindowSize, int preserveTurns) {
        // 1. 冻结区: 找到最后一个 compact_boundary
        int compactBoundaryIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg instanceof Message.SystemMessage sys
                    && sys.type() == SystemMessageType.COMPACT_SUMMARY) {
                compactBoundaryIndex = i;
            }
        }

        int splitPoint = Math.max(compactBoundaryIndex + 1, 0);
        List<Message> frozen = new ArrayList<>(messages.subList(0, splitPoint));
        List<Message> remaining = new ArrayList<>(messages.subList(splitPoint, messages.size()));

        // 2. 保留区: 从 remaining 末尾取最近 N 轮
        int preserveStart = findTurnBoundary(remaining, preserveTurns);
        List<Message> preserved = new ArrayList<>(remaining.subList(preserveStart, remaining.size()));
        List<Message> compaction = new ArrayList<>(remaining.subList(0, preserveStart));

        // 3. 计算 token 预算
        int frozenTokens = tokenCounter.estimateTokens(frozen);
        int preservedTokens = tokenCounter.estimateTokens(preserved);
        int compactionTokens = tokenCounter.estimateTokens(compaction);

        int targetSummaryTokens = Math.min(
                (int) (contextWindowSize * COMPACT_TARGET_RATIO) - frozenTokens - preservedTokens,
                SUMMARY_MAX_TOKENS);
        targetSummaryTokens = Math.max(targetSummaryTokens, 512);

        return new CompactionPlan(frozen, compaction, preserved, compactionTokens, targetSummaryTokens);
    }

    // ============ 关键消息选择 ============

    public enum MessagePriority {
        P0_SYSTEM,
        P1_FILE_OPERATION,
        P2_ERROR_CONTEXT,
        P3_USER_INTENT,
        P4_TOOL_SUCCESS,
        P5_INTERMEDIATE
    }

    private record PrioritizedMessage(Message message, MessagePriority priority) {}

    /**
     * 关键消息选择 — 按优先级保留消息直到 token 预算耗尽。
     */
    public List<Message> fallbackKeyMessageSelection(List<Message> messages, int tokenBudget) {
        List<PrioritizedMessage> prioritized = messages.stream()
                .map(m -> new PrioritizedMessage(m, classifyPriority(m)))
                .sorted(Comparator.comparing(PrioritizedMessage::priority))
                .toList();

        List<Message> selected = new ArrayList<>();
        int usedTokens = 0;
        for (PrioritizedMessage pm : prioritized) {
            int msgTokens = tokenCounter.estimateTokens(List.of(pm.message()));
            if (usedTokens + msgTokens <= tokenBudget) {
                selected.add(pm.message());
                usedTokens += msgTokens;
            }
        }

        // 按原始顺序排列
        List<Message> originalOrder = new ArrayList<>(messages);
        selected.sort(Comparator.comparingInt(originalOrder::indexOf));
        return selected;
    }

    /**
     * 消息优先级分类。
     */
    private MessagePriority classifyPriority(Message message) {
        if (message instanceof Message.SystemMessage) {
            return MessagePriority.P0_SYSTEM;
        }
        if (message instanceof Message.UserMessage user) {
            if (user.toolUseResult() != null) {
                String result = user.toolUseResult();
                if (result.contains("error") || result.contains("Error")
                        || result.contains("failed") || result.contains("Failed")) {
                    return MessagePriority.P2_ERROR_CONTEXT;
                }
                return MessagePriority.P4_TOOL_SUCCESS;
            }
            return MessagePriority.P3_USER_INTENT;
        }
        if (message instanceof Message.AssistantMessage assistant) {
            if (assistant.content() != null) {
                for (var block : assistant.content()) {
                    if (block instanceof ContentBlock.ToolUseBlock) {
                        return MessagePriority.P1_FILE_OPERATION;
                    }
                }
            }
            return MessagePriority.P5_INTERMEDIATE;
        }
        return MessagePriority.P5_INTERMEDIATE;
    }

    // ============ 辅助方法 ============

    /**
     * 查找轮次边界 — 从消息列表末尾回溯 N 个用户消息轮次。
     */
    private int findTurnBoundary(List<Message> messages, int turns) {
        int turnCount = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Message.UserMessage) {
                turnCount++;
                if (turnCount >= turns) {
                    return i;
                }
            }
        }
        return 0;
    }
}
