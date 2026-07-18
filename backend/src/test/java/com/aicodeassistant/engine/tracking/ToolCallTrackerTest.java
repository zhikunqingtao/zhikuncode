package com.aicodeassistant.engine.tracking;

import com.aicodeassistant.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallTrackerTest {
    @Test
    void recordsToolCallFields() {
        ToolCallTracker tracker = new ToolCallTracker();
        tracker.record("Bash", true, null);

        List<com.aicodeassistant.engine.strategy.TerminationStrategy.ToolCallRecord> records =
                tracker.getRecentRecords(1);
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().toolName()).isEqualTo("Bash");
        assertThat(records.getFirst().success()).isTrue();
        assertThat(records.getFirst().errorMessage()).isNull();
        assertThat(records.getFirst().timestamp()).isNotNull();
    }

    @Test
    void successfulCallResetsConsecutiveErrors() {
        ToolCallTracker tracker = new ToolCallTracker();
        tracker.record("Bash", false, "error1");
        tracker.record("Bash", false, "error2");
        tracker.record("Bash", false, "error3");
        assertThat(tracker.getConsecutiveErrors()).isEqualTo(3);

        tracker.record("Read", true, null);
        assertThat(tracker.getConsecutiveErrors()).isZero();
    }

    @Test
    void returnsMostRecentRecordsInOrder() {
        ToolCallTracker tracker = new ToolCallTracker();
        for (int i = 0; i < 10; i++) {
            tracker.record("Tool_" + i, i % 2 == 0, i % 2 == 0 ? null : "error");
        }

        assertThat(tracker.getRecentRecords(5)).extracting(
                com.aicodeassistant.engine.strategy.TerminationStrategy.ToolCallRecord::toolName)
                .containsExactly("Tool_5", "Tool_6", "Tool_7", "Tool_8", "Tool_9");
    }

    @Test
    void expectedPermissionOutcomesDoNotTriggerSystemRecovery() {
        ToolCallTracker tracker = new ToolCallTracker();
        tracker.record("Bash", ToolResult.internalError(
                "PROCESS_START_FAILED", "failed", ToolResult.EffectState.NOT_STARTED));
        assertThat(tracker.getConsecutiveErrors()).isEqualTo(1);

        tracker.record("Bash", ToolResult.permissionDenied(
                "PERMISSION_USER_DENIED", "The user denied this operation"));

        assertThat(tracker.getConsecutiveErrors()).isZero();
        assertThat(tracker.getRecentRecords(2).getLast().recoveryRelevant()).isFalse();
        assertThat(tracker.isWindowAllFailed(2)).isFalse();
    }

    @Test
    void authorizationStoreFailuresRemainRecoveryRelevant() {
        ToolCallTracker tracker = new ToolCallTracker();
        tracker.record("Read", ToolResult.failed(
                ToolResult.ToolFailureType.INTERNAL, "AUTHORIZATION_STORE_BUSY", "busy",
                ToolResult.Retryability.SAFE_READ_ONLY, ToolResult.EffectState.NOT_STARTED,
                null, java.util.Map.of()));

        assertThat(tracker.getConsecutiveErrors()).isEqualTo(1);
        assertThat(tracker.getRecentRecords(1).getFirst().recoveryRelevant()).isTrue();
    }
}
