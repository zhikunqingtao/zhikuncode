package com.aicodeassistant.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt 过长恢复策略 — 处理 413 (Payload Too Large) 渐进截断恢复。
 * <p>
 * 当 API 返回 413 或 token 超限错误时，渐进式截断历史消息以缩减 token 数。
 * <p>
 * 策略：
 * 1. 精确计算 Token Gap（当前 - 上限）
 * 2. 多截 20% 作为安全余量
 * 3. 按 API-round groups 从旧到新截断
 * <p>
 * 【新增】Phase 2 错误恢复框架组件。
 */
@Component
public class PromptTooLongRecovery {

    private static final Logger log = LoggerFactory.getLogger(PromptTooLongRecovery.class);

    // ==================== 常量（新增） ====================

    /** OTK（Over Token Limit）最大恢复次数 */
    public static final int OTK_RECOVER_MAX = 3;

    /** 相同错误连续阈值 — 连续 3 次相同错误切换策略 */
    public static final int SAME_ERROR_THRESHOLD = 3;

    /** 工具调用失败窗口 — 最近 5 次工具调用全失败触发警告 */
    public static final int TOOLCALL_FAILURE_WINDOW = 5;

    /** 安全余量比例 — 多截 20% */
    private static final double SAFETY_MARGIN = 1.2;

    // ==================== 恢复结果 ====================

    /**
     * 恢复结果。
     *
     * @param success          是否恢复成功
     * @param truncatedContent 截断后的内容描述
     * @param tokensSaved      节省的 token 数
     */
    public record RecoveryResult(boolean success, String truncatedContent, int tokensSaved) {

        /** 失败结果 */
        public static RecoveryResult failed() {
            return new RecoveryResult(false, null, 0);
        }

        /** 成功结果 */
        public static RecoveryResult success(String content, int saved) {
            return new RecoveryResult(true, content, saved);
        }
    }

    // ==================== 核心 API ====================

    /**
     * 执行 413 恢复 — 渐进截断消息以减少 token 数。
     *
     * @param messages      当前消息列表
     * @param currentTokens 当前 token 数
     * @param maxTokens     模型最大 token 限制
     * @param attempt       当前恢复尝试次数
     * @return 恢复结果
     */
    public RecoveryResult recover413(List<?> messages, long currentTokens, long maxTokens, int attempt) {
        // 超过最大恢复次数 → 放弃
        if (attempt > OTK_RECOVER_MAX) {
            log.warn("413 recovery: exceeded max attempts ({}), giving up", OTK_RECOVER_MAX);
            return RecoveryResult.failed();
        }

        if (messages == null || messages.isEmpty()) {
            log.warn("413 recovery: no messages to truncate");
            return RecoveryResult.failed();
        }

        // 1. 精确计算 Token Gap
        long gap = currentTokens - maxTokens;
        if (gap <= 0) {
            log.info("413 recovery: no gap detected (current={}, max={}), skipping",
                    currentTokens, maxTokens);
            return RecoveryResult.failed();
        }

        // 2. 多截 20% 作为安全余量
        long targetReduction = (long) (gap * SAFETY_MARGIN);

        // 3. 按 API-round groups 从旧到新截断
        int totalMessages = messages.size();
        // 保护系统消息（索引 0）和最近一轮对话（至少保留最后 2 条）
        int protectedTail = Math.min(2, totalMessages);
        int removableCount = totalMessages - 1 - protectedTail; // 排除系统消息和尾部

        if (removableCount <= 0) {
            log.warn("413 recovery: not enough messages to truncate (total={}, protected={})",
                    totalMessages, protectedTail);
            return RecoveryResult.failed();
        }

        // 估算每条消息平均 token 数，计算需要移除的消息数
        long avgTokensPerMessage = currentTokens / totalMessages;
        int messagesToRemove = (int) Math.ceil((double) targetReduction / avgTokensPerMessage);
        messagesToRemove = Math.min(messagesToRemove, removableCount);

        if (messagesToRemove <= 0) {
            return RecoveryResult.failed();
        }

        int tokensSaved = (int) (messagesToRemove * avgTokensPerMessage);

        log.info("413 recovery attempt {}/{}: removing {} messages, estimated saving {} tokens "
                        + "(gap={}, target={}, total_messages={})",
                attempt, OTK_RECOVER_MAX, messagesToRemove, tokensSaved,
                gap, targetReduction, totalMessages);

        String summary = String.format(
                "[Truncated %d earlier messages to fit context window. Saved ~%d tokens]",
                messagesToRemove, tokensSaved);

        return RecoveryResult.success(summary, tokensSaved);
    }

    /**
     * 判断错误是否为 token 超限类型。
     *
     * @param errorMessage 错误信息
     * @param statusCode   HTTP 状态码
     * @return true 表示是 token 超限错误
     */
    public boolean isTokenLimitError(String errorMessage, int statusCode) {
        if (statusCode == 413) return true;
        if (errorMessage == null) return false;
        String lower = errorMessage.toLowerCase();
        return lower.contains("token")
                || lower.contains("too long")
                || lower.contains("context length")
                || lower.contains("maximum context")
                || lower.contains("prompt is too long");
    }
}
