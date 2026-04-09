package com.aicodeassistant.websocket;

import com.aicodeassistant.model.Usage;

import java.util.List;
import java.util.Map;

/**
 * WebSocket Server → Client 消息类型 (§8.5.1, 25 种)。
 * <p>
 * 所有消息通过 {@link WebSocketController#pushToUser} 推送，
 * 框架自动追加 {@code type} 和 {@code ts} 字段。
 * <p>
 * 消息格式: 扁平 JSON，字段名与前端 handler 完全一致。
 */
public final class ServerMessage {

    private ServerMessage() {}

    // ==================== #1-5: messageStore ====================

    /** #1 stream_delta — 文本流增量 */
    public record StreamDelta(String delta) {}

    /** #2 thinking_delta — 思考流增量 */
    public record ThinkingDelta(String delta) {}

    /** #3 tool_use_start — 工具调用开始 */
    public record ToolUseStart(String toolUseId, String toolName, Object input) {}

    /** #4 tool_use_progress — 工具执行进度 (stdout) */
    public record ToolUseProgress(String toolUseId, String progress) {}

    /** #5 tool_result — 工具结果返回 */
    public record ToolResult(String toolUseId, ToolResultContent result) {
        public record ToolResultContent(String content, boolean isError, Map<String, Object> metadata) {}
    }

    // ==================== #6: permissionStore + sessionStore ====================

    /** #6 permission_request — 权限确认请求 */
    public record PermissionRequest(
            String toolUseId, String toolName, Object input,
            String riskLevel, String reason
    ) {}

    // ==================== #7: messageStore + sessionStore ====================

    /** #7 message_complete — 助手回合完成 */
    public record MessageComplete(Usage usage, String stopReason) {}

    // ==================== #8: messageStore + sessionStore ====================

    /** #8 error — API 错误 / 过载 */
    public record Error(String code, String message, boolean retryable) {}

    // ==================== #9-10: sessionStore ====================

    /** #9 compact_start — 上下文压缩开始 */
    public record CompactStart() {}

    /** #10 compact_complete — 压缩完成 */
    public record CompactComplete(String summary, int tokensSaved) {}

    // ==================== #11: appUiStore ====================

    /** #11 elicitation — AI 反向提问 */
    public record Elicitation(String requestId, String question, List<ElicitationOption> options) {
        public record ElicitationOption(String value, String label) {}
    }

    // ==================== #12-14: taskStore ====================

    /** #12 agent_spawn — 子代理启动 */
    public record AgentSpawn(String taskId, String agentName, String agentType) {}

    /** #13 agent_update — 子代理进度 */
    public record AgentUpdate(String taskId, String progress) {}

    /** #14 agent_complete — 子代理完成 */
    public record AgentComplete(String taskId, String result) {}

    // ==================== #15: costStore ====================

    /** #15 cost_update — 费用/Token 更新 */
    public record CostUpdate(double sessionCost, double totalCost, Usage usage) {}

    // ==================== #16: sessionStore ====================

    /** #16 rate_limit — 限流通知 */
    public record RateLimit(long retryAfterMs, String limitType) {}

    // ==================== #17: notificationStore ====================

    /** #17 notification — 系统通知推送 */
    public record Notification(String key, String level, String message, int timeout) {}

    // ==================== #18: taskStore ====================

    /** #18 task_update — 后台任务状态 */
    public record TaskUpdate(String taskId, String status, String progress) {}

    // ==================== #19: appUiStore ====================

    /** #19 prompt_suggestion — 提示建议推送 */
    public record PromptSuggestion(List<String> suggestions) {}

    // ==================== #20: bridgeStore ====================

    /** #20 bridge_status — 桥接连接状态 */
    public record BridgeStatus(String status, String url) {}

    // ==================== #21: inboxStore ====================

    /** #21 teammate_message — Swarm 队友消息 */
    public record TeammateMessage(String fromId, String content) {}

    // ==================== #22: appUiStore ====================

    /** #22 speculation_result — 推测执行结果 */
    public record SpeculationResult(String id, boolean accepted) {}

    // ==================== #23: mcpStore ====================

    /** #23 mcp_tool_update — MCP 工具列表变更 */
    public record McpToolUpdate(String serverId, List<McpToolInfo> tools) {
        public record McpToolInfo(String name, String description, Map<String, Object> inputSchema) {}
    }

    // ==================== #24: 断线重连 ====================

    /** #24 session_restored — 断线重连恢复 */
    public record SessionRestored(List<Object> messages, SessionMetadata metadata) {
        public record SessionMetadata(
                String sessionId, String model,
                String permissionMode, String status
        ) {}
    }

    // ==================== #26-32: 新增消息类型 ====================

    /** #26 compact_event — 压缩进度事件 */
    public record CompactEvent(String phase, int usagePercent, int currentTokens) {}

    /** #27 token_warning — Token 用量警告 */
    public record TokenWarning(int currentTokens, int maxTokens, int usagePercent, String warningLevel) {}

    /** #28 interrupt_ack — 中断确认 */
    public record InterruptAck(String reason) {}

    /** #29 model_changed — 模型切换确认 */
    public record ModelChanged(String model) {}

    /** #30 permission_mode_changed — 权限模式切换确认 */
    public record PermissionModeChanged(String mode) {}

    /** #31 command_result — 命令执行结果 */
    public record CommandResult(String command, String output) {}

    /** #32 rewind_complete — 文件回退完成 */
    public record RewindComplete(String messageId, List<String> files) {}

    // ==================== #25: 心跳 ====================

    /** #25 pong — 心跳响应 */
    public record Pong() {}
}
