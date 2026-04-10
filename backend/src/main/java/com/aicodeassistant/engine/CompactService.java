package com.aicodeassistant.engine;

import com.aicodeassistant.llm.LlmProvider;
import com.aicodeassistant.llm.LlmProviderRegistry;
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

    /** SessionMemoryCompact 配置 */
    private static final int SMC_MIN_TOKENS = 10_000;
    private static final int SMC_MIN_TEXT_BLOCK_MESSAGES = 5;
    private static final int SMC_MAX_TOKENS = 40_000;

    private final TokenCounter tokenCounter;
    private final LlmProviderRegistry providerRegistry;

    public CompactService(TokenCounter tokenCounter, LlmProviderRegistry providerRegistry) {
        this.tokenCounter = tokenCounter;
        this.providerRegistry = providerRegistry;
    }

    // ============ 压缩系统提示 ============

    private static final String NO_TOOLS_PREAMBLE = """
            CRITICAL: Respond with TEXT ONLY. Do NOT call any tools.
            Tool calls will be REJECTED and will waste your only turn.
            Your entire response must be plain text: an <analysis> block followed by a <summary> block.
            """;

    private static final String COMPACT_SYSTEM_PROMPT = NO_TOOLS_PREAMBLE + """
            Your task is to create a detailed summary of the conversation so far,
            paying close attention to the user's explicit requests and your previous actions.

            Before providing your final summary, wrap your analysis in <analysis> tags.
            In your analysis process:
            1. Chronologically analyze each message. For each section identify:
               - The user's explicit requests and intents
               - Key decisions, technical concepts and code patterns
               - Specific details: file names, code snippets, function signatures, file edits
               - Errors encountered and how they were fixed
               - User feedback, especially corrections
            2. Double-check for technical accuracy and completeness.

            Your summary should include these sections in <summary> tags:
            1. Primary Request and Intent
            2. Key Technical Concepts
            3. Files and Code Sections (with code snippets)
            4. Errors and fixes
            5. Problem Solving
            6. All user messages (non tool-result)
            7. Pending Tasks
            8. Current Work (precise description with file names)
            9. Optional Next Step (only if directly related to recent work)

            Preserve all file paths, error messages, and concrete values.
            Do NOT use vague references like "the file" - use actual paths.
            """;

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

        // ★ 新增：SMC 配对完整性保证 ★
        plan = ensureToolPairIntegrity(plan, messages);

        int beforeTokens = tokenCounter.estimateTokens(messages);

        // ---- Level 1: LLM 摘要 (增强版) ----
        Optional<String> rawSummary = generateLlmSummary(plan.compactionMessages(), plan.targetSummaryTokens());
        if (rawSummary.isPresent()) {
            String structuredSummary = extractStructuredSummary(rawSummary.get());
            if (validateSummaryQuality(structuredSummary, plan.compactionMessages())) {
                List<Message> compactedMessages = buildCompactResultWithSummary(plan, structuredSummary);
                int afterTokens = tokenCounter.estimateTokens(compactedMessages);
                double ratio = beforeTokens > 0 ? (double) (beforeTokens - afterTokens) / beforeTokens : 0.0;
                log.info("压缩完成 (LLM 摘要, 质量校验通过): {} → {} tokens, 压缩率 {}%",
                        beforeTokens, afterTokens, String.format("%.1f", ratio * 100));
                CompactResult result = new CompactResult(compactedMessages, beforeTokens, afterTokens,
                        plan.compactionMessages().size(), ratio);
                executeCompactHooks(compactedMessages, result);
                return result;
            } else {
                log.warn("LLM 摘要质量不足，降级到关键消息选择");
            }
        }

        // ---- Level 2: 关键消息选择 ----
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

    // ============ Level 1: LLM 摘要 ============

    /**
     * 生成 LLM 摘要 — 用轻量模型生成对话摘要。
     */
    private Optional<String> generateLlmSummary(List<Message> compactionMessages, int targetTokens) {
        if (providerRegistry == null || !providerRegistry.hasProviders()) {
            return Optional.empty();
        }
        try {
            String conversationText = formatMessagesForSummary(compactionMessages);
            String prompt = "Target summary length: ~" + targetTokens + " tokens.\n\n" + conversationText;
            String model = providerRegistry.getFastModel();
            LlmProvider provider = providerRegistry.getProvider(model);
            String summaryText = provider.chatSync(
                    model, COMPACT_SYSTEM_PROMPT, prompt,
                    SUMMARY_MAX_TOKENS, null, 30_000L);
            if (summaryText != null && !summaryText.isBlank()) {
                int summaryTokens = tokenCounter.estimateTokens(summaryText);
                if (summaryTokens <= SUMMARY_MAX_TOKENS) {
                    return Optional.of(summaryText);
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("LLM summary failed, falling back to key message selection", e);
            return Optional.empty();
        }
    }

    /**
     * 将消息列表格式化为摘要输入文本。
     */
    private String formatMessagesForSummary(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            switch (msg) {
                case Message.UserMessage user -> {
                    if (user.toolUseResult() != null) {
                        sb.append("[ToolResult] ").append(user.toolUseResult(), 0,
                                Math.min(user.toolUseResult().length(), 500)).append("\n");
                    } else if (user.content() != null) {
                        for (var block : user.content()) {
                            if (block instanceof ContentBlock.TextBlock text) {
                                sb.append("[User] ").append(text.text()).append("\n");
                            }
                        }
                    }
                }
                case Message.AssistantMessage assistant -> {
                    if (assistant.content() != null) {
                        for (var block : assistant.content()) {
                            if (block instanceof ContentBlock.TextBlock text) {
                                sb.append("[Assistant] ").append(text.text(), 0,
                                        Math.min(text.text().length(), 500)).append("\n");
                            } else if (block instanceof ContentBlock.ToolUseBlock toolUse) {
                                sb.append("[ToolUse] ").append(toolUse.name()).append("\n");
                            }
                        }
                    }
                }
                case Message.SystemMessage sys ->
                        sb.append("[System] ").append(sys.content()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 使用 LLM 摘要构建压缩结果。
     */
    private List<Message> buildCompactResultWithSummary(CompactionPlan plan, String summary) {
        List<Message> compactedMessages = new ArrayList<>();
        compactedMessages.addAll(plan.frozenMessages());
        Message summaryMessage = new Message.SystemMessage(
                UUID.randomUUID().toString(), Instant.now(),
                summary, SystemMessageType.COMPACT_SUMMARY);
        compactedMessages.add(summaryMessage);
        compactedMessages.addAll(plan.preservedMessages());
        return compactedMessages;
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

    // ============ SMC (SessionMemoryCompact) 方法 ============

    /**
     * Session Memory Compaction — 对齐原版 trySessionMemoryCompaction。
     * 保留所有消息，仅生成摘要注入。
     */
    public CompactResult trySessionMemoryCompaction(List<Message> messages, int contextWindow) {
        if (!shouldAutoCompactBufferBased(messages, contextWindow)) {
            return CompactResult.notNeeded();
        }
        int lastSummaryIndex = findLastSummaryIndex(messages);
        int keepIndex = calculateMessagesToKeepIndex(messages, lastSummaryIndex);
        if (keepIndex <= 0 || keepIndex >= messages.size()) {
            return CompactResult.notNeeded();
        }
        keepIndex = adjustIndexToPreserveApiInvariants(messages, keepIndex);
        List<Message> toSummarize = messages.subList(
                Math.max(0, lastSummaryIndex + 1), keepIndex);
        if (toSummarize.isEmpty()) return CompactResult.notNeeded();
        int beforeTokens = tokenCounter.estimateTokens(messages);
        Optional<String> summary = generateLlmSummary(toSummarize, SMC_MAX_TOKENS);
        if (summary.isEmpty()) {
            return CompactResult.notNeeded();
        }
        List<Message> result = new ArrayList<>();
        if (lastSummaryIndex >= 0) {
            result.addAll(messages.subList(0, lastSummaryIndex + 1));
        }
        result.add(new Message.SystemMessage(
                UUID.randomUUID().toString(), Instant.now(),
                summary.get(), SystemMessageType.COMPACT_SUMMARY));
        result.addAll(messages.subList(keepIndex, messages.size()));
        int afterTokens = tokenCounter.estimateTokens(result);
        return CompactResult.success(result, beforeTokens, afterTokens);
    }

    private int calculateMessagesToKeepIndex(List<Message> messages, int lastSummaryIndex) {
        int startIndex = Math.max(0, lastSummaryIndex + 1);
        int tokenCount = 0;
        int textBlockCount = 0;
        for (int i = startIndex; i < messages.size(); i++) {
            tokenCount += tokenCounter.estimateTokens(List.of(messages.get(i)));
            if (hasTextBlocks(messages.get(i))) textBlockCount++;
        }
        if (tokenCount >= SMC_MAX_TOKENS) return startIndex;
        if (tokenCount >= SMC_MIN_TOKENS && textBlockCount >= SMC_MIN_TEXT_BLOCK_MESSAGES) return startIndex;
        for (int i = startIndex - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            tokenCount += tokenCounter.estimateTokens(List.of(msg));
            if (hasTextBlocks(msg)) textBlockCount++;
            startIndex = i;
            if (tokenCount >= SMC_MAX_TOKENS) break;
            if (tokenCount >= SMC_MIN_TOKENS && textBlockCount >= SMC_MIN_TEXT_BLOCK_MESSAGES) break;
        }
        return startIndex;
    }

    private boolean hasTextBlocks(Message message) {
        if (message instanceof Message.AssistantMessage am && am.content() != null) {
            return am.content().stream().anyMatch(b -> b instanceof ContentBlock.TextBlock);
        }
        if (message instanceof Message.UserMessage user && user.content() != null) {
            return user.content().stream().anyMatch(b -> b instanceof ContentBlock.TextBlock);
        }
        return false;
    }

    private int adjustIndexToPreserveApiInvariants(List<Message> messages, int startIndex) {
        if (startIndex <= 0 || startIndex >= messages.size()) return startIndex;
        int adjustedIndex = startIndex;
        Set<String> allToolResultIds = new HashSet<>();
        for (int i = startIndex; i < messages.size(); i++) {
            if (messages.get(i) instanceof Message.UserMessage user && user.toolUseResult() != null) {
                allToolResultIds.add(user.sourceToolAssistantUUID());
            }
        }
        if (!allToolResultIds.isEmpty()) {
            Set<String> toolUseIdsInKept = new HashSet<>();
            for (int i = adjustedIndex; i < messages.size(); i++) {
                if (messages.get(i) instanceof Message.AssistantMessage am && am.content() != null) {
                    for (var block : am.content()) {
                        if (block instanceof ContentBlock.ToolUseBlock tub) toolUseIdsInKept.add(tub.id());
                    }
                }
            }
            Set<String> neededIds = new HashSet<>(allToolResultIds);
            neededIds.removeAll(toolUseIdsInKept);
            for (int i = adjustedIndex - 1; i >= 0 && !neededIds.isEmpty(); i--) {
                if (messages.get(i) instanceof Message.AssistantMessage am && am.content() != null) {
                    boolean hasNeeded = am.content().stream()
                            .anyMatch(b -> b instanceof ContentBlock.ToolUseBlock tub && neededIds.contains(tub.id()));
                    if (hasNeeded) {
                        adjustedIndex = i;
                        am.content().stream()
                                .filter(b -> b instanceof ContentBlock.ToolUseBlock)
                                .map(b -> ((ContentBlock.ToolUseBlock) b).id())
                                .forEach(neededIds::remove);
                    }
                }
            }
        }
        Set<String> uuidsInKept = new HashSet<>();
        for (int i = adjustedIndex; i < messages.size(); i++) {
            if (messages.get(i) instanceof Message.AssistantMessage am) uuidsInKept.add(am.uuid());
        }
        for (int i = adjustedIndex - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Message.AssistantMessage am && uuidsInKept.contains(am.uuid())) {
                adjustedIndex = i;
            }
        }
        return adjustedIndex;
    }

    private int findLastSummaryIndex(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Message.SystemMessage sys
                    && sys.type() == SystemMessageType.COMPACT_SUMMARY) {
                return i;
            }
        }
        return -1;
    }

    // ============ §9.1 增强方法 ============

    /** 结构化摘要解析 */
    private String extractStructuredSummary(String rawSummary) {
        if (rawSummary == null || rawSummary.isBlank()) return "";
        int summaryStart = rawSummary.indexOf("<summary>");
        int summaryEnd = rawSummary.indexOf("</summary>");
        if (summaryStart >= 0 && summaryEnd > summaryStart) {
            return rawSummary.substring(summaryStart + "<summary>".length(), summaryEnd).trim();
        }
        int analysisEnd = rawSummary.indexOf("</analysis>");
        if (analysisEnd >= 0) {
            return rawSummary.substring(analysisEnd + "</analysis>".length()).trim();
        }
        return rawSummary.trim();
    }

    /** 摘要质量校验 */
    private boolean validateSummaryQuality(String summary, List<Message> originalMessages) {
        if (summary.length() < 100) return false;
        int filePathCount = 0;
        for (String line : summary.split("\n")) {
            if (line.contains("/") && (line.contains(".java") || line.contains(".ts")
                    || line.contains(".py") || line.contains(".md"))) {
                filePathCount++;
            }
        }
        boolean hasFileOps = originalMessages.stream().anyMatch(m ->
                m instanceof Message.AssistantMessage am && am.content() != null
                        && am.content().stream().anyMatch(b ->
                        b instanceof ContentBlock.ToolUseBlock tub
                                && (tub.name().contains("File") || tub.name().contains("Bash"))));
        if (hasFileOps && filePathCount == 0) {
            log.warn("摘要质量不足：原始消息包含文件操作但摘要中无文件路径");
            return false;
        }
        return true;
    }

    /** SMC 配对完整性保证 */
    public CompactionPlan ensureToolPairIntegrity(CompactionPlan plan, List<Message> allMessages) {
        Set<String> toolUseIds = new HashSet<>();
        Set<String> toolResultIds = new HashSet<>();
        for (Message msg : plan.compactionMessages()) {
            if (msg instanceof Message.AssistantMessage am && am.content() != null) {
                for (ContentBlock block : am.content()) {
                    if (block instanceof ContentBlock.ToolUseBlock tub) toolUseIds.add(tub.id());
                }
            }
            if (msg instanceof Message.UserMessage um && um.content() != null) {
                for (ContentBlock block : um.content()) {
                    if (block instanceof ContentBlock.ToolResultBlock trb) toolResultIds.add(trb.toolUseId());
                }
            }
        }
        Set<String> orphanResultIds = new HashSet<>();
        Set<String> orphanUseIds = new HashSet<>();
        for (Message msg : plan.preservedMessages()) {
            if (msg instanceof Message.UserMessage um && um.content() != null) {
                for (ContentBlock block : um.content()) {
                    if (block instanceof ContentBlock.ToolResultBlock trb && toolUseIds.contains(trb.toolUseId())) {
                        orphanResultIds.add(trb.toolUseId());
                    }
                }
            }
            if (msg instanceof Message.AssistantMessage am && am.content() != null) {
                for (ContentBlock block : am.content()) {
                    if (block instanceof ContentBlock.ToolUseBlock tub && toolResultIds.contains(tub.id())) {
                        orphanUseIds.add(tub.id());
                    }
                }
            }
        }
        if (orphanResultIds.isEmpty() && orphanUseIds.isEmpty()) return plan;
        log.info("SMC: 检测到 {} 个孤立 tool_result, {} 个孤立 tool_use，调整压缩边界",
                orphanResultIds.size(), orphanUseIds.size());
        List<Message> newCompaction = new ArrayList<>(plan.compactionMessages());
        List<Message> newPreserved = new ArrayList<>();
        Set<String> allOrphans = new HashSet<>();
        allOrphans.addAll(orphanResultIds);
        allOrphans.addAll(orphanUseIds);
        for (Message msg : plan.preservedMessages()) {
            boolean isOrphan = false;
            if (msg instanceof Message.UserMessage um && um.content() != null) {
                for (ContentBlock block : um.content()) {
                    if (block instanceof ContentBlock.ToolResultBlock trb && allOrphans.contains(trb.toolUseId())) {
                        isOrphan = true; break;
                    }
                }
            }
            if (msg instanceof Message.AssistantMessage am && am.content() != null) {
                for (ContentBlock block : am.content()) {
                    if (block instanceof ContentBlock.ToolUseBlock tub && allOrphans.contains(tub.id())) {
                        isOrphan = true; break;
                    }
                }
            }
            if (isOrphan) newCompaction.add(msg);
            else newPreserved.add(msg);
        }
        int newCompactionTokens = tokenCounter.estimateTokens(newCompaction);
        return new CompactionPlan(plan.frozenMessages(), newCompaction, newPreserved,
                newCompactionTokens, plan.targetSummaryTokens());
    }

    // ============ 压缩后钩子 ============

    /** 压缩后钩子接口 */
    public interface CompactHook {
        void afterCompact(List<Message> compactedMessages, CompactResult result);
    }

    private final List<CompactHook> compactHooks = new ArrayList<>();

    public void registerCompactHook(CompactHook hook) {
        compactHooks.add(hook);
    }

    private void executeCompactHooks(List<Message> compactedMessages, CompactResult result) {
        for (CompactHook hook : compactHooks) {
            try {
                hook.afterCompact(compactedMessages, result);
            } catch (Exception e) {
                log.warn("Compact hook failed: {}", e.getMessage());
            }
        }
    }

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
