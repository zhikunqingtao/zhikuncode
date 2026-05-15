package com.aicodeassistant.engine.strategy;

import com.aicodeassistant.engine.strategy.TerminationStrategy.LoopContext;
import com.aicodeassistant.engine.strategy.TerminationStrategy.ToolCallRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultTerminationStrategyTest {

    private DefaultTerminationStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new DefaultTerminationStrategy();
    }

    @Test
    @DisplayName("TC-TERM-001: Token > 95% → TERMINATE_BUDGET")
    void shouldTerminateBudgetWhenTokenExceeds95Percent() {
        LoopContext context = new LoopContext(
                1,          // currentTurn
                10,         // maxTurns
                0,          // consecutiveErrors
                0,          // toolCallsThisTurn
                false,      // lastResponseHasToolCalls
                null,       // stopReason
                96000L,     // totalTokensUsed (96% > 95%)
                100000L,    // tokenBudget
                List.of()   // recentResults
        );

        TerminationDecision decision = strategy.evaluate(context);

        assertThat(decision).isEqualTo(TerminationDecision.TERMINATE_BUDGET);
    }

    @Test
    @DisplayName("TC-TERM-002: 连续错误 ≥ 3 → SWITCH_STRATEGY")
    void shouldSwitchStrategyWhenConsecutiveErrorsReachThreshold() {
        LoopContext context = new LoopContext(
                1,          // currentTurn
                10,         // maxTurns
                3,          // consecutiveErrors (>= 3)
                0,          // toolCallsThisTurn
                true,       // lastResponseHasToolCalls
                null,       // stopReason
                10000L,     // totalTokensUsed (< 95%)
                100000L,    // tokenBudget
                List.of()   // recentResults
        );

        TerminationDecision decision = strategy.evaluate(context);

        assertThat(decision).isEqualTo(TerminationDecision.SWITCH_STRATEGY);
    }

    @Test
    @DisplayName("TC-TERM-003: end_turn + 无工具调用 → TERMINATE_SUCCESS")
    void shouldTerminateSuccessOnEndTurnWithoutToolCalls() {
        LoopContext context = new LoopContext(
                5,          // currentTurn
                10,         // maxTurns
                0,          // consecutiveErrors
                0,          // toolCallsThisTurn
                false,      // lastResponseHasToolCalls (无工具调用)
                "end_turn", // stopReason
                10000L,     // totalTokensUsed (< 95%)
                100000L,    // tokenBudget
                List.of()   // recentResults
        );

        TerminationDecision decision = strategy.evaluate(context);

        assertThat(decision).isEqualTo(TerminationDecision.TERMINATE_SUCCESS);
    }

    @Test
    @DisplayName("TC-TERM-004: 轮次 ≥ 动态 maxTurns → TERMINATE_ERROR")
    void shouldTerminateErrorWhenCurrentTurnExceedsDynamicMax() {
        // 动态 maxTurns = 10 + 1*2 = 12, currentTurn=12 >= 12
        LoopContext context = new LoopContext(
                12,         // currentTurn
                10,         // maxTurns
                1,          // consecutiveErrors (动态 max = 10 + 1*2 = 12)
                0,          // toolCallsThisTurn
                true,       // lastResponseHasToolCalls
                null,       // stopReason
                10000L,     // totalTokensUsed (< 95%)
                100000L,    // tokenBudget
                List.of()   // recentResults
        );

        TerminationDecision decision = strategy.evaluate(context);

        assertThat(decision).isEqualTo(TerminationDecision.TERMINATE_ERROR);
    }

    @Test
    @DisplayName("TC-TERM-005: 滑动窗口 5 次全失败 → REQUEST_USER_INPUT")
    void shouldRequestUserInputWhenSlidingWindowAllFailed() {
        List<ToolCallRecord> allFailed = IntStream.range(0, 5)
                .mapToObj(i -> new ToolCallRecord("Bash", false, "error " + i, Instant.now()))
                .toList();

        LoopContext context = new LoopContext(
                3,          // currentTurn
                10,         // maxTurns
                0,          // consecutiveErrors (< 3, 不触发 SWITCH_STRATEGY)
                0,          // toolCallsThisTurn
                true,       // lastResponseHasToolCalls
                null,       // stopReason
                10000L,     // totalTokensUsed (< 95%)
                100000L,    // tokenBudget
                allFailed   // recentResults: 5 条全部失败
        );

        TerminationDecision decision = strategy.evaluate(context);

        assertThat(decision).isEqualTo(TerminationDecision.REQUEST_USER_INPUT);
    }

    @Test
    @DisplayName("TC-TERM-006: 正常情况 → CONTINUE")
    void shouldContinueWhenAllMetricsNormal() {
        List<ToolCallRecord> mixedResults = List.of(
                new ToolCallRecord("FileRead", true, null, Instant.now()),
                new ToolCallRecord("Bash", false, "timeout", Instant.now()),
                new ToolCallRecord("FileRead", true, null, Instant.now())
        );

        LoopContext context = new LoopContext(
                3,          // currentTurn (< maxTurns)
                10,         // maxTurns
                0,          // consecutiveErrors (< 3)
                1,          // toolCallsThisTurn
                true,       // lastResponseHasToolCalls
                null,       // stopReason
                50000L,     // totalTokensUsed (50% < 95%)
                100000L,    // tokenBudget
                mixedResults // recentResults: 不全是失败
        );

        TerminationDecision decision = strategy.evaluate(context);

        assertThat(decision).isEqualTo(TerminationDecision.CONTINUE);
    }
}
