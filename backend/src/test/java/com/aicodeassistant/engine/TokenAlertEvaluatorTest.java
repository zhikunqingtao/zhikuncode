package com.aicodeassistant.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenAlertEvaluatorTest {

    private TokenAlertEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new TokenAlertEvaluator();
    }

    @Test
    @DisplayName("NORMAL: 使用率低于70%")
    void evaluate_belowWarning_returnsNormal() {
        // contextWindow=100000, maxOutput=4096 -> effectiveWindow=100000-4096=95904
        // 69% of 95904 = 66173
        assertEquals(TokenAlertEvaluator.Level.NORMAL,
                evaluator.evaluate(66000, 100_000, 4096));
    }

    @Test
    @DisplayName("WARNING: 使用率恰好70%")
    void evaluate_atWarningThreshold_returnsWarning() {
        // effectiveWindow = 100000 - 4096 = 95904
        // 70% of 95904 = 67132
        assertEquals(TokenAlertEvaluator.Level.WARNING,
                evaluator.evaluate(67200, 100_000, 4096));
    }

    @Test
    @DisplayName("WARNING: 使用率89%（临界CRITICAL之下）")
    void evaluate_belowCritical_returnsWarning() {
        // 89% of 95904 = 85354
        assertEquals(TokenAlertEvaluator.Level.WARNING,
                evaluator.evaluate(85000, 100_000, 4096));
    }

    @Test
    @DisplayName("CRITICAL: 使用率恰好90%")
    void evaluate_atCriticalThreshold_returnsCritical() {
        // 90% of 95904 = 86313
        assertEquals(TokenAlertEvaluator.Level.CRITICAL,
                evaluator.evaluate(86400, 100_000, 4096));
    }

    @Test
    @DisplayName("TRIGGER_COMPACT: 使用率100%以上")
    void evaluate_atOrAbove100Percent_returnsTriggerCompact() {
        // 100% of 95904 = 95904
        assertEquals(TokenAlertEvaluator.Level.TRIGGER_COMPACT,
                evaluator.evaluate(96000, 100_000, 4096));
    }

    @Test
    @DisplayName("effectiveWindow防御: maxOutputTokens > contextWindow时不崩溃")
    void evaluate_negativeEffectiveWindow_usesDefensiveValue() {
        // contextWindow=5000, maxOutput=20000 -> effectiveWindow would be -15000
        // 防御: max(5000/2, 10000) = 10000
        var result = evaluator.evaluate(5000, 5000, 20_000);
        assertNotNull(result);
        // 5000/10000 = 50% -> NORMAL
        assertEquals(TokenAlertEvaluator.Level.NORMAL, result);
    }

    @Test
    @DisplayName("effectiveWindow防御: contextWindow为极小值")
    void evaluate_tinyContextWindow_usesMinimum() {
        // contextWindow=1000, maxOutput=20000 -> 防御: max(500, 10000) = 10000
        var result = evaluator.evaluate(500, 1000, 20_000);
        assertEquals(TokenAlertEvaluator.Level.NORMAL, result);
    }

    @Test
    @DisplayName("getEffectiveWindow: 正常计算")
    void getEffectiveWindow_normalCase() {
        // contextWindow=200000, maxOutput=50000 -> reserved=min(50000,20000)=20000
        // effectiveWindow = 200000 - 20000 = 180000
        assertEquals(180_000, evaluator.getEffectiveWindow(200_000, 50_000));
    }

    @Test
    @DisplayName("getEffectiveWindow: maxOutput小于20000时全额扣除")
    void getEffectiveWindow_smallMaxOutput() {
        // contextWindow=100000, maxOutput=8000 -> reserved=min(8000,20000)=8000
        // effectiveWindow = 100000 - 8000 = 92000
        assertEquals(92_000, evaluator.getEffectiveWindow(100_000, 8000));
    }
}
