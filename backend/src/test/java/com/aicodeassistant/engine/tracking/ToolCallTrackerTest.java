package com.aicodeassistant.engine.tracking;

import com.aicodeassistant.engine.strategy.TerminationStrategy.ToolCallRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallTrackerTest {

    private ToolCallTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ToolCallTracker();
    }

    @Test
    @DisplayName("TC-TERM-007: record() 正确记录工具调用")
    void shouldRecordToolCallCorrectly() {
        tracker.record("Bash", true, null);

        List<ToolCallRecord> records = tracker.getRecentRecords(1);

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().toolName()).isEqualTo("Bash");
        assertThat(records.getFirst().success()).isTrue();
        assertThat(records.getFirst().errorMessage()).isNull();
        assertThat(records.getFirst().timestamp()).isNotNull();
    }

    @Test
    @DisplayName("TC-TERM-008: 连续错误计数在成功调用后重置")
    void shouldResetConsecutiveErrorsAfterSuccess() {
        // 3 次失败
        tracker.record("Bash", false, "error1");
        tracker.record("Bash", false, "error2");
        tracker.record("Bash", false, "error3");
        assertThat(tracker.getConsecutiveErrors()).isEqualTo(3);

        // 1 次成功 → 重置
        tracker.record("FileRead", true, null);

        assertThat(tracker.getConsecutiveErrors()).isEqualTo(0);
    }

    @Test
    @DisplayName("TC-TERM-009: getConsecutiveErrors() 正确返回连续失败数")
    void shouldReturnCorrectConsecutiveErrorCount() {
        tracker.record("Bash", false, "timeout");
        tracker.record("FileWrite", false, "permission denied");

        assertThat(tracker.getConsecutiveErrors()).isEqualTo(2);
    }

    @Test
    @DisplayName("TC-TERM-010: 滑动窗口正确维护最近 N 条记录")
    void shouldReturnCorrectRecentRecords() {
        // 记录 10 条
        for (int i = 0; i < 10; i++) {
            tracker.record("Tool_" + i, i % 2 == 0, i % 2 != 0 ? "error" : null);
        }

        List<ToolCallRecord> recent5 = tracker.getRecentRecords(5);

        assertThat(recent5).hasSize(5);
        // 最近 5 条应该是 Tool_5 ~ Tool_9
        assertThat(recent5.get(0).toolName()).isEqualTo("Tool_5");
        assertThat(recent5.get(1).toolName()).isEqualTo("Tool_6");
        assertThat(recent5.get(2).toolName()).isEqualTo("Tool_7");
        assertThat(recent5.get(3).toolName()).isEqualTo("Tool_8");
        assertThat(recent5.get(4).toolName()).isEqualTo("Tool_9");
    }
}
