package com.aicodeassistant.tool.bash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * BashErrorClassifier — 分析命令执行结果，分类错误类型。
 * <p>
 * 根据退出码和 stderr 内容，判断错误是否可重试、需要人工干预，
 * 或是不可恢复的错误，并提供建议。
 */
@Component
public class BashErrorClassifier {

    private static final Logger log = LoggerFactory.getLogger(BashErrorClassifier.class);

    /**
     * 错误类型枚举。
     */
    public enum ErrorType {
        RETRYABLE,       // 网络超时、临时文件锁等可重试错误
        NON_RETRYABLE,   // 命令不存在(127)、语法错误等
        NEEDS_HUMAN,     // 权限错误、磁盘满等需要人工干预
        TIMEOUT          // 命令执行超时
    }

    /**
     * 错误分类结果。
     *
     * @param type       错误类型
     * @param category   错误分类描述
     * @param suggestion 修复建议
     */
    public record ErrorClassification(ErrorType type, String category, String suggestion) {}

    /**
     * 分析退出码和 stderr 内容，返回错误分类。
     *
     * @param exitCode 进程退出码
     * @param stderr   标准错误输出内容
     * @param command  执行的命令
     * @return 错误分类结果
     */
    public ErrorClassification classify(int exitCode, String stderr, String command) {
        log.debug("Classifying bash error: exitCode={}, command={}", exitCode, command);
        String stderrLower = stderr != null ? stderr.toLowerCase() : "";

        // 退出码 137/143 → 被信号杀死（SIGKILL/SIGTERM）
        if (exitCode == 137 || exitCode == 143) {
            log.debug("Classified as TIMEOUT: process killed by signal (exitCode={})", exitCode);
            return new ErrorClassification(
                    ErrorType.TIMEOUT,
                    "Process killed by signal",
                    "Command was terminated by signal. Consider increasing timeout or optimizing the command."
            );
        }

        // 退出码 127 → 命令不存在
        if (exitCode == 127) {
            log.debug("Classified as NON_RETRYABLE: command not found (cmd={})", command);
            String suggestion = buildCommandNotFoundSuggestion(command);
            return new ErrorClassification(
                    ErrorType.NON_RETRYABLE,
                    "Command not found",
                    suggestion
            );
        }

        // 退出码 126 → 权限不足（文件不可执行）
        if (exitCode == 126) {
            log.debug("Classified as NEEDS_HUMAN: permission denied (not executable)");
            return new ErrorClassification(
                    ErrorType.NEEDS_HUMAN,
                    "Permission denied (not executable)",
                    "The file exists but is not executable. Try: chmod +x <file>"
            );
        }

        // stderr 内容分析
        if (!stderrLower.isEmpty()) {
            // 网络相关可重试错误
            if (stderrLower.contains("connection refused")
                    || stderrLower.contains("connection timed out")
                    || stderrLower.contains("timeout")
                    || stderrLower.contains("econnreset")
                    || stderrLower.contains("econnrefused")
                    || stderrLower.contains("network is unreachable")
                    || stderrLower.contains("temporary failure in name resolution")) {
                log.debug("Classified as RETRYABLE: network error detected");
                return new ErrorClassification(
                        ErrorType.RETRYABLE,
                        "Network error",
                        "Network connectivity issue detected. Retry after checking network status."
                );
            }

            // 权限错误
            if (stderrLower.contains("permission denied")
                    || stderrLower.contains("operation not permitted")) {
                log.debug("Classified as NEEDS_HUMAN: permission denied");
                return new ErrorClassification(
                        ErrorType.NEEDS_HUMAN,
                        "Permission denied",
                        "Insufficient permissions. Check file/directory ownership and permissions."
                );
            }

            // 磁盘满
            if (stderrLower.contains("no space left on device")) {
                log.debug("Classified as NEEDS_HUMAN: disk full");
                return new ErrorClassification(
                        ErrorType.NEEDS_HUMAN,
                        "Disk full",
                        "No disk space available. Free up space before retrying."
                );
            }

            // 命令不存在（stderr 模式）
            if (stderrLower.contains("command not found")
                    || stderrLower.contains("not found")) {
                log.debug("Classified as NON_RETRYABLE: command not found (stderr pattern)");
                String suggestion = buildCommandNotFoundSuggestion(command);
                return new ErrorClassification(
                        ErrorType.NON_RETRYABLE,
                        "Command not found",
                        suggestion
                );
            }

            // 编译错误（javac/gcc/tsc 等）
            if (isCompilationError(stderrLower)) {
                log.debug("Classified as NON_RETRYABLE: compilation error detected");
                return new ErrorClassification(
                        ErrorType.NON_RETRYABLE,
                        "Compilation error",
                        "Fix the source code errors indicated in the output before retrying."
                );
            }

            // 临时文件锁（可重试）
            if (stderrLower.contains("resource temporarily unavailable")
                    || stderrLower.contains("lock file")
                    || stderrLower.contains("could not get lock")) {
                log.debug("Classified as RETRYABLE: resource locked");
                return new ErrorClassification(
                        ErrorType.RETRYABLE,
                        "Resource locked",
                        "A resource lock is held by another process. Retry after a short delay."
                );
            }
        }

        // 默认 → NON_RETRYABLE
        log.debug("Classified as NON_RETRYABLE: unrecognized error (exitCode={})", exitCode);
        return new ErrorClassification(
                ErrorType.NON_RETRYABLE,
                "Command failed",
                "Command exited with code " + exitCode + ". Review the output for details."
        );
    }

    /**
     * 检测是否为编译错误输出。
     */
    private boolean isCompilationError(String stderrLower) {
        // javac 错误格式: "file.java:10: error: ..."
        if (stderrLower.matches("(?s).*\\.java:\\d+: error:.*")) return true;
        // gcc/g++ 错误格式: "file.c:10:5: error: ..."
        if (stderrLower.matches("(?s).*\\.[chm]:\\d+:\\d+: error:.*")) return true;
        // TypeScript 错误格式: "file.ts(10,5): error TS..."
        if (stderrLower.matches("(?s).*\\.tsx?\\(\\d+,\\d+\\): error ts.*")) return true;
        // Rust 错误格式: "error[E0001]:"
        if (stderrLower.contains("error[e")) return true;
        return false;
    }

    /**
     * 构建命令未找到的替代建议。
     */
    private String buildCommandNotFoundSuggestion(String command) {
        if (command == null || command.isBlank()) {
            return "Command not found. Verify the command name and ensure it is installed.";
        }
        String firstToken = command.trim().split("\\s+")[0];
        return switch (firstToken) {
            case "python" -> "Try 'python3' instead of 'python'.";
            case "pip" -> "Try 'pip3' instead of 'pip'.";
            case "node" -> "Node.js may not be installed. Install via 'brew install node' or 'nvm install --lts'.";
            case "mvn" -> "Maven may not be installed. Install via 'brew install maven' or check MAVEN_HOME.";
            case "gradle" -> "Gradle may not be installed. Try using the Gradle wrapper: './gradlew'.";
            case "docker" -> "Docker may not be installed or not running. Check Docker Desktop status.";
            default -> "Command '" + firstToken + "' not found. Verify it is installed and in PATH.";
        };
    }
}
