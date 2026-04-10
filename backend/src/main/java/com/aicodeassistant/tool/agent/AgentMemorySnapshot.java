package com.aicodeassistant.tool.agent;

import com.aicodeassistant.model.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Agent 记忆快照 — 对齐原版 agentMemory.ts。
 * 子代理执行前/后保存上下文快照，支持断点恢复。
 *
 * 存储路径: ~/.ai-code-assistant/agent-snapshots/{agentId}.json
 */
@Component
public class AgentMemorySnapshot {

    private static final Logger log = LoggerFactory.getLogger(AgentMemorySnapshot.class);
    private static final long MAX_SNAPSHOT_SIZE = 10 * 1024 * 1024; // 10MB

    private final Path snapshotDir;
    private final ObjectMapper objectMapper;

    public AgentMemorySnapshot(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.snapshotDir = Path.of(System.getProperty("user.home"),
            ".ai-code-assistant", "agent-snapshots");
    }

    public record Snapshot(
        String agentId,
        String taskDescription,
        List<Message> messages,
        Instant createdAt,
        String parentSessionId,
        int nestingDepth,
        String workingDirectory,
        String model
    ) {}

    public void save(Snapshot snapshot) throws IOException {
        Files.createDirectories(snapshotDir);
        Path file = snapshotDir.resolve(snapshot.agentId() + ".json");
        objectMapper.writeValue(file.toFile(), snapshot);

        long size = Files.size(file);
        if (size > MAX_SNAPSHOT_SIZE) {
            log.warn("Agent snapshot exceeds size limit: {} bytes for agent {}",
                size, snapshot.agentId());
        }
        log.info("Saved agent snapshot: id={}, messages={}, size={}KB",
            snapshot.agentId(), snapshot.messages().size(), size / 1024);
    }

    public Snapshot load(String agentId) throws IOException {
        Path file = snapshotDir.resolve(agentId + ".json");
        if (!Files.exists(file)) return null;
        Snapshot snapshot = objectMapper.readValue(file.toFile(), Snapshot.class);
        log.info("Loaded agent snapshot: id={}, messages={}",
            agentId, snapshot.messages().size());
        return snapshot;
    }

    public void delete(String agentId) throws IOException {
        boolean deleted = Files.deleteIfExists(snapshotDir.resolve(agentId + ".json"));
        if (deleted) {
            log.info("Deleted agent snapshot: {}", agentId);
        }
    }

    public List<String> listSnapshots() throws IOException {
        if (!Files.isDirectory(snapshotDir)) return List.of();
        try (var stream = Files.list(snapshotDir)) {
            return stream.filter(p -> p.toString().endsWith(".json"))
                .map(p -> p.getFileName().toString().replace(".json", ""))
                .toList();
        }
    }

    public int purgeExpired() throws IOException {
        if (!Files.isDirectory(snapshotDir)) return 0;
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        int purged = 0;
        try (var stream = Files.list(snapshotDir)) {
            for (Path p : stream.filter(f -> f.toString().endsWith(".json")).toList()) {
                try {
                    Instant modified = Files.getLastModifiedTime(p).toInstant();
                    if (modified.isBefore(cutoff)) {
                        Files.delete(p);
                        purged++;
                    }
                } catch (IOException e) {
                    log.warn("Failed to check/delete snapshot: {}", p, e);
                }
            }
        }
        if (purged > 0) {
            log.info("Purged {} expired agent snapshots", purged);
        }
        return purged;
    }
}
