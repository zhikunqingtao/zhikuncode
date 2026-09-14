package com.aicodeassistant.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
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
            String sourceToolAssistantUUID,
            /**
             * 通用客户端元数据（持久化于 messages.meta_json，REST/WS 历史原样回传）。
             * 例：运行中追加的 steering 指令携带 {"steering": true}，
             * 供前端轮次投影在刷新后仍能识别 steering 边界。
             */
            @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> meta
    ) implements Message {

        /** 兼容旧调用方的 5 参构造（meta = null）。 */
        public UserMessage(
                String uuid,
                Instant timestamp,
                List<ContentBlock> content,
                String toolUseResult,
                String sourceToolAssistantUUID) {
            this(uuid, timestamp, content, toolUseResult, sourceToolAssistantUUID, null);
        }
    }

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
