package com.aicodeassistant.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.aicodeassistant.memdir.MemdirService;
import com.aicodeassistant.memdir.MemoryCategory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    // ═══ MEMORY.md 文件级端点 (前端"卡片+整篇"双模式编辑器) ═══

    /** 读取 MEMORY.md 全文 + 解析条目 + 文件元信息 (size 单位为字符数，与 maxSize 一致) */
    @GetMapping("/file")
    public ResponseEntity<Map<String, Object>> getMemoryFile() {
        return ResponseEntity.ok(snapshotBody(memdirService.readMemoryFileSnapshot()));
    }

    /**
     * 整篇覆盖保存 MEMORY.md (乐观并发: baseUpdatedAt 与当前 mtime 不一致 → 409)。
     * <p>
     * mtime 比对在 MemdirService 的 writeLock 内完成 (与写盘同锁)，
     * 消除同一服务实例内从检测到写盘之间被并发追加静默覆盖的窗口。
     */
    @PutMapping("/file")
    public ResponseEntity<Map<String, Object>> updateMemoryFile(
            @RequestBody UpdateMemoryFileRequest request) {
        Instant baseMtime;
        try {
            baseMtime = parseBaseMtime(request.baseUpdatedAt());
        } catch (RuntimeException e) {
            // 无法解析的基线无法证明一致 → 保守按冲突处理
            return conflictResponse("baseUpdatedAt 无法解析，无法证明与当前版本一致；请重新加载后重试");
        }
        try {
            var snapshot = memdirService.overwriteFromUserEditSnapshot(
                    request.content() != null ? request.content() : "", baseMtime);
            return overwriteSuccessResponse(snapshot);
        } catch (MemdirService.MemdirConflictException e) {
            return conflictResponse(e.getMessage());
        } catch (IllegalArgumentException e) {
            return tooLargeResponse(e);
        }
    }

    /** 卡片模式提交整个条目列表覆盖保存 MEMORY.md (冲突检测同上，锁内完成) */
    @PutMapping("/file/entries")
    public ResponseEntity<Map<String, Object>> updateMemoryFileEntries(
            @RequestBody UpdateMemoryFileEntriesRequest request) {
        Instant baseMtime;
        try {
            baseMtime = parseBaseMtime(request.baseUpdatedAt());
        } catch (RuntimeException e) {
            // 无法解析的基线无法证明一致 → 保守按冲突处理
            return conflictResponse("baseUpdatedAt 无法解析，无法证明与当前版本一致；请重新加载后重试");
        }
        List<MemdirService.MemoryEntry> entries = (request.entries() != null
                ? request.entries() : List.<MemoryFileEntryInput>of()).stream()
                .filter(Objects::nonNull)
                .map(this::toServiceEntry)
                .toList();
        try {
            var snapshot = memdirService.overwriteEntriesFromUserEditSnapshot(entries, baseMtime);
            return overwriteSuccessResponse(snapshot);
        } catch (MemdirService.MemdirConflictException e) {
            return conflictResponse(e.getMessage());
        } catch (IllegalArgumentException e) {
            return tooLargeResponse(e);
        }
    }

    /**
     * 宽松转换 DTO → 条目: 非法 source/timestamp/category 分别降级 USER/EPOCH/SEMANTIC。
     */
    private MemdirService.MemoryEntry toServiceEntry(MemoryFileEntryInput in) {
        MemdirService.MemorySource source = MemdirService.MemorySource.USER;
        if (in.source() != null) {
            try {
                source = MemdirService.MemorySource.valueOf(in.source().trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 非法 source 降级 USER
            }
        }
        Instant timestamp = Instant.EPOCH;
        if (in.timestamp() != null) {
            try {
                timestamp = Instant.parse(in.timestamp().trim());
            } catch (RuntimeException ignored) {
                // 非法 timestamp 降级 EPOCH
            }
        }
        // fromTag 对 null/未知标签默认 SEMANTIC
        return new MemdirService.MemoryEntry(source, timestamp, in.content(),
                MemoryCategory.fromTag(in.category()));
    }

    /**
     * 解析乐观并发基线 baseUpdatedAt 为 Instant，交给 Service 在 writeLock 内与当前 mtime 比对。
     * <p>
     * 比较方案说明: 采用 Instant 解析后比较而非字符串精确比较 — GET 响应中的 updatedAt
     * 由 Instant.toString() 生成，Instant.parse(toString()) 可无损往返 (纳秒精度完整保留)，
     * 解析后比较可避免同一时刻的不同字符串写法 (时区/精度表示差异) 造成误判。
     * baseUpdatedAt 为 null/空白时返回 null，表示客户端加载时文件不存在 (文件已存在则 409)；
     * 无法解析的基线无法证明一致，抛 DateTimeParseException 由调用方保守按 409 冲突处理。
     */
    private Instant parseBaseMtime(String baseUpdatedAt) {
        if (baseUpdatedAt == null || baseUpdatedAt.isBlank()) return null;
        return Instant.parse(baseUpdatedAt.trim());
    }

    private ResponseEntity<Map<String, Object>> conflictResponse(String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "conflict",
                "message", message != null ? message
                        : "MEMORY.md has been modified since the loaded base version; please reload and retry"));
    }

    private ResponseEntity<Map<String, Object>> tooLargeResponse(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "error", "too_large",
                "message", e.getMessage() != null ? e.getMessage() : "content too large"));
    }

    /**
     * 保存成功响应: 返回服务端实际落盘的规范化结果 (content + 由其解析的 entries + size)，
     * 前端以此为准同步卡片/整篇两种视图，不再回显请求数据或本地估算大小。
     */
    private ResponseEntity<Map<String, Object>> overwriteSuccessResponse(MemdirService.MemoryFileSnapshot snapshot) {
        Map<String, Object> body = snapshotBody(snapshot);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** GET 与 PUT 共享同一快照格式；字符数和条目均来自该快照的正文。 */
    private Map<String, Object> snapshotBody(MemdirService.MemoryFileSnapshot snapshot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", snapshot.content());
        body.put("entries", toEntryMaps(memdirService.parseEntriesOf(snapshot.content())));
        body.put("updatedAt", snapshot.updatedAt() != null ? snapshot.updatedAt().toString() : null);
        body.put("size", snapshot.content().length());
        body.put("maxSize", memdirService.getMaxMemorySize());
        return body;
    }

    private List<Map<String, Object>> toEntryMaps(List<MemdirService.MemoryEntry> entries) {
        return entries.stream()
                .map(e -> Map.<String, Object>of(
                        "source", e.source().name(),
                        "category", e.category().tag(),
                        "timestamp", e.timestamp().toString(),
                        "content", e.content()))
                .toList();
    }

    // ═══ DTO Records ═══
    public record MemoryEntry(String id, String category, String title,
                               String content, String keywords, String scope,
                               String source, String createdAt, String updatedAt) {}
    public record UpdateMemoriesRequest(List<MemoryEntry> entries) {}
    public record UpdateMemoryFileRequest(String content, String baseUpdatedAt) {}
    public record MemoryFileEntryInput(String source, String category, String timestamp, String content) {}
    public record UpdateMemoryFileEntriesRequest(List<MemoryFileEntryInput> entries, String baseUpdatedAt) {}
}
