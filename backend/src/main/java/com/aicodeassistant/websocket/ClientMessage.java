package com.aicodeassistant.websocket;

import java.util.List;
import java.util.Map;

/**
 * WebSocket Client → Server 消息 Payload 类型 (§8.5.1b, §8.5.2, 10 种)。
 * <p>
 * 每种 Payload 对应 {@link WebSocketController} 中的一个 {@code @MessageMapping} 处理器。
 */
public final class ClientMessage {

    private ClientMessage() {}

    /** #1 user_message → /app/chat */
    public record UserMessagePayload(
            String text,
            List<Attachment> attachments,
            List<Reference> references
    ) {
        public record Attachment(String type, String path, String mediaType, String base64Data, String url) {}
        public record Reference(String type, String path, Integer startLine, Integer endLine) {}
    }

    /** #2 permission_response → /app/permission */
    public record PermissionResponsePayload(
            String toolUseId,
            String decision,  // "allow" | "deny" | "allow_always"
            boolean remember,
            String scope      // "session" | "project" | "global"
    ) {}

    /** #3 interrupt → /app/interrupt (无字段) */
    public record InterruptPayload() {}

    /** #4 set_model → /app/model */
    public record SetModelPayload(String model) {}

    /** #5 set_permission_mode → /app/permission-mode */
    public record SetPermissionModePayload(String mode) {}

    /** #6 slash_command → /app/command */
    public record SlashCommandPayload(String command, String args) {}

    /** #7 mcp_operation → /app/mcp */
    public record McpOperationPayload(
            String operation,   // "connect" | "disconnect" | "restart" | "list_tools" | "approve"
            String serverId,
            Map<String, Object> config
    ) {}

    /** #8 rewind_files → /app/rewind */
    public record RewindFilesPayload(String messageId, List<String> filePaths) {}

    /** #9 elicitation_response → /app/elicitation */
    public record ElicitationResponsePayload(String requestId, Object answer) {}

    /** #10 ping → /app/ping (无字段) */
    public record PingPayload() {}
}
