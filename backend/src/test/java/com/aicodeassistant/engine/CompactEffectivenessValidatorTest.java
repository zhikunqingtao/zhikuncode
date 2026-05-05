package com.aicodeassistant.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompactEffectivenessValidatorTest {

    private CompactEffectivenessValidator validator;

    @BeforeEach
    void setUp() {
        validator = new CompactEffectivenessValidator();
    }

    @Test
    @DisplayName("isEffective: 压缩后低于70%返回true")
    void isEffective_belowSafeRatio_returnsTrue() {
        // effectiveWindow = 100000 - 4096 = 95904
        // 70% of 95904 = 67132
        assertTrue(validator.isEffective(60000, 100_000, 4096));
    }

    @Test
    @DisplayName("isEffective: 压缩后高于70%返回false")
    void isEffective_aboveSafeRatio_returnsFalse() {
        // 75% of 95904 = 71928
        assertFalse(validator.isEffective(72000, 100_000, 4096));
    }

    @Test
    @DisplayName("isEffective: 恰好70%边界返回true")
    void isEffective_exactlyAtSafeRatio_returnsTrue() {
        // effectiveWindow = 100000 - 4096 = 95904
        // 70% = 67132.8
        assertTrue(validator.isEffective(67132, 100_000, 4096));
    }

    @Test
    @DisplayName("tokensToRelease: 需要释放的token数正确")
    void tokensToRelease_calculatesCorrectly() {
        // effectiveWindow = 100000 - 4096 = 95904
        // safeTarget = 95904 * 70 / 100 = 67132
        // tokensToRelease = 80000 - 67132 = 12868
        int release = validator.tokensToRelease(80_000, 100_000, 4096);
        assertTrue(release > 0);
        assertEquals(80_000 - (95904 * 70 / 100), release);
    }

    @Test
    @DisplayName("tokensToRelease: 已在安全水位返回0")
    void tokensToRelease_alreadySafe_returnsZero() {
        assertEquals(0, validator.tokensToRelease(50_000, 100_000, 4096));
    }

    @Test
    @DisplayName("effectiveWindow防御: 负值时使用防御值")
    void isEffective_negativeEffectiveWindow_usesDefensive() {
        // contextWindow=5000, maxOutput=20000 -> 防御: max(2500, 10000) = 10000
        // 5000/10000 = 50% < 70% -> effective
        assertTrue(validator.isEffective(5000, 5000, 20_000));
    }

    @Test
    @DisplayName("tokensToRelease: effectiveWindow防御值正确计算")
    void tokensToRelease_defensiveWindow_calculatesCorrectly() {
        // contextWindow=1000, maxOutput=20000 -> 防御: max(500, 10000) = 10000
        // safeTarget = 10000 * 70 / 100 = 7000
        // release = 8000 - 7000 = 1000
        assertEquals(1000, validator.tokensToRelease(8000, 1000, 20_000));
    }
}
