package com.aicodeassistant.model;

/**
 * MCP 连接状态枚举。
 *
 * @see <a href="SPEC §5.6">MCP 模型</a>
 */
public enum McpConnectionStatus {
    CONNECTED,
    FAILED,
    NEEDS_AUTH,
    PENDING,
    DISABLED
}
