package com.aicodeassistant.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 便捷构造 CoordinatorEventBus envelope / STOMP payload 的测试 Builder。
 *
 * <p>对应 Task3-5 方案 §11.11 资产 #12。封装 envelope 7 字段（type/ts/uuid/sessionId/workflowId/eventType/payload）
 * 与常见 payload 键，避免测试用例中大量 Map.of 冗余。
 *
 * <p>示例：
 * <pre>{@code
 *   Map<String,Object> env = MessagesTestBuilder.envelope()
 *       .session("sess-1").workflow("wf-1").eventType("phase_transition")
 *       .payload("previousPhase", "PLANNING")
 *       .payload("nextPhase", "EXECUTING")
 *       .build();
 * }</pre>
 */
public final class MessagesTestBuilder {

    private MessagesTestBuilder() {}

    public static EnvelopeBuilder envelope() {
        return new EnvelopeBuilder();
    }

    public static class EnvelopeBuilder {
        private String sessionId = "sess-1";
        private String workflowId = "wf-1";
        private String eventType = "phase_transition";
        private String uuid = java.util.UUID.randomUUID().toString();
        private long ts = System.currentTimeMillis();
        private final Map<String, Object> payload = new LinkedHashMap<>();

        public EnvelopeBuilder session(String sessionId) { this.sessionId = sessionId; return this; }
        public EnvelopeBuilder workflow(String workflowId) { this.workflowId = workflowId; return this; }
        public EnvelopeBuilder eventType(String eventType) { this.eventType = eventType; return this; }
        public EnvelopeBuilder uuid(String uuid) { this.uuid = uuid; return this; }
        public EnvelopeBuilder ts(long ts) { this.ts = ts; return this; }
        public EnvelopeBuilder payload(String key, Object value) { this.payload.put(key, value); return this; }

        public Map<String, Object> build() {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "coordinator_event");
            envelope.put("ts", ts);
            envelope.put("uuid", uuid);
            envelope.put("sessionId", sessionId);
            envelope.put("workflowId", workflowId);
            envelope.put("eventType", eventType);
            envelope.put("payload", Map.copyOf(payload));
            return envelope;
        }
    }

    /** phase_transition 常见 payload 快捷工厂。 */
    public static Map<String, Object> phaseTransitionPayload(String previousPhase, String nextPhase) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("previousPhase", previousPhase);
        m.put("nextPhase", nextPhase);
        return m;
    }

    /** mailbox_write 常见 payload 快捷工厂。 */
    public static Map<String, Object> mailboxWritePayload(String senderId, String recipientId, int contentLength) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("senderId", senderId);
        m.put("recipientId", recipientId);
        m.put("contentLength", contentLength);
        return m;
    }
}
