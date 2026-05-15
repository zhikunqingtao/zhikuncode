package com.aicodeassistant.engine;

import com.aicodeassistant.engine.PromptTooLongRecovery.RecoveryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptTooLongRecoveryTest {

    private PromptTooLongRecovery recovery;

    @BeforeEach
    void setUp() {
        recovery = new PromptTooLongRecovery();
    }

    @Test
    @DisplayName("TC-RECOVERY-008: 第一次 413 → 成功截断")
    void recover413_firstAttempt_successfulTruncation() {
        // Arrange: 构造足够多的消息以确保可截断
        List<String> messages = new ArrayList<>(Collections.nCopies(20, "message content placeholder"));
        long currentTokens = 200_000L;
        long maxTokens = 100_000L;

        // Act
        RecoveryResult result = recovery.recover413(messages, currentTokens, maxTokens, 1);

        // Assert
        assertThat(result.success()).isTrue();
        assertThat(result.tokensSaved()).isGreaterThan(0);
        assertThat(result.truncatedContent()).isNotNull();
    }

    @Test
    @DisplayName("TC-RECOVERY-009: 第二次 413 → 继续截断")
    void recover413_secondAttempt_continuesTruncation() {
        // Arrange: 仍超限场景，attempt=2
        List<String> messages = new ArrayList<>(Collections.nCopies(15, "message content placeholder"));
        long currentTokens = 150_000L;
        long maxTokens = 100_000L;

        // Act
        RecoveryResult result = recovery.recover413(messages, currentTokens, maxTokens, 2);

        // Assert
        assertThat(result.success()).isTrue();
        assertThat(result.tokensSaved()).isGreaterThan(0);
    }

    @Test
    @DisplayName("TC-RECOVERY-010: 第三次 413 → 最大尝试内仍可恢复")
    void recover413_thirdAttempt_lastChanceRecovery() {
        // Arrange: attempt=3 (== OTK_RECOVER_MAX)，最后一次机会
        List<String> messages = new ArrayList<>(Collections.nCopies(10, "message content placeholder"));
        long currentTokens = 120_000L;
        long maxTokens = 100_000L;

        // Act
        RecoveryResult result = recovery.recover413(messages, currentTokens, maxTokens, 3);

        // Assert
        assertThat(result.success()).isTrue();
        assertThat(result.truncatedContent()).contains("Truncated");
    }

    @Test
    @DisplayName("TC-RECOVERY-011: 超过最大尝试次数 → 返回失败")
    void recover413_exceedsMaxAttempts_returnsFailed() {
        // Arrange: attempt=4 > OTK_RECOVER_MAX(3)
        List<String> messages = new ArrayList<>(Collections.nCopies(10, "message content placeholder"));
        long currentTokens = 200_000L;
        long maxTokens = 100_000L;

        // Act
        RecoveryResult result = recovery.recover413(messages, currentTokens, maxTokens, 4);

        // Assert
        assertThat(result.success()).isFalse();
        assertThat(result.tokensSaved()).isEqualTo(0);
        assertThat(result.truncatedContent()).isNull();
    }
}
