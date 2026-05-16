package com.aicodeassistant.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SkillTokenBudgetTest {

    private SkillTokenBudget budget;

    @BeforeEach
    void setUp() {
        budget = new SkillTokenBudget();
    }

    // ====================== canConsume() tests ======================

    @Test
    void testCanConsume_UnderLimit() {
        assertThat(budget.canConsume("s1", "debug", 3000)).isTrue();
    }

    @Test
    void testCanConsume_ExceedsSingleSkillLimit() {
        budget.recordConsumption("s1", "debug", 4000);
        // 4000 + 2000 = 6000 > SINGLE_SKILL_BUDGET(5000)
        assertThat(budget.canConsume("s1", "debug", 2000)).isFalse();
    }

    @Test
    void testCanConsume_ExactlySingleSkillLimit_Passes() {
        budget.recordConsumption("s1", "debug", 3000);
        // 3000 + 2000 = 5000 == SINGLE_SKILL_BUDGET → allowed
        assertThat(budget.canConsume("s1", "debug", 2000)).isTrue();
    }

    @Test
    void testCanConsume_ExceedsSessionLimit() {
        budget.recordConsumption("s1", "skill1", 5000);
        budget.recordConsumption("s1", "skill2", 5000);
        budget.recordConsumption("s1", "skill3", 5000);
        budget.recordConsumption("s1", "skill4", 5000);
        budget.recordConsumption("s1", "skill5", 5000);
        // total = 25000 == TOTAL_SESSION_BUDGET, any additional > budget
        assertThat(budget.canConsume("s1", "skill6", 1)).isFalse();
    }

    @Test
    void testMultipleSkills_IndependentBudgets() {
        budget.recordConsumption("s1", "debug", 4000);
        // debug is near single limit but verify has its own budget
        assertThat(budget.canConsume("s1", "verify", 4000)).isTrue();
    }

    @Test
    void testDifferentSessions_Independent() {
        budget.recordConsumption("s1", "debug", 5000);
        // session s2 is untouched
        assertThat(budget.canConsume("s2", "debug", 5000)).isTrue();
    }

    // ====================== clearSession() tests ======================

    @Test
    void testClearSession_ResetsAll() {
        budget.recordConsumption("s1", "debug", 3000);
        budget.clearSession("s1");
        assertThat(budget.canConsume("s1", "debug", 5000)).isTrue();
    }

    @Test
    void testClearSession_DoesNotAffectOtherSessions() {
        budget.recordConsumption("s1", "debug", 3000);
        budget.recordConsumption("s2", "debug", 4000);
        budget.clearSession("s1");
        // s2 unaffected
        SkillTokenBudget.BudgetStatus s2Status = budget.getStatus("s2", "debug");
        assertThat(s2Status.skillUsed()).isEqualTo(4000);
    }

    // ====================== getStatus() tests ======================

    @Test
    void testGetStatus_ReturnsCorrectValues() {
        budget.recordConsumption("s1", "debug", 2000);
        SkillTokenBudget.BudgetStatus status = budget.getStatus("s1", "debug");
        assertThat(status.skillUsed()).isEqualTo(2000);
        assertThat(status.skillRemaining()).isEqualTo(3000);
        assertThat(status.sessionUsed()).isEqualTo(2000);
        assertThat(status.sessionRemaining()).isEqualTo(23000);
        assertThat(status.canContinue()).isTrue();
    }

    @Test
    void testGetStatus_UnknownSession_ReturnsDefaults() {
        SkillTokenBudget.BudgetStatus status = budget.getStatus("unknown", "any");
        assertThat(status.skillUsed()).isZero();
        assertThat(status.skillRemaining()).isEqualTo(SkillTokenBudget.SINGLE_SKILL_BUDGET);
        assertThat(status.canContinue()).isTrue();
    }

    @Test
    void testGetStatus_ExhaustedBudget_CanContinueFalse() {
        budget.recordConsumption("s1", "debug", 5000);
        SkillTokenBudget.BudgetStatus status = budget.getStatus("s1", "debug");
        assertThat(status.canContinue()).isFalse();
    }

    // ====================== concurrency test ======================

    @Test
    void testConcurrentAccess_ThreadSafe() throws Exception {
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> budget.recordConsumption("s1", "skill" + idx, 100));
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        // 10 skills × 100 tokens each = 1000 total
        SkillTokenBudget.BudgetStatus status = budget.getStatus("s1", "skill0");
        assertThat(status.sessionUsed()).isEqualTo(1000);
    }
}
