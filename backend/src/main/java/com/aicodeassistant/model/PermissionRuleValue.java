package com.aicodeassistant.model;

/**
 * 权限规则值 — 工具名 + 可选匹配条件。
 *
 * @see <a href="SPEC §3.4.2">权限规则</a>
 */
public record PermissionRuleValue(
        String toolName,
        String ruleContent
) {}
