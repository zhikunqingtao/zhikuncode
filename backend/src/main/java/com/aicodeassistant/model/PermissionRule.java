package com.aicodeassistant.model;

/**
 * 权限规则 — 一条完整的权限声明（含来源追踪）。
 *
 * @see <a href="SPEC §3.4.2">权限规则</a>
 */
public record PermissionRule(
        PermissionRuleSource source,
        PermissionBehavior ruleBehavior,
        PermissionRuleValue ruleValue
) {}
