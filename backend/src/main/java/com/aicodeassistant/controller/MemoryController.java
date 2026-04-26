package com.aicodeassistant.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.aicodeassistant.memdir.MemdirService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记忆管理 Controller — 管理 PROJECT.md 等记忆条目。
 *
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private static final Logger log = LoggerFactory.getLogger(MemoryController.class);

    private final JdbcTemplate globalJdbcTemplate;
    private final MemdirService memdirService;

    public MemoryController(@Qualifier("globalJdbcTemplate") JdbcTemplate globalJdbcTemplate,
                            MemdirService memdirService) {
        this.globalJdbcTemplate = globalJdbcTemplate;
        this.memdirService = memdirService;
    }

    /** 获取记忆条目列表 */
    @GetMapping
    public ResponseEntity<Map<String, List<MemoryEntry>>> getMemories() {
        List<MemoryEntry> entries = globalJdbcTemplate.query(
                "SELECT id, category, title, content, keywords, scope, source, created_at, updated_at FROM memories ORDER BY updated_at DESC",
                (rs, rowNum) -> new MemoryEntry(
                        rs.getString("id"),
                        rs.getString("category"),
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getString("keywords"),
                        rs.getString("scope"),
                        rs.getString("source"),
                        rs.getString("created_at"),
                        rs.getString("updated_at")));
        return ResponseEntity.ok(Map.of("entries", entries));
    }

    /** 更新记忆条目（批量覆盖） */
    @PutMapping
    public ResponseEntity<Map<String, Object>> updateMemories(
            @RequestBody UpdateMemoriesRequest request) {
        for (MemoryEntry entry : request.entries()) {
            String id = entry.id() != null ? entry.id() : UUID.randomUUID().toString();
            String now = Instant.now().toString();

            int updated = globalJdbcTemplate.update(
                    "UPDATE memories SET category = ?, title = ?, content = ?, keywords = ?, scope = ?, source = ?, updated_at = ? WHERE id = ?",
                    entry.category(), entry.title(), entry.content(),
                    entry.keywords(), entry.scope(),
                    entry.source() != null ? entry.source() : "USER",
                    now, id);

            if (updated == 0) {
                globalJdbcTemplate.update(
                        "INSERT INTO memories (id, category, title, content, keywords, scope, source, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        id, entry.category(), entry.title(), entry.content(),
                        entry.keywords(), entry.scope() != null ? entry.scope() : "global",
                        entry.source() != null ? entry.source() : "USER",
                        now, now);
            }
        }
        log.info("Updated {} memory entries", request.entries().size());
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** 创建单条记忆 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createMemory(@RequestBody MemoryEntry entry) {
        String id = entry.id() != null ? entry.id() : UUID.randomUUID().toString();
        String now = Instant.now().toString();

        globalJdbcTemplate.update(
                "INSERT INTO memories (id, category, title, content, keywords, scope, source, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, entry.category(), entry.title(), entry.content(),
                entry.keywords(), entry.scope() != null ? entry.scope() : "global",
                entry.source() != null ? entry.source() : "USER",
                now, now);

        log.info("Created memory entry: id={}, source={}", id,
                entry.source() != null ? entry.source() : "USER");
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "success", true,
                "id", id));
    }

    /** 统一查询所有记忆（SQLite + MEMORY.md） */
    @GetMapping("/all")
    public ResponseEntity<Map<String, Object>> getAllMemories() {
        List<MemoryEntry> sqliteEntries = globalJdbcTemplate.query(
                "SELECT id, category, title, content, keywords, scope, source, created_at, updated_at FROM memories ORDER BY updated_at DESC",
                (rs, rowNum) -> new MemoryEntry(
                        rs.getString("id"), rs.getString("category"), rs.getString("title"),
                        rs.getString("content"), rs.getString("keywords"), rs.getString("scope"),
                        rs.getString("source"), rs.getString("created_at"), rs.getString("updated_at")));

        List<MemdirService.MemoryEntry> mdEntries = memdirService.listEntries();

        return ResponseEntity.ok(Map.of(
                "sqlite", sqliteEntries,
                "memoryMd", mdEntries.stream().map(e -> Map.of(
                        "source", e.source().name(),
                        "category", e.category().tag(),
                        "timestamp", e.timestamp().toString(),
                        "content", e.content()
                )).toList()
        ));
    }

    /** 删除单条记忆 */
    @DeleteMapping("/{memoryId}")
    public ResponseEntity<Void> deleteMemory(@PathVariable String memoryId) {
        int deleted = globalJdbcTemplate.update(
                "DELETE FROM memories WHERE id = ?", memoryId);
        if (deleted > 0) {
            log.info("Deleted memory entry: id={}", memoryId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    // ═══ DTO Records ═══
    public record MemoryEntry(String id, String category, String title,
                               String content, String keywords, String scope,
                               String source, String createdAt, String updatedAt) {}
    public record UpdateMemoriesRequest(List<MemoryEntry> entries) {}
}
