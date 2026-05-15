package com.aicodeassistant.engine.scheduling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ToolPrioritySchedulerTest {

    private ToolPriorityScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ToolPriorityScheduler();
    }

    @Test
    @DisplayName("TC-TERM-011: 4 层优先级正确排序")
    void shouldSortByFourPriorityLevels() {
        // 输入顺序: FileEdit(3), Bash(2), FileRead(0), LspDefinition(1)
        List<String> toolCalls = List.of("FileEdit", "Bash", "FileRead", "LspDefinition");

        List<String> sorted = scheduler.sortByPriority(toolCalls, Function.identity());

        // 期望排序: FileRead(0) → LspDefinition(1) → Bash(2) → FileEdit(3)
        assertThat(sorted).containsExactly("FileRead", "LspDefinition", "Bash", "FileEdit");
    }

    @Test
    @DisplayName("TC-TERM-012: 同优先级按提交顺序执行（稳定排序）")
    void shouldMaintainOriginalOrderForSamePriority() {
        // 三个 Priority 0 的工具
        List<String> toolCalls = List.of("GrepSearch", "FileRead", "ListDir");

        List<String> sorted = scheduler.sortByPriority(toolCalls, Function.identity());

        // 同优先级，保持原序
        assertThat(sorted).containsExactly("GrepSearch", "FileRead", "ListDir");
    }
}
