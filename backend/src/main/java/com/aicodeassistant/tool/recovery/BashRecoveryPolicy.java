package com.aicodeassistant.tool.recovery;

import com.aicodeassistant.tool.recovery.ToolRecoveryFramework.RecoveryContext;
import com.aicodeassistant.tool.recovery.ToolRecoveryFramework.RecoveryDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bash 工具恢复策略 — 基于退出码和 stderr 内容决定恢复动作。
 * <p>
 * 与 {@link com.aicodeassistant.tool.bash.BashErrorClassifier} 协作，
 * 将分类结果映射为恢复决策。
 */
@Component
public class BashRecoveryPolicy implements ToolRecoveryPolicy {

    private static final Logger log = LoggerFactory.getLogger(BashRecoveryPolicy.class);

    /** 最大尝试次数 — 超过此值直接上报用户 */
    private static final int MAX_ATTEMPTS = 3;

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

        int exitCode = context.exitCode();
        String errorMsg = context.errorMessage() != null ? context.errorMessage().toLowerCase() : "";

        // 退出码 127 → 命令不存在 → 报告给 LLM 建议替代命令
        if (exitCode == 127) {
            return RecoveryDecision.reportToLlm(
                    "Command not found (exit code 127). "
                            + "Please try an alternative command or verify the command is installed. "
                            + "Common alternatives: python→python3, pip→pip3, node→check nvm.");
        }

        // 退出码 126 → 权限不足 → 报告给 LLM 建议 chmod
        if (exitCode == 126) {
            return RecoveryDecision.reportToLlm(
                    "Permission denied - file is not executable (exit code 126). "
                            + "Consider using 'chmod +x <file>' to make it executable, "
                            + "or run with an appropriate interpreter (e.g., 'bash script.sh').");
        }

        // 网络错误 → 重试（带延迟）
        if (isNetworkError(errorMsg)) {
            log.info("Bash recovery: network error detected, suggesting retry");
            return RecoveryDecision.retrySame(
                    "Network connectivity issue detected (connection refused/timeout). "
                            + "Retrying after a short delay.");
        }

        // 编译错误 → 报告给 LLM 格式化错误信息
        if (isCompilationError(errorMsg)) {
            return RecoveryDecision.reportToLlm(
                    "Compilation error detected. Please review and fix the source code errors:\n"
                            + context.errorMessage());
        }

        // 测试失败 → 报告给 LLM 格式化失败测试列表
        if (isTestFailure(errorMsg)) {
            return RecoveryDecision.reportToLlm(
                    "Test execution failed. Please review the failing tests and fix the issues:\n"
                            + context.errorMessage());
        }

        // 默认：报告给 LLM
        return RecoveryDecision.reportToLlm(
                "Bash command failed with exit code " + exitCode + ". "
                        + "Error: " + context.errorMessage());
    }

    private boolean isNetworkError(String errorMsg) {
        return errorMsg.contains("connection refused")
                || errorMsg.contains("connection timed out")
                || errorMsg.contains("timeout")
                || errorMsg.contains("econnreset")
                || errorMsg.contains("econnrefused")
                || errorMsg.contains("network is unreachable")
                || errorMsg.contains("temporary failure in name resolution");
    }

    private boolean isCompilationError(String errorMsg) {
        return errorMsg.matches("(?s).*\\.java:\\d+: error:.*")
                || errorMsg.matches("(?s).*\\.[chm]:\\d+:\\d+: error:.*")
                || errorMsg.matches("(?s).*\\.tsx?\\(\\d+,\\d+\\): error ts.*")
                || errorMsg.contains("error[e");
    }

    private boolean isTestFailure(String errorMsg) {
        return errorMsg.contains("test failed")
                || errorMsg.contains("tests failed")
                || errorMsg.contains("build failure")
                || errorMsg.contains("test failures")
                || errorMsg.contains("assertion failed")
                || errorMsg.contains("assertionerror");
    }
}
