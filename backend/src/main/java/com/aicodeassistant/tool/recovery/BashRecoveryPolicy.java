package com.aicodeassistant.tool.recovery;

import com.aicodeassistant.tool.bash.BashErrorClassifier;
import com.aicodeassistant.tool.bash.BashErrorClassifier.ErrorClassification;
import com.aicodeassistant.tool.bash.BashErrorClassifier.ErrorType;
import com.aicodeassistant.tool.recovery.ToolRecoveryFramework.RecoveryContext;
import com.aicodeassistant.tool.recovery.ToolRecoveryFramework.RecoveryDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bash 工具恢复策略 — 基于 BashErrorClassifier 的分类结果决定恢复动作。
 * <p>
 * 将 {@link BashErrorClassifier} 的错误分类映射为具体的恢复决策。
 */
@Component
public class BashRecoveryPolicy implements ToolRecoveryPolicy {

    private static final Logger log = LoggerFactory.getLogger(BashRecoveryPolicy.class);

    /** 最大尝试次数 — 超过此值直接上报用户 */
    private static final int MAX_ATTEMPTS = 3;

    private final BashErrorClassifier errorClassifier;

    public BashRecoveryPolicy(BashErrorClassifier errorClassifier) {
        this.errorClassifier = errorClassifier;
    }

    @Override
    public boolean canHandle(RecoveryContext context) {
        return "Bash".equals(context.toolName());
    }

    @Override
    public RecoveryDecision recover(RecoveryContext context) {
        // 超过最大尝试次数 → 上报用户
        if (context.attemptCount() >= MAX_ATTEMPTS) {
            log.info("Bash recovery: max attempts ({}) reached, escalating to user", MAX_ATTEMPTS);
            return RecoveryDecision.escalateToUser(
                    "Bash command failed " + context.attemptCount()
                            + " times. Manual intervention required.");
        }

        // 使用 BashErrorClassifier 进行分类
        String command = extractCommand(context);
        ErrorClassification classification = errorClassifier.classify(
                context.exitCode(), context.errorMessage(), command);

        log.debug("Bash recovery classification: type={}, category={}, command={}",
                classification.type(), classification.category(), command);

        // 将错误分类映射为恢复决策
        return switch (classification.type()) {
            case RETRYABLE -> RecoveryDecision.retrySame(classification.suggestion());
            case NON_RETRYABLE -> RecoveryDecision.reportToLlm(classification.suggestion());
            case NEEDS_HUMAN -> RecoveryDecision.escalateToUser(classification.suggestion());
            case TIMEOUT -> RecoveryDecision.reportToLlm(
                    "Command timed out. " + classification.suggestion());
        };
    }

    /**
     * 从恢复上下文中提取原始命令。
     */
    private String extractCommand(RecoveryContext context) {
        Object input = context.originalInput();
        if (input instanceof java.util.Map<?, ?> map) {
            Object cmd = map.get("command");
            if (cmd instanceof String s) return s;
        }
        return "";
    }
}
