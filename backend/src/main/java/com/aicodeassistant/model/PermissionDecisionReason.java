package com.aicodeassistant.model;

/**
 * 权限决策来源类型 — 记录为什么做出了此权限决策。
 *
 */
public enum PermissionDecisionReason {
    RULE,
    MODE,
    SUBCOMMAND_RESULTS,
    PERMISSION_PROMPT_TOOL,
    HOOK,
    ASYNC_AGENT,
    SANDBOX_OVERRIDE,
    CLASSIFIER,
    WORKING_DIR,
    SAFETY_CHECK,
    USER_DENIED,
    INTERACTION_EXPIRED,
    INTERACTION_UNDELIVERABLE,
    INTERACTION_CANCELLED,
    OTHER
}
