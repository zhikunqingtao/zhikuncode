package com.aicodeassistant.bridge;

import java.util.Map;

/**
 * 桥接消息 — IDE↔Server 双向通信消息。
 * <p>
 * IDE→Server 消息类型: bridge_init, bridge_auth, bridge_subscribe,
 * bridge_command, bridge_file_open, bridge_diff_apply, bridge_ping
 * <p>
 * Server→IDE 消息类型: bridge_ready, bridge_session_update, bridge_tool_result,
 * bridge_file_changed, bridge_permission_request, bridge_error, bridge_pong
 *
 * @param type    消息类型
 * @param payload 消息负载
 * @param id      消息唯一 ID（用于去重）
 * @param epoch   消息 epoch（用于版本控制）
 * @see <a href="SPEC §4.5.1">桥接消息类型</a>
 */
public record BridgeMessage(
        String type,
        Map<String, Object> payload,
        String id,
        long epoch
) {

    /** 简化构造 — 无 epoch */
    public static BridgeMessage of(String type, Map<String, Object> payload) {
        return new BridgeMessage(type, payload, java.util.UUID.randomUUID().toString(), 0);
    }

    /** 带 epoch */
    public static BridgeMessage of(String type, Map<String, Object> payload, long epoch) {
        return new BridgeMessage(type, payload, java.util.UUID.randomUUID().toString(), epoch);
    }

    // ==================== IDE → Server 消息类型 ====================

    /** 握手初始化 */
    public static BridgeMessage init(String extensionId, String extensionVersion, String ideType) {
        return of("bridge_init", Map.of(
                "extensionId", extensionId,
                "extensionVersion", extensionVersion,
                "ideType", ideType));
    }

    /** JWT 认证 */
    public static BridgeMessage auth(String token, String issuer) {
        return of("bridge_auth", Map.of("token", token, "issuer", issuer));
    }

    /** 订阅事件 */
    public static BridgeMessage subscribe(java.util.List<String> topics) {
        return of("bridge_subscribe", Map.of("topics", topics));
    }

    /** 执行命令 */
    public static BridgeMessage command(String command, Map<String, Object> args) {
        return of("bridge_command", Map.of("command", command, "args", args));
    }

    /** 请求打开文件 */
    public static BridgeMessage fileOpen(String filePath, Integer line, Integer column) {
        var payload = new java.util.HashMap<String, Object>();
        payload.put("filePath", filePath);
        if (line != null) payload.put("line", line);
        if (column != null) payload.put("column", column);
        return of("bridge_file_open", payload);
    }

    /** 心跳探测 */
    public static BridgeMessage ping() {
        return of("bridge_ping", Map.of());
    }

    // ==================== Server → IDE 消息类型 ====================

    /** 连接就绪 */
    public static BridgeMessage ready(String sessionId, String serverVersion,
                                       java.util.List<String> capabilities) {
        return of("bridge_ready", Map.of(
                "sessionId", sessionId,
                "serverVersion", serverVersion,
                "capabilities", capabilities));
    }

    /** 会话状态变更 */
    public static BridgeMessage sessionUpdate(String status, String model, long tokenUsage) {
        return of("bridge_session_update", Map.of(
                "status", status, "model", model, "tokenUsage", tokenUsage));
    }

    /** 工具执行结果 */
    public static BridgeMessage toolResult(String toolName, String toolUseId,
                                            String content, boolean isError) {
        return of("bridge_tool_result", Map.of(
                "toolName", toolName, "toolUseId", toolUseId,
                "content", content, "isError", isError));
    }

    /** 错误通知 */
    public static BridgeMessage error(String code, String message) {
        return of("bridge_error", Map.of("code", code, "message", message));
    }

    /** 心跳回复 */
    public static BridgeMessage pong() {
        return of("bridge_pong", Map.of());
    }
}
