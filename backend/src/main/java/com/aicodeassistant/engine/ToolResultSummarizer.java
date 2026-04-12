package com.aicodeassistant.engine;

import com.aicodeassistant.llm.LlmProvider;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 工具结果摘要器 — 压缩过大的工具结果，防止上下文溢出。
 * <p>
 * 三级策略：
 * 1. 结果 ≤ SOFT_LIMIT → 保持原样
 * 2. SOFT_LIMIT < 结果 ≤ HARD_LIMIT → 截断 + 尾部摘要提示
 * 3. 结果 > HARD_LIMIT → 硬截断（只保留头尾）
 * <p>
 * LLM 智能摘要（P-AG-01）：
 * - 当工具输出超过阈值时，优先使用轻量级模型（haiku）生成结构化摘要
 * - 摘要为 git-commit 风格单行描述
 * - 失败时静默降级为截断策略
 * <p>
 * 对照原版 Function Result Clearing + Summarize Tool Results + toolUseSummaryGenerator.ts 机制。
 *
 * @see <a href="SPEC §3.1.5">CompactService 压缩算法</a>
 */
@Component
public class ToolResultSummarizer {

    private static final Logger log = LoggerFactory.getLogger(ToolResultSummarizer.class);

    // ===== LLM 摘要相关常量 (P-AG-01) =====
    /** LLM 摘要输入截断长度（工具名/输入/输出各截断到此长度作为 LLM 输入） */
    private static final int MAX_INPUT_LENGTH = 300;
    /** LLM 摘要最大生成 token 数 */
    private static final int SUMMARY_MAX_TOKENS = 256;
    /** LLM 摘要调用超时（毫秒） */
    private static final long TIMEOUT_MS = 10_000;

    private static final String SUMMARY_SYSTEM_PROMPT = """
            You are a tool result summarizer. Generate a concise summary of what the tool \
            call accomplished. The summary should be:
            - One line, under 100 characters
            - Past tense verb + key noun (like a git commit subject)
            - Drop articles and connectors
            
            Examples:
            - Searched authentication logic in auth/
            - Read 15 files matching *.java pattern
            - Listed directory contents of /src/main
            - Executed 'npm test' with 3 failures
            """;

    // ===== 截断策略常量 =====
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
    /** LLM 提供者注册表 — 可为 null（旧构造函数兼容模式，LLM 摘要功能禁用） */
    private final LlmProviderRegistry providerRegistry;

    /**
     * 旧构造函数 — 向后兼容，LLM 摘要功能禁用，仅使用截断策略。
     */
    public ToolResultSummarizer(TokenCounter tokenCounter) {
        this(null, tokenCounter);
    }

    /**
     * 新构造函数 — 启用 LLM 摘要功能。
     *
     * @param providerRegistry LLM 提供者注册表（用于调用轻量级模型生成摘要）
     * @param tokenCounter     token 计数器
     */
    @Autowired
    public ToolResultSummarizer(LlmProviderRegistry providerRegistry,
                                 TokenCounter tokenCounter) {
        this.providerRegistry = providerRegistry;
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

    // ==================== LLM 智能摘要 (P-AG-01) ====================

    /**
     * 对工具输出生成摘要（如果超过阈值）。
     * 采用"内部异步、外部同步"模式：对外同步 API，内部异步调用 LLM + 超时等待。
     * 失败时自动回退到截断策略。
     *
     * @param toolName   工具名称
     * @param toolInput  工具输入
     * @param toolOutput 原始工具输出
     * @param maxTokens  输出的最大 token 数阈值
     * @return 摘要文本，或截断后的输出（如果未超阈值或生成失败）
     */
    public String summarizeIfNeeded(String toolName, String toolInput,
                                     String toolOutput, int maxTokens) {
        if (toolOutput == null || toolOutput.isBlank()) return toolOutput;

        int estimatedTokens = tokenCounter.estimateTokens(toolOutput);
        if (estimatedTokens <= maxTokens) {
            return toolOutput; // 未超阈值，直接返回
        }

        // LLM 摘要功能未启用（旧构造函数兼容模式），直接截断
        if (providerRegistry == null) {
            log.debug("LLM 摘要未启用 (providerRegistry=null), 降级截断: tool={}", toolName);
            return truncate(toolOutput, maxTokens);
        }

        // 内部异步调用 LLM，用 CompletableFuture.get() 同步等待结果
        try {
            return CompletableFuture.supplyAsync(() ->
                            generateSummary(toolName, toolInput, toolOutput))
                    .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("工具摘要生成失败, 降级为截断: tool={}, error={}", toolName, e.getMessage());
            return truncate(toolOutput, maxTokens);
        }
    }

    /**
     * 异步生成摘要（用于非阻塞场景）。
     */
    public CompletableFuture<String> summarizeAsync(String toolName, String toolInput,
                                                     String toolOutput, int maxTokens) {
        return CompletableFuture.supplyAsync(() ->
                        summarizeIfNeeded(toolName, toolInput, toolOutput, maxTokens))
                .orTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(e -> {
                    log.warn("异步工具摘要超时/失败: {}", e.getMessage());
                    return truncate(toolOutput, maxTokens);
                });
    }

    /**
     * 调用轻量级 LLM 生成工具结果摘要。
     */
    private String generateSummary(String toolName, String toolInput, String toolOutput) {
        String model = providerRegistry.resolveModelAlias("haiku");
        LlmProvider provider = providerRegistry.getProvider(model);

        String userPrompt = "Tool: " + toolName + "\n"
                + "Input: " + truncateStr(toolInput, MAX_INPUT_LENGTH) + "\n"
                + "Output: " + truncateStr(toolOutput, MAX_INPUT_LENGTH) + "\n\n"
                + "Summary:";

        log.debug("生成工具摘要: tool={}, model={}, inputLen={}, outputLen={}",
                toolName, model, toolInput != null ? toolInput.length() : 0, toolOutput.length());

        String response = provider.chatSync(model, SUMMARY_SYSTEM_PROMPT, userPrompt,
                SUMMARY_MAX_TOKENS, null, TIMEOUT_MS);

        return "[工具摘要] " + response.trim() + "\n\n"
                + "[原始输出已压缩, 原长度: " + toolOutput.length() + " 字符]";
    }

    /**
     * 按 token 预算截断文本（LLM 摘要降级时使用）。
     */
    private String truncate(String text, int maxTokens) {
        int maxChars = maxTokens * 4; // 粗略估算: 1 token ≈ 4 字符
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n... [已截断, 总长 " + text.length() + " 字符]";
    }

    /**
     * 截断字符串到指定最大长度。
     */
    private String truncateStr(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
