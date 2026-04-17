package com.aicodeassistant.tool.config;

import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SendMessageTool — 向指定的后台任务或子代理发送消息。
 * <p>
 * 支持单播和广播（"*"）两种模式。
 * 消息写入目标代理的邮箱队列，通过 STOMP 推送通知。
 *
 * @see <a href="SPEC §4.1.14">SendMessageTool</a>
 */
@Component
public class SendMessageTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SendMessageTool.class);

    private final SimpMessagingTemplate messagingTemplate;

    /** 内存邮箱 — agentId → 消息列表 */
    private final ConcurrentMap<String, List<MailboxMessage>> mailboxes = new ConcurrentHashMap<>();

    /** 已注册的代理 ID */
    private final Set<String> registeredAgents = ConcurrentHashMap.newKeySet();

    public SendMessageTool(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public String getName() {
        return "SendMessage";
    }

    @Override
    public String getDescription() {
        return "Send a message to a background task or sub-agent. " +
                "Use '*' as the target to broadcast to all agents.";
    }

    @Override
    public String prompt() {
        return """
                Send a message to another agent.

                Example: {"to": "researcher", "summary": "assign task 1", "message": "start on task #1"}

                | `to` | |
                |---|---|
                | `"researcher"` | Teammate by name |
                | `"*"` | Broadcast to all teammates — expensive (linear in team size), use only when \
                everyone genuinely needs it |

                Your plain text output is NOT visible to other agents — to communicate, you MUST call \
                this tool. Messages from teammates are delivered automatically; you don't check an inbox. \
                Refer to teammates by name, never by UUID. When relaying, don't quote the original — \
                it's already rendered to the user.

                Protocol responses (legacy):
                If you receive a JSON message with `type: "shutdown_request"` or `type: "plan_approval_request"`, \
                respond with the matching `_response` type — echo the `request_id`, set `approve` true/false:
                - {"to": "team-lead", "message": {"type": "shutdown_response", "request_id": "...", "approve": true}}
                - {"to": "researcher", "message": {"type": "plan_approval_response", "request_id": "...", \
                "approve": false, "feedback": "add error handling"}}

                Approving shutdown terminates your process. Rejecting plan sends the teammate back to revise. \
                Don't originate `shutdown_request` unless asked. Don't send structured JSON status messages — \
                use TaskUpdate.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "to", Map.of(
                                "type", "string",
                                "description", "Target agent name ('*' for broadcast)"),
                        "message", Map.of(
                                "type", "string",
                                "description", "Message content"),
                        "summary", Map.of(
                                "type", "string",
                                "description", "Message summary for log compression")
                ),
                "required", List.of("to", "message")
        );
    }

    @Override
    public String getGroup() {
        return "agent";
    }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.NONE;
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return true;
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String to = input.getString("to");
        String message = input.getString("message");
        String summary = input.getString("summary", "");
        String senderId = (context.agentHierarchy() != null && !context.agentHierarchy().isBlank())
                ? context.agentHierarchy()
                : "main";

        // 1. 广播模式
        if ("*".equals(to)) {
            int count = 0;
            List<String> recipients = new ArrayList<>();
            for (String agentId : registeredAgents) {
                writeToMailbox(agentId, senderId, message, summary);
                recipients.add(agentId);
                count++;
            }
            log.info("Broadcast message from {} to {} agents", senderId, count);
            return ToolResult.success(
                    "Message broadcast to " + count + " agents: "
                            + String.join(", ", recipients));
        }

        // 2. 单播: 检查目标是否存在
        if (!registeredAgents.contains(to)) {
            return ToolResult.error("Agent not found: " + to);
        }

        // 3. 写入目标邮箱
        writeToMailbox(to, senderId, message, summary);

        // 4. STOMP 通知
        try {
            String safeSenderId = senderId.replaceAll("[^a-zA-Z0-9_\\->/\\s]", "_");
            messagingTemplate.convertAndSend(
                    "/topic/session/" + context.sessionId(),
                    Map.of("type", "agent_message",
                            "from", safeSenderId,
                            "to", to,
                            "timestamp", Instant.now().toEpochMilli()));
        } catch (Exception e) {
            log.warn("Failed to send message notification: {}", e.getMessage());
        }

        return ToolResult.success(
                "Message sent to " + to + " from " + senderId + ".");
    }

    /** 注册代理（供 SubAgentExecutor 调用） */
    public void registerAgent(String agentId) {
        registeredAgents.add(agentId);
        mailboxes.putIfAbsent(agentId, new CopyOnWriteArrayList<>());
    }

    /** 注销代理 */
    public void unregisterAgent(String agentId) {
        registeredAgents.remove(agentId);
        mailboxes.remove(agentId);
    }

    /** 读取邮箱消息 */
    public List<MailboxMessage> readMailbox(String agentId) {
        List<MailboxMessage> messages = mailboxes.get(agentId);
        return messages != null ? List.copyOf(messages) : List.of();
    }

    private void writeToMailbox(String to, String from, String message, String summary) {
        mailboxes.computeIfAbsent(to, k -> new CopyOnWriteArrayList<>())
                .add(new MailboxMessage(from, message, summary, Instant.now()));
    }

    /** 邮箱消息记录 */
    public record MailboxMessage(String from, String message, String summary, Instant timestamp) {}
}
