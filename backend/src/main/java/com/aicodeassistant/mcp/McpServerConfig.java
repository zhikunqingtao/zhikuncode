package com.aicodeassistant.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置 — 定义一个 MCP 服务器的连接参数。
 *
 * @param name    服务器名称（唯一标识）
 * @param type    传输类型 (STDIO/SSE/HTTP/WS 等)
 * @param command stdio: 启动命令
 * @param args    stdio: 命令参数
 * @param env     环境变量
 * @param url     SSE/HTTP/WS: 服务 URL
 * @param headers HTTP 头
 * @param scope   配置作用域
 * @see <a href="SPEC §4.3.2">MCP 配置模型</a>
 */
public record McpServerConfig(
        String name,
        McpTransportType type,
        String command,
        List<String> args,
        Map<String, String> env,
        String url,
        Map<String, String> headers,
        McpConfigScope scope
) {
    /** stdio 类型构建 */
    public static McpServerConfig stdio(String name, String command, List<String> args) {
        return new McpServerConfig(name, McpTransportType.STDIO, command, args,
                Map.of(), null, Map.of(), McpConfigScope.LOCAL);
    }

    /** SSE 类型构建 */
    public static McpServerConfig sse(String name, String url) {
        return new McpServerConfig(name, McpTransportType.SSE, null, List.of(),
                Map.of(), url, Map.of(), McpConfigScope.USER);
    }

    /** HTTP 类型构建 */
    public static McpServerConfig http(String name, String url) {
        return new McpServerConfig(name, McpTransportType.HTTP, null, List.of(),
                Map.of(), url, Map.of(), McpConfigScope.USER);
    }
}
