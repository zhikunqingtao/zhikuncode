package com.aicodeassistant.skill;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Skill 级别 token 预算控制。
 * <p>
 * 约束:
 * - 单个 Skill 在同一会话内累计不得超过 {@link #SINGLE_SKILL_BUDGET} tokens
 * - 同一会话内所有 Skill 累计不得超过 {@link #TOTAL_SESSION_BUDGET} tokens
 */
@Service
public class SkillTokenBudget {
    private static final Logger log = LoggerFactory.getLogger(SkillTokenBudget.class);

    public static final int SINGLE_SKILL_BUDGET = 5000;
    public static final int TOTAL_SESSION_BUDGET = 25000;

    // sessionId -> SessionBudget
    private final ConcurrentHashMap<String, SessionBudget> sessions = new ConcurrentHashMap<>();

    /**
     * 检查是否允许继续消耗 token。
     */
    public boolean canConsume(String sessionId, String skillName, int requestedTokens) {
        SessionBudget budget = sessions.computeIfAbsent(sessionId, k -> new SessionBudget());
        int skillUsed = budget.getSkillUsed(skillName);
        int sessionUsed = budget.getTotalUsed();

        if (skillUsed + requestedTokens > SINGLE_SKILL_BUDGET) {
            log.info("[SKILL-BUDGET] Skill '{}' would exceed single budget: {}/{}",
                    skillName, skillUsed + requestedTokens, SINGLE_SKILL_BUDGET);
            return false;
        }
        if (sessionUsed + requestedTokens > TOTAL_SESSION_BUDGET) {
            log.info("[SKILL-BUDGET] Session '{}' would exceed total budget: {}/{}",
                    sessionId, sessionUsed + requestedTokens, TOTAL_SESSION_BUDGET);
            return false;
        }
        return true;
    }

    /**
     * 记录 token 消耗。
     */
    public void recordConsumption(String sessionId, String skillName, int tokensUsed) {
        SessionBudget budget = sessions.computeIfAbsent(sessionId, k -> new SessionBudget());
        budget.record(skillName, tokensUsed);
        log.debug("[SKILL-BUDGET] Recorded {} tokens for skill '{}' in session '{}'",
                tokensUsed, skillName, sessionId);
    }

    /**
     * 查询预算状态。
     */
    public BudgetStatus getStatus(String sessionId, String skillName) {
        SessionBudget budget = sessions.get(sessionId);
        if (budget == null) {
            return new BudgetStatus(0, SINGLE_SKILL_BUDGET, 0, TOTAL_SESSION_BUDGET, true);
        }
        int skillUsed = budget.getSkillUsed(skillName);
        int sessionUsed = budget.getTotalUsed();
        return new BudgetStatus(
                skillUsed, SINGLE_SKILL_BUDGET - skillUsed,
                sessionUsed, TOTAL_SESSION_BUDGET - sessionUsed,
                skillUsed < SINGLE_SKILL_BUDGET && sessionUsed < TOTAL_SESSION_BUDGET
        );
    }

    /**
     * 清理会话预算数据。
     */
    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
        log.debug("[SKILL-BUDGET] Cleared budget for session '{}'", sessionId);
    }

    // 内部类: 会话级预算追踪
    private static class SessionBudget {
        private final ConcurrentHashMap<String, AtomicInteger> skillUsage = new ConcurrentHashMap<>();
        private final AtomicInteger totalUsed = new AtomicInteger(0);

        void record(String skillName, int tokens) {
            skillUsage.computeIfAbsent(skillName, k -> new AtomicInteger(0)).addAndGet(tokens);
            totalUsed.addAndGet(tokens);
        }

        int getSkillUsed(String skillName) {
            AtomicInteger usage = skillUsage.get(skillName);
            return usage != null ? usage.get() : 0;
        }

        int getTotalUsed() {
            return totalUsed.get();
        }
    }

    // BudgetStatus record
    public record BudgetStatus(
            int skillUsed, int skillRemaining,
            int sessionUsed, int sessionRemaining,
            boolean canContinue
    ) {}
}
