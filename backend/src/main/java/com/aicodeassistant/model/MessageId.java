package com.aicodeassistant.model;

import java.util.Objects;
import java.util.UUID;

/**
 * 品牌化消息 ID — 标识一条消息。
 *
 * @see <a href="SPEC §5.0">品牌化 ID 类型</a>
 */
public record MessageId(String value) {

    public MessageId {
        Objects.requireNonNull(value, "MessageId cannot be null");
    }

    public static MessageId create() {
        return new MessageId(UUID.randomUUID().toString());
    }

    public static MessageId of(String raw) {
        return new MessageId(raw);
    }

    @Override
    public String toString() {
        return value;
    }
}
