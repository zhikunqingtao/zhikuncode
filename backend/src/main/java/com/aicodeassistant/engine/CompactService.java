package com.aicodeassistant.engine;

import com.aicodeassistant.llm.LlmProvider;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.SystemMessageType;
import com.aicodeassistant.security.PathSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.util.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CompactService — 对话历史压缩引擎。
 * <p>
 * 两种触发模式:
 *   1. 自动压缩 (Auto Compact): 消息 token 数接近模型上下文窗口时主动触发
 *   2. 反应式压缩 (Reactive Compact): API 返回 413 prompt_too_long 时紧急触发
 * <p>
 * 然后通过 3 级降级策略生成摘要。
 *
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

    /** 自动压缩最低消息数守卫 — 至少 N 条消息才考虑压缩 */
    private static final int MIN_MESSAGES_FOR_COMPACT = 5;

    /** 输出预留 token 上限*/
    private static final int MAX_OUTPUT_RESERVE = 20_000;

    /** SessionMemoryCompact 配置 */
    private static final int SMC_MIN_TOKENS = 10_000;
    private static final int SMC_MIN_TEXT_BLOCK_MESSAGES = 5;
    private static final int SMC_MAX_TOKENS = 40_000;


    @org.springframework.beans.factory.annotation.Autowired
    private ContextCompactor contextCompactor;

    private ContextCompactor compactor() {
        return contextCompactor != null ? contextCompactor
                : new ContextCompactor(tokenCounter, providerRegistry, null, new CompactConfiguration(null));
    }

    public CompactResult compactForPreview(List<Message> messages, String model) {
        return compact(messages, compactor().previewContext(model), false);
    }

    public long summaryTimeoutMillis() { return compactor().timeoutMillis(); }

    private final TokenCounter tokenCounter;
    private final LlmProviderRegistry providerRegistry;
    private final KeyFileTracker keyFileTracker;
    private final PathSecurityService pathSecurity;

    public CompactService(TokenCounter tokenCounter, LlmProviderRegistry providerRegistry,
                          KeyFileTracker keyFileTracker, PathSecurityService pathSecurity) {
        this.tokenCounter = tokenCounter;
        this.providerRegistry = providerRegistry;
        this.keyFileTracker = keyFileTracker;
        this.pathSecurity = pathSecurity;
    }

    // ============ 压缩系统提示 ============


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
            int consecutiveFailures,
            String mode,
            String failureReason
    ) {
        public CompactResult(List<Message> messages, int before, int after, int count, double ratio,
                             String skipReason, int failures) {
            this(messages, before, after, count, ratio, skipReason, failures,
                    skipReason == null ? "legacy" : "unchanged", skipReason);
        }
        /** 5 参数便捷构造器 */
        public CompactResult(List<Message> compactedMessages, int beforeTokens,
                             int afterTokens, int compactedMessageCount, double compressionRatio) {
            this(compactedMessages, beforeTokens, afterTokens,
                    compactedMessageCount, compressionRatio, null, 0);
        }

        public int savedTokens() { return Math.max(0, beforeTokens - afterTokens); }

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
            if (afterTokens >= beforeTokens) {
                return CompactResult.skipped("no_token_savings");
            }
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
        // ★ 守卫 1: 最低消息数量检查 — 至少 5 条消息才考虑压缩 ★
        if (messages.size() < MIN_MESSAGES_FOR_COMPACT) {
            log.debug("自动压缩跳过: 消息数 {} < 最低阈值 {}", messages.size(), MIN_MESSAGES_FOR_COMPACT);
            return false;
        }

        // ★ 守卫 2: 排除纯系统消息（如仅包含系统提示词） ★
        long userOrAssistantCount = messages.stream()
                .filter(m -> m instanceof Message.UserMessage || m instanceof Message.AssistantMessage)
                .count();
        if (userOrAssistantCount < 2) {
            log.debug("自动压缩跳过: 用户/助手消息数 {} < 2", userOrAssistantCount);
            return false;
        }

        int effectiveWindow = contextWindowSize - Math.max(contextWindowSize / 4, MAX_OUTPUT_RESERVE);
        // 安全下限保护: 防止 contextWindowSize 过小导致 effectiveWindow 为负数
        // 最低有效窗口 = AUTOCOMPACT_BUFFER_TOKENS * 2，确保只在真正接近上下文极限时触发
        int minimumEffectiveWindow = AUTOCOMPACT_BUFFER_TOKENS * 2;
        if (effectiveWindow < minimumEffectiveWindow) {
            log.warn("自动压缩: effectiveWindow={} 过小(小于{})[上下文窗口={}], 已钳制为最小安全值",
                    effectiveWindow, minimumEffectiveWindow, contextWindowSize);
            effectiveWindow = minimumEffectiveWindow;
        }
        int threshold = effectiveWindow - AUTOCOMPACT_BUFFER_TOKENS;
        int estimatedTokens = tokenCounter.estimateTokens(messages);

        log.debug("自动压缩检查: tokens={}, threshold={}, effectiveWindow={}, messages={}",
                estimatedTokens, threshold, effectiveWindow, messages.size());

        return estimatedTokens > threshold;
    }

    // ============ 核心压缩 ============

    /**
     * 执行压缩 — 3 级降级策略。
     * <p>
     * 完整摘要验证失败后，按完整工具事务选择本地历史；不截断消息。
     */
    public CompactResult compact(List<Message> messages, int contextWindowSize, boolean isReactive) {
        return compact(messages, CompactionContext.unscoped(contextWindowSize), isReactive);
    }

    public CompactResult compact(List<Message> messages, CompactionContext context, boolean reactive) {
        CompactResult result = compactor().compact(messages, context, reactive);
        if (result.skipReason() == null) executeCompactHooks(result.compactedMessages(), result);
        return result;
    }

    public CompactResult reactiveCompact(List<Message> messages, CompactionContext context, boolean attempted) {
        return attempted ? CompactResult.failed(1) : compact(messages, context, true);
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
        targetSummaryTokens = Math.max(targetSummaryTokens, 0);

        return new CompactionPlan(frozen, compaction, preserved, compactionTokens, targetSummaryTokens);
    }

    // ============ Level 1: LLM 摘要 ============

    /**
     * 生成 LLM 摘要 — 使用独立配置的摘要模型。
     */
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
     * <p>
     * 增强策略：
     * 1. 优先保留 system 消息
     * 2. 工具调用对（tool_use + tool_result）成对保留
     * 3. 最近用户消息优先保留
     * 4. 剩余按优先级填充
     */
    public List<Message> fallbackKeyMessageSelection(List<Message> messages, int tokenBudget) {
        var units = CompactionHistory.analyze(messages).units();
        boolean[] keep = new boolean[messages.size()];
        int used = 0;
        for (var unit : units) {
            if (unit.userContent() || messages.subList(unit.start(), unit.end()).stream().anyMatch(Message.SystemMessage.class::isInstance)) {
                Arrays.fill(keep, unit.start(), unit.end(), true);
                used += tokenCounter.estimateTokens(messages.subList(unit.start(), unit.end()));
            }
        }
        if (used > tokenBudget) return List.of();
        for (int i = units.size() - 1; i >= 0; i--) {
            var unit = units.get(i);
            if (keep[unit.start()]) continue;
            int cost = tokenCounter.estimateTokens(messages.subList(unit.start(), unit.end()));
            if (used + cost <= tokenBudget) {
                Arrays.fill(keep, unit.start(), unit.end(), true); used += cost;
            }
        }
        List<Message> selected = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) if (keep[i]) selected.add(messages.get(i));
        return selected;
    }

    // ============ SMC (SessionMemoryCompact) 方法 ============

    /**
     * Session Memory Compaction。
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
        // Keep the existing inactive SMC planning gate; never bypass shared candidate validation.
        return compact(messages, contextWindow, false);
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
            if (messages.get(i) instanceof Message.UserMessage user && MessageContentAccessor.legacyToolResult(user) != null) {
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
        return ContextCompactor.extract(rawSummary);
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

    // ==================== 压缩后文件重注入 ====================

    private static final int MAX_REINJECT_FILES = 5;
    private static final int MAX_FILE_SIZE_CHARS = 10_000;

    /**
     * 压缩后文件重注入
     * <p>
     * 从摘要文本中提取文件路径引用，重新读取文件内容，
     * 以 SystemMessage 形式注入到压缩结果中。
     */
    public List<Message> reInjectFilesAfterCompact(
            List<Message> compactedMessages, String workingDirectory) {

        // 1. 从 COMPACT_SUMMARY 消息中提取 LLM 摘要文本
        String summaryText = compactedMessages.stream()
                .filter(m -> m instanceof Message.SystemMessage sys
                        && sys.type() == SystemMessageType.COMPACT_SUMMARY)
                .map(m -> ((Message.SystemMessage) m).content())
                .reduce((first, second) -> second)
                .orElse(null);
        if (summaryText == null || summaryText.isBlank()) {
            log.debug("压缩后文件重注入: 未找到 COMPACT_SUMMARY 消息");
            return compactedMessages;
        }

        // 2. 从摘要中提取文件路径
        Set<String> filePaths = extractFilePathsFromSummary(summaryText);
        if (filePaths.isEmpty()) {
            log.debug("压缩后文件重注入: 未检测到文件路径引用");
            return compactedMessages;
        }

        // 3. 过滤有效且存在的文件 + PathSecurityService 安全检查
        List<String> validPaths = filePaths.stream()
                .map(p -> resolveFilePath(p, workingDirectory))
                .filter(p -> p != null && Files.exists(Path.of(p)))
                .filter(p -> {
                    // ★ PathSecurityService 安全检查 ★
                    var securityCheck = pathSecurity.checkReadPermission(p, workingDirectory);
                    if (!securityCheck.isAllowed()) {
                        log.warn("文件重注入安全拦截: {} - {}", p, securityCheck.message());
                        return false;
                    }
                    return true;
                })
                .filter(p -> {
                    try { return Files.size(Path.of(p)) < MAX_FILE_SIZE_CHARS * 2L; }
                    catch (IOException e) { return false; }
                })
                .limit(MAX_REINJECT_FILES)
                .toList();

        if (validPaths.isEmpty()) {
            log.debug("压缩后文件重注入: 无有效文件可注入");
            return compactedMessages;
        }

        // 4. 读取文件内容并构建注入消息
        List<Message> result = new ArrayList<>(compactedMessages);
        StringBuilder fileContent = new StringBuilder();
        fileContent.append("[压缩后文件重注入] 以下文件在上下文压缩中被引用，已重新加载最新内容:\n\n");

        for (String path : validPaths) {
            try {
                String content = Files.readString(Path.of(path), StandardCharsets.UTF_8);
                if (content.length() > MAX_FILE_SIZE_CHARS) {
                    content = content.substring(0, MAX_FILE_SIZE_CHARS) + "\n...[truncated]";
                }
                fileContent.append("--- ").append(path).append(" ---\n");
                fileContent.append(content).append("\n\n");
            } catch (IOException e) {
                log.warn("文件重注入读取失败: {}", path, e);
            }
        }

        // 5. 插入到摘要消息之后
        Message reInjectMsg = new Message.SystemMessage(
                UUID.randomUUID().toString(), Instant.now(),
                fileContent.toString(), SystemMessageType.FILE_REINJECT);

        int insertIndex = -1;
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i) instanceof Message.SystemMessage sys
                    && sys.type() == SystemMessageType.COMPACT_SUMMARY) {
                insertIndex = i + 1;
            }
        }
        if (insertIndex >= 0 && insertIndex <= result.size()) {
            result.add(insertIndex, reInjectMsg);
        } else {
            result.add(reInjectMsg);
        }

        log.info("压缩后文件重注入完成: {}个文件 [{}]", validPaths.size(),
                String.join(", ", validPaths));
        return result;
    }

    /**
     * 基于访问历史的文件重注入 — 优先使用 KeyFileTracker，降级回退到正则提取。
     * <p>
     * 在 AutoCompact 完成后调用，从 KeyFileTracker 获取 Top-5 关键文件，
     * 经过 PathSecurityService 安全检查后，读取文件内容注入到压缩结果中。
     *
     * @param compactedMessages 压缩后的消息列表
     * @param sessionId         会话 ID（用于查询 KeyFileTracker）
     * @param workingDirectory  工作目录
     * @return 注入关键文件后的消息列表
     */
    public List<Message> rebuildAfterCompact(
            List<Message> compactedMessages, String sessionId, String workingDirectory) {

        // 1. 优先使用 KeyFileTracker 获取 Top-5 关键文件
        List<String> keyFiles = keyFileTracker.getKeyFiles(sessionId, MAX_REINJECT_FILES);

        // 2. 降级回退：KeyFileTracker 无记录时，使用现有正则提取方案
        if (keyFiles.isEmpty()) {
            return reInjectFilesAfterCompact(compactedMessages, workingDirectory);
        }

        // 3. 安全检查 + 文件过滤
        List<String> validPaths = keyFiles.stream()
                .filter(path -> {
                    // ★ PathSecurityService 安全检查 ★
                    var checkResult = pathSecurity.checkReadPermission(path, workingDirectory);
                    if (!checkResult.isAllowed()) {
                        log.warn("文件重注入安全拦截: {} - {}", path, checkResult.message());
                        return false;
                    }
                    return true;
                })
                .filter(p -> {
                    try { return Files.exists(Path.of(p)) && Files.size(Path.of(p)) < MAX_FILE_SIZE_CHARS * 4L; }
                    catch (IOException e) { return false; }
                })
                .limit(MAX_REINJECT_FILES)
                .toList();

        if (validPaths.isEmpty()) {
            log.debug("压缩后文件重注入: 无有效文件可注入");
            return compactedMessages;
        }

        // 4. 读取文件内容并截断
        List<Message> result = new ArrayList<>(compactedMessages);
        StringBuilder fileContent = new StringBuilder();
        fileContent.append("[Key Files re-injected after compression (by access frequency)]\n\n");

        for (String path : validPaths) {
            try {
                String content = Files.readString(Path.of(path), StandardCharsets.UTF_8);
                if (content.length() > MAX_FILE_SIZE_CHARS) {
                    content = content.substring(0, MAX_FILE_SIZE_CHARS) + "\n...[truncated]";
                }
                fileContent.append("--- ").append(path).append(" ---\n");
                fileContent.append(content).append("\n\n");
            } catch (IOException e) {
                log.warn("文件重注入读取失败: {}", path, e);
            }
        }

        // 5. 插入到 COMPACT_SUMMARY 消息之后
        Message reInjectMsg = new Message.SystemMessage(
                UUID.randomUUID().toString(), Instant.now(),
                fileContent.toString(), SystemMessageType.FILE_REINJECT);

        int insertIndex = -1;
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i) instanceof Message.SystemMessage sys
                    && sys.type() == SystemMessageType.COMPACT_SUMMARY) {
                insertIndex = i + 1;
            }
        }
        if (insertIndex >= 0 && insertIndex <= result.size()) {
            result.add(insertIndex, reInjectMsg);
        } else {
            result.add(reInjectMsg);
        }

        log.info("压缩后文件重注入完成 (KeyFileTracker): {}个文件 [{}]",
                validPaths.size(), String.join(", ", validPaths));
        return result;
    }

    private Set<String> extractFilePathsFromSummary(String summary) {
        Set<String> paths = new LinkedHashSet<>();
        Pattern pathPattern = Pattern.compile(
                "(?:^|\\s)(/[\\w./\\-]+\\.(java|ts|tsx|py|json|yml|yaml|xml|md|sql|sh))"
                + "|(?:^|\\s)([\\w./\\-]+\\.(java|ts|tsx|py|json|yml|yaml|xml|md|sql|sh))",
                Pattern.MULTILINE);
        Matcher matcher = pathPattern.matcher(summary);
        while (matcher.find()) {
            String path = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
            if (path != null) paths.add(path.trim());
        }
        return paths;
    }

    private String resolveFilePath(String path, String workingDirectory) {
        if (path == null) return null;
        Path p = Path.of(path);
        if (p.isAbsolute()) return p.toString();
        if (workingDirectory != null) {
            return Path.of(workingDirectory).resolve(path).normalize().toString();
        }
        return null;
    }
}
