package com.aicodeassistant.permission;

/**
 * 权限通知接口 — 解耦 PermissionPipeline 与 WebSocketController。
 * <p>
 * P1-06: PermissionPipeline 不再直接依赖 WebSocketController 类型，
 * 而是通过此接口与前端通信。WebSocketController 实现此接口。
 */
public interface PermissionNotifier {

    /**
     * 推送权限请求到前端。
     *
     * @param sessionId  会话 ID
     * @param toolUseId  工具调用 ID
     * @param toolName   工具名称
     * @param input      工具输入
     * @param riskLevel  风险级别 ("high"/"normal")
     * @param reason     请求原因
     */
    void sendPermissionRequest(String sessionId, String toolUseId,
                                String toolName, Object input,
                                String riskLevel, String reason);
}
