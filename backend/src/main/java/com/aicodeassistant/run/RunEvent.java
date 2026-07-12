package com.aicodeassistant.run;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 运行事件 — 记录运行过程中的单个事件。
 * <p>
 * 每个事件属于一个 RunEnvelope，通过 seq 保证顺序。
 */
public record RunEvent(
        Long id,
        String runId,
        int seq,
        String eventType,
        String eventData,
        long ts
) {

    /**
     * 创建事件 — data 对象序列化为 JSON。
     */
    public static RunEvent of(String runId, int seq, String eventType, Object data) {
        String json;
        try {
            json = new ObjectMapper().writeValueAsString(data);
        } catch (Exception e) {
            json = "{}";
        }
        return new RunEvent(null, runId, seq, eventType, json, System.currentTimeMillis());
    }

    /**
     * 创建事件 — 直接传入 JSON 字符串。
     */
    public static RunEvent ofRaw(String runId, int seq, String eventType, String jsonData) {
        return new RunEvent(null, runId, seq, eventType, jsonData, System.currentTimeMillis());
    }
}
