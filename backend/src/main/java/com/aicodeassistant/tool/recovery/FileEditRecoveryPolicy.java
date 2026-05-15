package com.aicodeassistant.tool.recovery;

import com.aicodeassistant.tool.recovery.ToolRecoveryFramework.RecoveryContext;
import com.aicodeassistant.tool.recovery.ToolRecoveryFramework.RecoveryDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 文件编辑工具恢复策略 — 处理 FileEdit 和 FileWrite 工具的失败恢复。
 * <p>
 * 常见失败场景：
 * - 冲突检测失败（文件已被其他进程修改）
 * - 内容不匹配（old_string 找不到）
 * - 路径不存在
 * - 权限错误
 */
@Component
public class FileEditRecoveryPolicy implements ToolRecoveryPolicy {

    private static final Logger log = LoggerFactory.getLogger(FileEditRecoveryPolicy.class);

    @Override
    public boolean canHandle(RecoveryContext context) {
        String toolName = context.toolName();
        return "FileEdit".equals(toolName) || "FileWrite".equals(toolName);
    }

    @Override
    public RecoveryDecision recover(RecoveryContext context) {
        String errorMsg = context.errorMessage() != null ? context.errorMessage().toLowerCase() : "";

        // 冲突检测失败 — 文件已被修改
        if (isConflictError(errorMsg)) {
            log.info("FileEdit recovery: conflict detected, advising re-read");
            return RecoveryDecision.reportToLlm(
                    "File conflict detected - the file has been modified since last read. "
                            + "Please re-read the file to get the current content before making edits.");
        }

        // 内容不匹配 — old_string 找不到
        if (isContentMismatch(errorMsg)) {
            log.info("FileEdit recovery: content mismatch, advising re-read");
            return RecoveryDecision.reportToLlm(
                    "Content mismatch - the specified text (old_string) was not found in the file. "
                            + "Please re-read the file to confirm the current content and try again "
                            + "with the exact text that exists in the file.");
        }

        // 路径不存在
        if (isPathNotFound(errorMsg)) {
            log.info("FileEdit recovery: path not found");
            return RecoveryDecision.reportToLlm(
                    "File path does not exist. Please verify the file path is correct. "
                            + "Use a file listing tool to check the directory structure.");
        }

        // 权限错误 → 上报用户
        if (isPermissionError(errorMsg)) {
            log.info("FileEdit recovery: permission error, escalating to user");
            return RecoveryDecision.escalateToUser(
                    "Permission denied when writing to file. "
                            + "Please check file permissions and ownership.");
        }

        // 默认：报告给 LLM
        return RecoveryDecision.reportToLlm(
                "File operation failed: " + context.errorMessage()
                        + ". Please verify the file state and try again.");
    }

    private boolean isConflictError(String errorMsg) {
        return errorMsg.contains("conflict")
                || errorMsg.contains("modified externally")
                || errorMsg.contains("stale")
                || errorMsg.contains("changed since");
    }

    private boolean isContentMismatch(String errorMsg) {
        return errorMsg.contains("not found in file")
                || errorMsg.contains("old_string")
                || errorMsg.contains("content mismatch")
                || errorMsg.contains("no match")
                || errorMsg.contains("does not match")
                || errorMsg.contains("string not found");
    }

    private boolean isPathNotFound(String errorMsg) {
        return errorMsg.contains("no such file")
                || errorMsg.contains("file not found")
                || errorMsg.contains("path does not exist")
                || errorMsg.contains("does not exist")
                || errorMsg.contains("not a file");
    }

    private boolean isPermissionError(String errorMsg) {
        return errorMsg.contains("permission denied")
                || errorMsg.contains("access denied")
                || errorMsg.contains("operation not permitted")
                || errorMsg.contains("read-only file system");
    }
}
