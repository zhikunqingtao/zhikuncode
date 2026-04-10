package com.aicodeassistant.engine;

import com.aicodeassistant.llm.LlmProvider;
import com.aicodeassistant.llm.LlmProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * SideQueryService — 使用小/快模型执行低复杂度的辅助查询。
 * <p>
 * 用途: 生成 commit message、摘要、标题、代码注释等不需要主模型参与的轻量级任务。
 * 始终使用 {@link LlmProviderRegistry#getFastModel()} 获取的快速模型，
 * 以降低延迟和成本。
 *
 * @see LlmProviderRegistry#getFastModel()
 * @see LlmProvider#chatSync(String, String, String, int, String[], long)
 */
@Service
public class SideQueryService {

    private static final Logger log = LoggerFactory.getLogger(SideQueryService.class);

    /** 默认超时 15 秒 — side query 应当快速返回 */
    private static final long DEFAULT_TIMEOUT_MS = 15_000L;

    /** 默认最大输出 token */
    private static final int DEFAULT_MAX_TOKENS = 1024;

    private final LlmProviderRegistry providerRegistry;

    public SideQueryService(LlmProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    // ═══════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════

    /**
     * 通用 side query — 用小模型完成简单任务。
     *
     * @param systemPrompt 系统提示
     * @param userContent  用户内容
     * @param maxTokens    最大输出 token
     * @param timeoutMs    超时毫秒
     * @return LLM 响应文本，失败返回 null
     */
    public String query(String systemPrompt, String userContent, int maxTokens, long timeoutMs) {
        String model = providerRegistry.getFastModel();
        try {
            LlmProvider provider = providerRegistry.getProvider(model);
            String result = provider.chatSync(model, systemPrompt, userContent, maxTokens, null, timeoutMs);
            if (result != null && !result.isBlank()) {
                return result.strip();
            }
            log.warn("SideQuery returned empty response, model={}", model);
            return null;
        } catch (Exception e) {
            log.warn("SideQuery failed: model={}, error={}", model, e.getMessage());
            return null;
        }
    }

    /**
     * 通用 side query — 使用默认超时和 maxTokens。
     */
    public String query(String systemPrompt, String userContent) {
        return query(systemPrompt, userContent, DEFAULT_MAX_TOKENS, DEFAULT_TIMEOUT_MS);
    }

    // ═══════════════════════════════════════════
    // 预设任务
    // ═══════════════════════════════════════════

    /**
     * 生成 commit message。
     *
     * @param diff git diff 内容
     * @return 简洁的 commit message，或 null
     */
    public String generateCommitMessage(String diff) {
        String systemPrompt = """
                You are a git commit message generator. Write a concise, conventional commit message
                for the given diff. Use the format: <type>(<scope>): <description>
                Types: feat, fix, refactor, docs, style, test, chore.
                Keep the message under 72 characters. Return ONLY the commit message, nothing else.""";
        return query(systemPrompt, diff, 128, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 生成对话摘要/标题。
     *
     * @param conversationSnippet 对话片段（前几条消息）
     * @return 简短标题，或 null
     */
    public String generateTitle(String conversationSnippet) {
        String systemPrompt = """
                Generate a short title (max 50 chars) for this conversation.
                Return ONLY the title, no quotes, no explanation.""";
        return query(systemPrompt, conversationSnippet, 64, 10_000L);
    }

    /**
     * 生成代码摘要。
     *
     * @param code 代码片段
     * @return 1-2 句话的代码功能描述，或 null
     */
    public String summarizeCode(String code) {
        String systemPrompt = """
                Summarize what the following code does in 1-2 concise sentences.
                Focus on the main purpose and key operations. Return ONLY the summary.""";
        return query(systemPrompt, code, 256, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 检查是否可用（有 Provider 且 fast model 可解析）。
     */
    public boolean isAvailable() {
        try {
            String model = providerRegistry.getFastModel();
            providerRegistry.getProvider(model);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
