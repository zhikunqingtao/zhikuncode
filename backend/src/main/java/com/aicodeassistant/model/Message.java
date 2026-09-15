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

    /**
     * @param type     后端系统消息种类，以 JSON "kind" 往返保存；"type" 仅表示消息角色。
     *                 兼容旧快照中角色判别字段之后重复的业务 type；两者均缺失时保持 null。
     * @param subtype  业务子类型（如 "task_boundary"），对应前端 system 消息的 subtype 字段；可空
     * @param metadata 业务元数据（如 task_boundary 的 {task_id,title,seq,turn_index}），
     *                 持久化于 messages.meta_json，REST/WS 历史原样回传；
     *                 对应前端 system 消息的 metadata 字段；可空
     */
    record SystemMessage(
            String uuid,
            Instant timestamp,
            String content,
            @com.fasterxml.jackson.annotation.JsonProperty("kind")
            @com.fasterxml.jackson.annotation.JsonAlias("type") SystemMessageType type,
            @JsonInclude(JsonInclude.Include.NON_NULL) String subtype,
            @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> metadata
    ) implements Message {

        /** 兼容旧调用方的 4 参构造（subtype/metadata = null）。 */
        public SystemMessage(
                String uuid,
                Instant timestamp,
                String content,
                SystemMessageType type) {
            this(uuid, timestamp, content, type, null, null);
        }
    }
}
