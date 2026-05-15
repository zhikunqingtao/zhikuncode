package com.aicodeassistant.tool.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 工具恢复框架 — 统一恢复入口。
 * <p>
 * 通过 Spring 自动收集所有 {@link ToolRecoveryPolicy} 实现，
 * 按照策略链模式匹配第一个可处理的策略执行恢复。
 */
@Component
public class ToolRecoveryFramework {

    private static final Logger log = LoggerFactory.getLogger(ToolRecoveryFramework.class);

    private final List<ToolRecoveryPolicy> policies;

    public ToolRecoveryFramework(List<ToolRecoveryPolicy> policies) {
        this.policies = policies;
        log.info("ToolRecoveryFramework initialized with {} policies", policies.size());
    }

    // ==================== 恢复上下文 ====================

    /**
     * 恢复上下文 — 封装工具执行失败的完整信息。
     *
     * @param toolName      工具名称
     * @param originalInput 原始输入
     * @param failedResult  失败结果
     * @param attemptCount  已尝试次数
     * @param totalElapsed  总耗时
     * @param errorMessage  错误信息
     * @param exitCode      退出码（Bash 工具有效，其他工具为 -1）
     */
    public record RecoveryContext(
            String toolName,
            Object originalInput,
            Object failedResult,
            int attemptCount,
            Duration totalElapsed,
            String errorMessage,
            int exitCode
    ) {}

    // ==================== 恢复决策 ====================

    /**
     * 恢复决策 — 描述应采取的恢复动作。
     *
     * @param action            恢复动作类型
     * @param modifiedInput     修改后的输入（用于 RETRY_SAME）
     * @param alternativeToolName 替代工具名（用于 TRY_ALTERNATIVE）
     * @param hintForLlm       给 LLM 的提示信息
     */
    public record RecoveryDecision(
            RecoveryAction action,
            String modifiedInput,
            String alternativeToolName,
            String hintForLlm
    ) {
        /** 快捷构造：仅指定动作和 LLM 提示 */
        public static RecoveryDecision reportToLlm(String hint) {
            return new RecoveryDecision(RecoveryAction.REPORT_TO_LLM, null, null, hint);
        }

        /** 快捷构造：原参数重试 */
        public static RecoveryDecision retrySame(String hint) {
            return new RecoveryDecision(RecoveryAction.RETRY_SAME, null, null, hint);
        }

        /** 快捷构造：上报用户 */
        public static RecoveryDecision escalateToUser(String hint) {
            return new RecoveryDecision(RecoveryAction.ESCALATE_TO_USER, null, null, hint);
        }

        /** 快捷构造：中止并总结 */
        public static RecoveryDecision abortWithSummary(String hint) {
            return new RecoveryDecision(RecoveryAction.ABORT_WITH_SUMMARY, null, null, hint);
        }
    }

    // ==================== 恢复动作枚举 ====================

    /**
     * 恢复动作类型。
     */
    public enum RecoveryAction {
        /** 原参数重试 */
        RETRY_SAME,
        /** 尝试替代工具 */
        TRY_ALTERNATIVE,
        /** 简化任务 */
        SIMPLIFY_TASK,
        /** 上报用户 */
        ESCALATE_TO_USER,
        /** 中止并总结 */
        ABORT_WITH_SUMMARY,
        /** 报告给 LLM 自行决策 */
        REPORT_TO_LLM
    }

    // ==================== 核心方法 ====================

    /**
     * 尝试恢复 — 遍历策略链，返回第一个匹配策略的恢复决策。
     *
     * @param context 恢复上下文
     * @return 恢复决策（如果没有策略能处理则为空）
     */
    public Optional<RecoveryDecision> attemptRecovery(RecoveryContext context) {
        for (ToolRecoveryPolicy policy : policies) {
            if (policy.canHandle(context)) {
                log.debug("Recovery policy matched: {} for tool {}",
                        policy.getClass().getSimpleName(), context.toolName());
                try {
                    RecoveryDecision decision = policy.recover(context);
                    log.info("Recovery decision for tool {}: action={}, hint={}",
                            context.toolName(), decision.action(), decision.hintForLlm());
                    return Optional.of(decision);
                } catch (Exception e) {
                    log.error("Recovery policy {} threw exception: {}",
                            policy.getClass().getSimpleName(), e.getMessage(), e);
                }
            }
        }
        log.debug("No recovery policy matched for tool {} (attempt {})",
                context.toolName(), context.attemptCount());
        return Optional.empty();
    }
}
