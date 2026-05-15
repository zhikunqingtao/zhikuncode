package com.aicodeassistant.engine.strategy;

import com.aicodeassistant.tool.recovery.ToolRecoveryFramework.RecoveryAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 错误恢复策略 — 根据尝试次数和错误类型决定恢复动作。
 * <p>
 * 策略升级路径：
 * - attempt=1 → RETRY_SAME（直接重试）
 * - attempt=2 → TRY_ALTERNATIVE 或 SIMPLIFY_TASK
 * - attempt>=3 → ESCALATE_TO_USER
 * <p>
 * RecoveryAction 复用 ToolRecoveryFramework 中的定义。
 */
@Component
public class ErrorRecoveryStrategy {

    private static final Logger log = LoggerFactory.getLogger(ErrorRecoveryStrategy.class);

    /**
     * 根据尝试次数和错误类型决定恢复动作。
     *
     * @param attemptCount 当前尝试次数（1-based）
     * @param errorType    错误类型描述
     * @return 恢复动作
     */
    public RecoveryAction decide(int attemptCount, String errorType) {
        log.debug("ErrorRecoveryStrategy.decide: attempt={}, errorType={}", attemptCount, errorType);

        if (attemptCount <= 1) {
            // 首次失败 → 直接重试
            log.info("Recovery decision: RETRY_SAME (attempt={})", attemptCount);
            return RecoveryAction.RETRY_SAME;
        }

        if (attemptCount == 2) {
            // 第二次失败 → 根据错误类型选择替代或简化
            if (isToolSpecificError(errorType)) {
                log.info("Recovery decision: TRY_ALTERNATIVE (attempt={}, errorType={})",
                        attemptCount, errorType);
                return RecoveryAction.TRY_ALTERNATIVE;
            }
            log.info("Recovery decision: SIMPLIFY_TASK (attempt={}, errorType={})",
                    attemptCount, errorType);
            return RecoveryAction.SIMPLIFY_TASK;
        }

        // 三次及以上 → 上报用户
        log.warn("Recovery decision: ESCALATE_TO_USER (attempt={}, errorType={})",
                attemptCount, errorType);
        return RecoveryAction.ESCALATE_TO_USER;
    }

    /**
     * 判断是否为工具相关的特定错误（可尝试替代工具）。
     */
    private boolean isToolSpecificError(String errorType) {
        if (errorType == null) return false;
        String lower = errorType.toLowerCase();
        return lower.contains("permission") || lower.contains("not found")
                || lower.contains("timeout") || lower.contains("unavailable");
    }
}
