package com.aicodeassistant.mcp;

/**
 * MCP 传输类型 — 8 种，P1 实现 4 种标准传输。
 *
 * @see <a href="SPEC §4.3.2">MCP 配置模型</a>
 */
public enum McpTransportType {
    /** 标准进程 I/O — P1 */
    STDIO,
    /** Server-Sent Events — P1 */
    SSE,
    /** IDE 内置 SSE — P2 */
    SSE_IDE,
    /** Streamable HTTP — P1 */
    HTTP,
    /** WebSocket — P1 */
    WS,
    /** IDE 内置 WebSocket — P2 */
    WS_IDE,
    /** SDK 内嵌 — P2 */
    SDK,
    /** Claude AI 平台代理 — P2 */
    CLAUDEAI_PROXY
}
