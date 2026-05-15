package com.aicodeassistant.tool.recovery;

import com.aicodeassistant.tool.recovery.ToolRecoveryFramework.RecoveryAction;
import com.aicodeassistant.tool.recovery.ToolRecoveryFramework.RecoveryContext;
import com.aicodeassistant.tool.recovery.ToolRecoveryFramework.RecoveryDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ToolRecoveryFrameworkTest {

    @Test
    @DisplayName("TC-RECOVERY-001: 策略链匹配第一个 canHandle=true 的策略")
    void attemptRecovery_shouldMatchFirstCanHandleTruePolicy() {
        // Arrange: 3 个 Mock Policy
        ToolRecoveryPolicy policy1 = mock(ToolRecoveryPolicy.class);
        ToolRecoveryPolicy policy2 = mock(ToolRecoveryPolicy.class);
        ToolRecoveryPolicy policy3 = mock(ToolRecoveryPolicy.class);

        RecoveryContext context = new RecoveryContext(
                "Bash", "ls -la", null, 1, Duration.ofSeconds(5), "error", 1
        );

        // policy1: canHandle=false
        when(policy1.canHandle(context)).thenReturn(false);
        // policy2: canHandle=true, 返回 REPORT_TO_LLM
        when(policy2.canHandle(context)).thenReturn(true);
        RecoveryDecision expectedDecision = RecoveryDecision.reportToLlm("handled by policy2");
        when(policy2.recover(context)).thenReturn(expectedDecision);
        // policy3: canHandle=true（不应被调用）
        when(policy3.canHandle(context)).thenReturn(true);

        ToolRecoveryFramework framework = new ToolRecoveryFramework(List.of(policy1, policy2, policy3));

        // Act
        Optional<RecoveryDecision> result = framework.attemptRecovery(context);

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get().action()).isEqualTo(RecoveryAction.REPORT_TO_LLM);
        assertThat(result.get().hintForLlm()).isEqualTo("handled by policy2");

        // 验证调用顺序
        verify(policy1).canHandle(context);
        verify(policy2).canHandle(context);
        verify(policy2).recover(context);
        // policy3 不应被调用 canHandle 或 recover
        verify(policy3, never()).canHandle(context);
        verify(policy3, never()).recover(any());
    }
}
