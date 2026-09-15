package com.aicodeassistant.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SystemMessage Jackson 序列化形态断言（原一次性探针转正）。
 * <p>
 * 背景：SystemMessage record 组件名 type 与多态 discriminator "type" 冲突，
 * 旧形态会输出重复的 "type" 键（{"type":"system",...,"type":"INFO"}）。
 * 修复后：type 组件映射为 kind，JSON 只保留 discriminator 的单一 "type":"system"，
 * 业务字段为 subtype / metadata（与前端契约一致），缺省时不输出。
 */
class MessageSerializationProbeTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void systemMessageSerializesSingleTypeKeyWithSubtypeAndMetadata() throws Exception {
        Message.SystemMessage sys = new Message.SystemMessage(
                "uuid-1", Instant.parse("2025-01-01T00:00:00Z"), "",
                SystemMessageType.INFO, "task_boundary",
                Map.of("task_id", "t1", "title", "Task A", "seq", 1, "turn_index", 2));

        // 多态序列化（REST 历史接口路径，writerFor(Message.class)）
        String polyJson = mapper.writerFor(Message.class).writeValueAsString(sys);
        assertThat(polyJson).containsOnlyOnce("\"type\":\"system\"");
        assertThat(polyJson).doesNotContain("\"type\":\"INFO\"");
        assertThat(polyJson).contains("\"subtype\":\"task_boundary\"");
        assertThat(polyJson).contains("\"task_id\":\"t1\"");
        assertThat(polyJson).contains("\"seq\":1");

        // 直接序列化（具体类型）同样只有单一 type 键
        String directJson = mapper.writeValueAsString(sys);
        assertThat(directJson).containsOnlyOnce("\"type\":\"system\"");
        assertThat(directJson).contains("\"subtype\":\"task_boundary\"");
    }

    @Test
    void systemMessageWithoutSubtypeOmitsSubtypeAndMetadataKeys() throws Exception {
        // 4 参兼容构造：subtype / metadata 均为 null → JSON 不输出这两个键
        Message.SystemMessage sys = new Message.SystemMessage(
                "uuid-2", Instant.parse("2025-01-01T00:00:00Z"), "plain",
                SystemMessageType.WARNING);

        String json = mapper.writerFor(Message.class).writeValueAsString(sys);

        assertThat(json).containsOnlyOnce("\"type\":\"system\"");
        assertThat(json).doesNotContain("subtype");
        assertThat(json).doesNotContain("metadata");
        assertThat(json).contains("\"kind\":\"WARNING\"");
    }

    @Test
    void systemMessageRoundTripDeserializesSubtypeAndMetadata() throws Exception {
        Message.SystemMessage sys = new Message.SystemMessage(
                "uuid-3", Instant.parse("2025-01-01T00:00:00Z"), "",
                SystemMessageType.INFO, "task_boundary",
                Map.of("task_id", "t9", "title", "Task Nine", "seq", 3, "turn_index", 1));

        String json = mapper.writerFor(Message.class).writeValueAsString(sys);
        Message back = mapper.readValue(json, Message.class);

        assertThat(back).isInstanceOf(Message.SystemMessage.class);
        Message.SystemMessage sysBack = (Message.SystemMessage) back;
        assertThat(sysBack.uuid()).isEqualTo("uuid-3");
        assertThat(sysBack.subtype()).isEqualTo("task_boundary");
        assertThat(sysBack.metadata())
                .containsEntry("task_id", "t9")
                .containsEntry("title", "Task Nine")
                .containsEntry("seq", 3)
                .containsEntry("turn_index", 1);
        // 业务种类与角色 discriminator 分离，往返后不丢失。
        assertThat(sysBack.type()).isEqualTo(SystemMessageType.INFO);
    }

    @Test
    void compactSummaryKindSurvivesRoundTrip() throws Exception {
        Message original = new Message.SystemMessage("summary", Instant.now(), "summary",
                SystemMessageType.COMPACT_SUMMARY);
        Message.SystemMessage restored = (Message.SystemMessage) mapper.readValue(
                mapper.writerFor(Message.class).writeValueAsString(original), Message.class);
        assertThat(restored.type()).isEqualTo(SystemMessageType.COMPACT_SUMMARY);
    }

    @Test
    void snapshotReloadPreservesCompactionBoundary(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var snapshots = new com.aicodeassistant.tool.agent.AgentMemorySnapshot(mapper);
        org.springframework.test.util.ReflectionTestUtils.setField(snapshots, "snapshotDir", directory);
        Message summary = new Message.SystemMessage("summary", Instant.now(), "summary", SystemMessageType.COMPACT_SUMMARY);
        snapshots.save(new com.aicodeassistant.tool.agent.AgentMemorySnapshot.Snapshot(
                "agent", "task", java.util.List.of(summary), Instant.now(), "parent", 1, ".", "model"));
        var restored = snapshots.load("agent");
        var compact = new com.aicodeassistant.engine.CompactService(
                new com.aicodeassistant.engine.TokenCounter(null, null, null),
                new com.aicodeassistant.llm.LlmProviderRegistry(java.util.List.of(), null), null, null);
        assertThat(compact.planCompaction(restored.messages(), 100000, 1).frozenMessages()).hasSize(1);
        assertThat(((Message.SystemMessage) restored.messages().getFirst()).type()).isEqualTo(SystemMessageType.COMPACT_SUMMARY);
    }

    @Test
    void legacyDuplicateTypeSnapshotRemainsReadableButIsWrittenAsKind() throws Exception {
        String legacy = "{\"type\":\"system\",\"uuid\":\"old-summary\","
                + "\"timestamp\":\"2025-01-01T00:00:00Z\",\"content\":\"summary\",\"type\":\"COMPACT_SUMMARY\"}";
        Message.SystemMessage restored = (Message.SystemMessage) mapper.readValue(legacy, Message.class);
        assertThat(restored.type()).isEqualTo(SystemMessageType.COMPACT_SUMMARY);
        String rewritten = mapper.writerFor(Message.class).writeValueAsString(restored);
        assertThat(rewritten).containsOnlyOnce("\"type\":").contains("\"kind\":\"COMPACT_SUMMARY\"");
    }

    @Test
    void legacyShapeSystemMessageDeserializesWithoutSubtype() throws Exception {
        // 旧形态 JSON（无 subtype/metadata 键）反序列化不炸，两字段为 null
        String legacyJson = "{\"type\":\"system\",\"uuid\":\"uuid-4\","
                + "\"timestamp\":\"2025-01-01T00:00:00Z\",\"content\":\"hello\"}";

        Message back = mapper.readValue(legacyJson, Message.class);

        assertThat(back).isInstanceOf(Message.SystemMessage.class);
        Message.SystemMessage sysBack = (Message.SystemMessage) back;
        assertThat(sysBack.content()).isEqualTo("hello");
        assertThat(sysBack.type()).isNull();
        assertThat(sysBack.subtype()).isNull();
        assertThat(sysBack.metadata()).isNull();
    }
}
