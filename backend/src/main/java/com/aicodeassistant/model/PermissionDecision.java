package com.aicodeassistant.model;

/**
 * 权限决策 — 权限管线的最终输出。
 *
 * @param behavior    最终行为: ALLOW / DENY / ASK
 * @param reasonType  决策原因类型
 * @param reason      人类可读原因
 * @param matchedRule 匹配的规则（可选）
 * @param mode        触发决策的权限模式（可选）
 * @param remember    是否记住决策
 * @param rememberScope 记忆作用域
 * @param bubble      是否冒泡给父代理
 * @see <a href="SPEC §3.4.3a">权限决策管线</a>
 */
public record PermissionDecision(
        PermissionBehavior behavior,
        PermissionDecisionReason reasonType,
        String reason,
        PermissionRule matchedRule,
        PermissionMode mode,
        boolean remember,
        RuleScope rememberScope,
        boolean bubble
) {
    /** 向后兼容: allowed 字段 */
    public boolean allowed() {
        return behavior == PermissionBehavior.ALLOW;
    }

    // ==================== 工厂方法 ====================

    /** 基于规则允许 */
    public static PermissionDecision allow(PermissionRule rule) {
        return new PermissionDecision(PermissionBehavior.ALLOW,
                PermissionDecisionReason.RULE, null, rule, null, false, null, false);
    }

    /** 基于模式允许 */
    public static PermissionDecision allow(PermissionDecisionReason reasonType, PermissionMode mode) {
        return new PermissionDecision(PermissionBehavior.ALLOW,
                reasonType, null, null, mode, false, null, false);
    }

    /** 基于分类器允许 */
    public static PermissionDecision allowByClassifier(String reason) {
        return new PermissionDecision(PermissionBehavior.ALLOW,
                PermissionDecisionReason.CLASSIFIER, reason, null, null, false, null, false);
    }

    /** 基于规则拒绝 */
    public static PermissionDecision deny(PermissionRule rule, String reason) {
        return new PermissionDecision(PermissionBehavior.DENY,
                PermissionDecisionReason.RULE, reason, rule, null, false, null, false);
    }

    /** 基于模式拒绝 */
    public static PermissionDecision denyByMode(String reason) {
        return new PermissionDecision(PermissionBehavior.DENY,
                PermissionDecisionReason.MODE, reason, null, null, false, null, false);
    }

    /** 基于规则询问 */
    public static PermissionDecision ask(PermissionRule rule) {
        return new PermissionDecision(PermissionBehavior.ASK,
                PermissionDecisionReason.RULE, null, rule, null, false, null, false);
    }

    /** 基于原因询问 */
    public static PermissionDecision ask(PermissionDecisionReason reasonType, String reason) {
        return new PermissionDecision(PermissionBehavior.ASK,
                reasonType, reason, null, null, false, null, false);
    }

    // ==================== 转换方法 ====================

    /** 设置冒泡标记 */
    public PermissionDecision withBubble(boolean bubble) {
        return new PermissionDecision(behavior, reasonType, reason, matchedRule, mode, remember, rememberScope, bubble);
    }

    /** 设置记忆 */
    public PermissionDecision withRemember(boolean remember, RuleScope scope) {
        return new PermissionDecision(behavior, reasonType, reason, matchedRule, mode, remember, scope, bubble);
    }

    /** 是否为允许 */
    public boolean isAllowed() {
        return behavior == PermissionBehavior.ALLOW;
    }

    /** 是否为拒绝 */
    public boolean isDenied() {
        return behavior == PermissionBehavior.DENY;
    }
}
