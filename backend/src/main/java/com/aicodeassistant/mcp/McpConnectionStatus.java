package com.aicodeassistant.mcp;

/**
 * MCP 服务器连接状态 — 6 种状态。
 *
 * @see <a href="SPEC §4.3.3">MCP 客户端管理</a>
 */
public enum McpConnectionStatus {
    /** 成功连接，工具/资源可用 */
    CONNECTED,
    /** 连接失败（启动错误、网络不可达） */
    FAILED,
    /** 需要 OAuth 认证 */
    NEEDS_AUTH,
    /** 正在连接中（支持重连尝试） */
    PENDING,
    /** 被用户/配置禁用 */
    DISABLED,
    /** 传输连接正常但协议握手失败，功能受限 */
    DEGRADED
}
