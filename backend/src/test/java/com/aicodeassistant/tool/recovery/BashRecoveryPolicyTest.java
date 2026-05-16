package com.aicodeassistant.tool.recovery;

import com.aicodeassistant.tool.bash.BashErrorClassifier;
import com.aicodeassistant.tool.recovery.ToolRecoveryFramework.RecoveryAction;
import com.aicodeassistant.tool.recovery.ToolRecoveryFramework.RecoveryContext;
import com.aicodeassistant.tool.recovery.ToolRecoveryFramework.RecoveryDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BashRecoveryPolicyTest {

    private BashRecoveryPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new BashRecoveryPolicy(new BashErrorClassifier());
    }

    @Test
    @DisplayName("TC-RECOVERY-004: exitCode=127 返回 REPORT_TO_LLM")
    void recover_exitCode127_returnsReportToLlm() {
        // Arrange
        RecoveryContext context = new RecoveryContext(
                "Bash", Map.of("command", "unknown_cmd"), null, 1, Duration.ofSeconds(2),
                "command not found: unknown_cmd", 127
        );

        // Act
        assertThat(policy.canHandle(context)).isTrue();
        RecoveryDecision decision = policy.recover(context);

        // Assert
        assertThat(decision.action()).isEqualTo(RecoveryAction.REPORT_TO_LLM);
        assertThat(decision.hintForLlm()).containsIgnoringCase("not found");
    }

    @Test
    @DisplayName("TC-RECOVERY-005: 网络错误返回 RETRY_SAME")
    void recover_networkError_returnsRetrySame() {
        // Arrange
        RecoveryContext context = new RecoveryContext(
                "Bash", Map.of("command", "curl http://localhost:8080"), null, 1, Duration.ofSeconds(3),
                "curl: (7) Failed to connect to localhost port 8080: Connection refused", 1
        );

        // Act
        assertThat(policy.canHandle(context)).isTrue();
        RecoveryDecision decision = policy.recover(context);

        // Assert
        assertThat(decision.action()).isEqualTo(RecoveryAction.RETRY_SAME);
        assertThat(decision.hintForLlm()).containsIgnoringCase("network");
    }

    @Test
    @DisplayName("TC-RECOVERY-006: 权限错误 exitCode=126 返回 ESCALATE_TO_USER")
    void recover_exitCode126_returnsEscalateToUser() {
        // Arrange
        RecoveryContext context = new RecoveryContext(
                "Bash", Map.of("command", "./script.sh"), null, 1, Duration.ofSeconds(1),
                "bash: ./script.sh: Permission denied", 126
        );

        // Act
        assertThat(policy.canHandle(context)).isTrue();
        RecoveryDecision decision = policy.recover(context);

        // Assert
        assertThat(decision.action()).isEqualTo(RecoveryAction.ESCALATE_TO_USER);
        assertThat(decision.hintForLlm()).containsIgnoringCase("chmod +x");
    }
}
