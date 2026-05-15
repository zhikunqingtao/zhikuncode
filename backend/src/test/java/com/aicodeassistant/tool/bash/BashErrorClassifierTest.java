package com.aicodeassistant.tool.bash;

import com.aicodeassistant.tool.bash.BashErrorClassifier.ErrorClassification;
import com.aicodeassistant.tool.bash.BashErrorClassifier.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BashErrorClassifier 单元测试。
 * <p>
 * 覆盖退出码分类、stderr 关键字匹配、默认分类、命令替代建议等场景。
 */
class BashErrorClassifierTest {

    private BashErrorClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new BashErrorClassifier();
    }

    // ═══════════════════════════════════════════════════════════
    // 退出码分类
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("TC-BASH-001: 退出码 137 分类为 TIMEOUT")
    void exitCode137_classifiedAsTimeout() {
        ErrorClassification result = classifier.classify(137, "", "some-cmd");
        assertThat(result.type()).isEqualTo(ErrorType.TIMEOUT);
        assertThat(result.category()).isEqualTo("Process killed by signal");
    }

    @Test
    @DisplayName("TC-BASH-002: 退出码 127 分类为 NON_RETRYABLE")
    void exitCode127_classifiedAsNonRetryable() {
        ErrorClassification result = classifier.classify(127, "", "missing-cmd");
        assertThat(result.type()).isEqualTo(ErrorType.NON_RETRYABLE);
        assertThat(result.category()).isEqualTo("Command not found");
    }

    @Test
    @DisplayName("TC-BASH-003: 退出码 126 分类为 NEEDS_HUMAN")
    void exitCode126_classifiedAsNeedsHuman() {
        ErrorClassification result = classifier.classify(126, "", "./script.sh");
        assertThat(result.type()).isEqualTo(ErrorType.NEEDS_HUMAN);
        assertThat(result.category()).isEqualTo("Permission denied (not executable)");
        assertThat(result.suggestion()).contains("chmod +x");
    }

    // ═══════════════════════════════════════════════════════════
    // stderr 关键字匹配
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("TC-BASH-004: stderr 含网络错误分类为 RETRYABLE")
    void stderrWithNetworkError_classifiedAsRetryable() {
        ErrorClassification result = classifier.classify(1, "Connection refused", "curl http://localhost");
        assertThat(result.type()).isEqualTo(ErrorType.RETRYABLE);
        assertThat(result.category()).isEqualTo("Network error");
    }

    @Test
    @DisplayName("TC-BASH-005: stderr 含权限错误分类为 NEEDS_HUMAN")
    void stderrWithPermissionDenied_classifiedAsNeedsHuman() {
        ErrorClassification result = classifier.classify(1, "Permission denied", "cat /etc/shadow");
        assertThat(result.type()).isEqualTo(ErrorType.NEEDS_HUMAN);
        assertThat(result.category()).isEqualTo("Permission denied");
    }

    @Test
    @DisplayName("TC-BASH-006: stderr 含磁盘满分类为 NEEDS_HUMAN")
    void stderrWithDiskFull_classifiedAsNeedsHuman() {
        ErrorClassification result = classifier.classify(1, "No space left on device", "cp bigfile /tmp");
        assertThat(result.type()).isEqualTo(ErrorType.NEEDS_HUMAN);
        assertThat(result.category()).isEqualTo("Disk full");
    }

    @Test
    @DisplayName("TC-BASH-007: stderr 含编译错误分类为 NON_RETRYABLE")
    void stderrWithCompilationError_classifiedAsNonRetryable() {
        ErrorClassification result = classifier.classify(1,
                "Main.java:10: error: cannot find symbol", "javac Main.java");
        assertThat(result.type()).isEqualTo(ErrorType.NON_RETRYABLE);
        assertThat(result.category()).isEqualTo("Compilation error");
    }

    @Test
    @DisplayName("TC-BASH-008: stderr 含资源锁分类为 RETRYABLE")
    void stderrWithResourceLock_classifiedAsRetryable() {
        ErrorClassification result = classifier.classify(1,
                "resource temporarily unavailable", "apt-get install vim");
        assertThat(result.type()).isEqualTo(ErrorType.RETRYABLE);
        assertThat(result.category()).isEqualTo("Resource locked");
    }

    // ═══════════════════════════════════════════════════════════
    // 默认分类与建议
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("TC-BASH-009: 未知退出码默认分类为 NON_RETRYABLE")
    void unknownExitCode_classifiedAsNonRetryable() {
        ErrorClassification result = classifier.classify(42, "", "some-random-cmd");
        assertThat(result.type()).isEqualTo(ErrorType.NON_RETRYABLE);
        assertThat(result.category()).isEqualTo("Command failed");
        assertThat(result.suggestion()).contains("42");
    }

    @Test
    @DisplayName("TC-BASH-010: 命令替代建议 — python 建议 python3")
    void commandNotFound_suggestsPython3() {
        ErrorClassification result = classifier.classify(127, "", "python foo.py");
        assertThat(result.type()).isEqualTo(ErrorType.NON_RETRYABLE);
        assertThat(result.suggestion()).contains("python3");
    }
}
