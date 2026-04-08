package com.aicodeassistant.mcp;

/**
 * MCP 配置作用域 — 7 级优先级。
 *
 * @see <a href="SPEC §4.3.2">MCP 配置模型</a>
 */
public enum McpConfigScope {
    /** 项目本地 (.ai-code-assistant/mcp.json) — 优先级 1 (最高) */
    LOCAL,
    /** 用户级 (~/.config/ai-code-assistant/mcp.json) — 优先级 2 */
    USER,
    /** 项目配置 — 优先级 3 */
    PROJECT,
    /** 运行时动态注册 — 优先级 7 (最低) */
    DYNAMIC,
    /** 企业配置 (MDM 推送) — 优先级 4 */
    ENTERPRISE,
    /** Claude AI 平台托管 — 优先级 5 */
    CLAUDEAI,
    /** 受管理配置 (Marketplace) — 优先级 6 */
    MANAGED
}
