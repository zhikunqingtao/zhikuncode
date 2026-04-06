package com.aicodeassistant.model;

/**
 * 权限决策来源类型 — 记录为什么做出了此权限决策。
 *
 * @see <a href="SPEC §5.4">权限模型</a>
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
    OTHER
}
