package com.aicodeassistant.model;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonSubTypes;

/**
 * 消息模型 — sealed interface 保证类型穷举。
 * 含 UserMessage / AssistantMessage / SystemMessage 三种子类型。
 *
 * ★ Jackson 多态序列化配置 ★
 * sealed interface 需要 @JsonTypeInfo + @JsonSubTypes 才能正确序列化/反序列化。
 * 使用 NAME 策略将类型信息写入 JSON 的 "type" 字段。
 *
 * @see <a href="SPEC §5.1">消息模型</a>
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = Message.UserMessage.class, name = "user"),
    @JsonSubTypes.Type(value = Message.AssistantMessage.class, name = "assistant"),
    @JsonSubTypes.Type(value = Message.SystemMessage.class, name = "system")
})
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
