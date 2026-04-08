package com.aicodeassistant.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记忆管理 Controller — 管理 CLAUDE.md 等记忆条目。
 *
 * @see <a href="SPEC §6.1.8 #15-#16">记忆端点</a>
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private static final Logger log = LoggerFactory.getLogger(MemoryController.class);

    private final JdbcTemplate globalJdbcTemplate;

    public MemoryController(@Qualifier("globalJdbcTemplate") JdbcTemplate globalJdbcTemplate) {
        this.globalJdbcTemplate = globalJdbcTemplate;
    }

    /** 获取记忆条目列表 */
    @GetMapping
    public ResponseEntity<Map<String, List<MemoryEntry>>> getMemories() {
        List<MemoryEntry> entries = globalJdbcTemplate.query(
                "SELECT id, category, title, content, keywords, scope, created_at, updated_at FROM memories ORDER BY updated_at DESC",
                (rs, rowNum) -> new MemoryEntry(
                        rs.getString("id"),
                        rs.getString("category"),
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getString("keywords"),
                        rs.getString("scope"),
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
                    "UPDATE memories SET category = ?, title = ?, content = ?, keywords = ?, scope = ?, updated_at = ? WHERE id = ?",
                    entry.category(), entry.title(), entry.content(),
                    entry.keywords(), entry.scope(), now, id);

            if (updated == 0) {
                globalJdbcTemplate.update(
                        "INSERT INTO memories (id, category, title, content, keywords, scope, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        id, entry.category(), entry.title(), entry.content(),
                        entry.keywords(), entry.scope() != null ? entry.scope() : "global",
                        now, now);
            }
        }
        log.info("Updated {} memory entries", request.entries().size());
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ═══ DTO Records ═══
    public record MemoryEntry(String id, String category, String title,
                               String content, String keywords, String scope,
                               String createdAt, String updatedAt) {}
    public record UpdateMemoriesRequest(List<MemoryEntry> entries) {}
}
