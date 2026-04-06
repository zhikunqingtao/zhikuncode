package com.aicodeassistant.model;

import java.time.Instant;
import java.util.List;

/**
 * 消息模型 — sealed interface 保证类型穷举。
 * 含 UserMessage / AssistantMessage / SystemMessage 三种子类型。
 *
 * @see <a href="SPEC §5.1">消息模型</a>
 */
public sealed interface Message {

    String uuid();

    Instant timestamp();

    record UserMessage(
            String uuid,
            Instant timestamp,
            List<ContentBlock> content,
            String toolUseResult,
            String sourceToolAssistantUUID
    ) implements Message {}

    record AssistantMessage(
            String uuid,
            Instant timestamp,
            List<ContentBlock> content,
            String stopReason,
            Usage usage
    ) implements Message {}

    record SystemMessage(
            String uuid,
            Instant timestamp,
            String content,
            SystemMessageType type
    ) implements Message {}
}
