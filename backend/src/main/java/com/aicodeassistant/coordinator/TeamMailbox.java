package com.aicodeassistant.coordinator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 团队消息邮箱 — Agent 间线程安全的消息传递机制。
 * <p>
 * 每个 Agent 有独立的收件箱（ConcurrentLinkedQueue），
 * 支持 writeToMailbox/readMailbox 异步消息通信。
 *
 * @see <a href="SPEC §11">Team/Swarm 多Agent协作</a>
 */
@Component
public class TeamMailbox {

    private static final Logger log = LoggerFactory.getLogger(TeamMailbox.class);

    /** agentId → 消息队列 */
    private final Map<String, ConcurrentLinkedQueue<MailMessage>> mailboxes = new ConcurrentHashMap<>();

    /**
     * 发送消息到指定 Agent 的邮箱。
     *
     * @param recipientId 接收者 Agent ID
     * @param senderId    发送者 Agent ID
     * @param content     消息内容
     */
    public void writeToMailbox(String recipientId, String senderId, String content) {
        ConcurrentLinkedQueue<MailMessage> inbox = mailboxes.computeIfAbsent(
                recipientId, k -> new ConcurrentLinkedQueue<>());
        MailMessage msg = new MailMessage(senderId, recipientId, content, Instant.now());
        inbox.offer(msg);
        log.debug("Mail sent: {} -> {} ({} chars)", senderId, recipientId, content.length());
    }

    /**
     * 读取指定 Agent 的所有待处理消息（非阻塞，drain 方式）。
     *
     * @param agentId Agent ID
     * @return 所有待处理消息（队列被清空）
     */
    public List<MailMessage> readMailbox(String agentId) {
        ConcurrentLinkedQueue<MailMessage> inbox = mailboxes.get(agentId);
        if (inbox == null || inbox.isEmpty()) {
            return List.of();
        }

        List<MailMessage> messages = new ArrayList<>();
        MailMessage msg;
        while ((msg = inbox.poll()) != null) {
            messages.add(msg);
        }
        log.debug("Mail read: {} got {} messages", agentId, messages.size());
        return messages;
    }

    /**
     * 查看邮箱中的消息数量（不消费）。
     */
    public int getMailboxSize(String agentId) {
        ConcurrentLinkedQueue<MailMessage> inbox = mailboxes.get(agentId);
        return inbox != null ? inbox.size() : 0;
    }

    /**
     * 广播消息到团队所有成员。
     *
     * @param teamPrefix 团队前缀（如 "team1-worker-"）
     * @param senderId   发送者 ID
     * @param content    消息内容
     */
    public void broadcast(String teamPrefix, String senderId, String content) {
        mailboxes.keySet().stream()
                .filter(id -> id.startsWith(teamPrefix) && !id.equals(senderId))
                .forEach(id -> writeToMailbox(id, senderId, content));
    }

    /**
     * 清除指定 Agent 的邮箱。
     */
    public void clearMailbox(String agentId) {
        mailboxes.remove(agentId);
    }

    /**
     * 清除所有邮箱。
     */
    public void clearAll() {
        mailboxes.clear();
        log.info("All mailboxes cleared");
    }

    // ── DTO ──────────────────────────────────────────────────

    /** 邮箱消息 */
    public record MailMessage(
            String senderId,
            String recipientId,
            String content,
            Instant timestamp
    ) {}
}
