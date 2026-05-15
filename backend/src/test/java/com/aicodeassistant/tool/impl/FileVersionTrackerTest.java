package com.aicodeassistant.tool.impl;

import com.aicodeassistant.tool.impl.FileVersionTracker.ConflictCheckResult;
import com.aicodeassistant.tool.impl.FileVersionTracker.FileVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FileVersionTracker 单元测试 — 冲突检测 + LRU 驱逐。
 */
class FileVersionTrackerTest {

    private FileVersionTracker tracker;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        tracker = new FileVersionTracker();
    }

    // ─── 冲突检测 ─────────────────────────────────────────────

    @Test
    @DisplayName("TC-FILE-001: 首次写入无冲突 — versions 为空时 checkBeforeWrite 返回无冲突")
    void tc001_firstWriteNoConflict() {
        Path file = tempDir.resolve("new-file.txt");
        ConflictCheckResult result = tracker.checkBeforeWrite(file.toString(), null);

        assertThat(result.hasConflict()).isFalse();
    }

    @Test
    @DisplayName("TC-FILE-002: 文件未变更写入无冲突 — recordWrite 后文件内容不变再 checkBeforeWrite")
    void tc002_unchangedFileNoConflict() throws IOException {
        // 创建文件并记录写入
        Path file = tempDir.resolve("stable.txt");
        String content = "hello world";
        Files.writeString(file, content, StandardCharsets.UTF_8);
        String hash = tracker.computeHash(content);
        tracker.recordWrite(file.toString(), hash, "agent-1");

        // 文件内容未变，用原 hash 检查 → 无冲突
        ConflictCheckResult result = tracker.checkBeforeWrite(file.toString(), hash);

        assertThat(result.hasConflict()).isFalse();
    }

    @Test
    @DisplayName("TC-FILE-003: 文件被外部修改 → 冲突触发")
    void tc003_externalModificationTriggersConflict() throws IOException {
        Path file = tempDir.resolve("modified.txt");
        String originalContent = "original";
        Files.writeString(file, originalContent, StandardCharsets.UTF_8);
        String hashA = tracker.computeHash(originalContent);
        tracker.recordWrite(file.toString(), hashA, "agent-1");

        // 外部修改文件
        String modifiedContent = "externally modified";
        Files.writeString(file, modifiedContent, StandardCharsets.UTF_8);

        // 用旧 hash 检查 → 冲突
        ConflictCheckResult result = tracker.checkBeforeWrite(file.toString(), hashA);

        assertThat(result.hasConflict()).isTrue();
        assertThat(result.expectedHash()).isEqualTo(hashA);
        assertThat(result.currentHash()).isEqualTo(tracker.computeHash(modifiedContent));
    }

    @Test
    @DisplayName("TC-FILE-004: expectedHash 优先于 stored hash — expectedHash 匹配当前文件则无冲突")
    void tc004_expectedHashTakesPrecedence() throws IOException {
        Path file = tempDir.resolve("precedence.txt");
        String contentA = "version A";
        Files.writeString(file, contentA, StandardCharsets.UTF_8);
        String hashA = tracker.computeHash(contentA);
        tracker.recordWrite(file.toString(), hashA, "agent-1");

        // 文件被修改为 B
        String contentB = "version B";
        Files.writeString(file, contentB, StandardCharsets.UTF_8);
        String hashB = tracker.computeHash(contentB);

        // 传入 expectedHash=hashB（匹配当前文件）→ 无冲突
        ConflictCheckResult result = tracker.checkBeforeWrite(file.toString(), hashB);

        assertThat(result.hasConflict()).isFalse();
    }

    @Test
    @DisplayName("TC-FILE-005: 文件不存在（新建）无冲突")
    void tc005_nonExistentFileNoConflict() {
        Path file = tempDir.resolve("non-existent.txt");
        // 传入一个 expectedHash 但文件不存在
        ConflictCheckResult result = tracker.checkBeforeWrite(file.toString(), "some-hash");

        assertThat(result.hasConflict()).isFalse();
    }

    @Test
    @DisplayName("TC-FILE-006: IO 异常时返回无冲突（容错）")
    void tc006_ioExceptionReturnNoConflict() {
        // 使用无效路径（包含空字节或特殊字符使得读取失败）
        // 在某些系统上目录路径尝试读取会抛 IO 异常
        // 使用一个存在的目录路径作为文件路径（读取目录内容会抛异常）
        String invalidPath = tempDir.toString();
        // 记录一个假的写入以确保 stored != null，从而触发 hash 计算路径
        tracker.recordWrite(invalidPath, "fake-hash", "agent-x");

        ConflictCheckResult result = tracker.checkBeforeWrite(invalidPath, "expected-hash");

        // 容错：IO 异常不应该导致冲突
        assertThat(result.hasConflict()).isFalse();
    }

    // ─── LRU 驱逐 ─────────────────────────────────────────────

    @Test
    @DisplayName("TC-FILE-007: 条目数 < 10K 不触发驱逐 — 9999 条记录全部保留")
    void tc007_belowThresholdNoEviction() throws Exception {
        ConcurrentHashMap<String, FileVersion> versions = getVersionsMap();

        for (int i = 0; i < 9999; i++) {
            versions.put("/file/" + i, new FileVersion("hash" + i, System.currentTimeMillis(), "agent"));
        }

        // 手动调用一次 recordWrite 来触发 evictIfNeeded（不会驱逐因为 9999+1=10000 <= 10000）
        Path file = tempDir.resolve("trigger.txt");
        Files.writeString(file, "trigger", StandardCharsets.UTF_8);
        tracker.recordWrite(file.toString(), tracker.computeHash("trigger"), "agent");

        // 10000 条 <= MAX_ENTRIES，不触发驱逐
        assertThat(versions.size()).isEqualTo(10000);
    }

    @Test
    @DisplayName("TC-FILE-008: 条目数 > 10K 触发驱逐最老 20%")
    void tc008_aboveThresholdTriggersEviction() throws Exception {
        ConcurrentHashMap<String, FileVersion> versions = getVersionsMap();

        // 插入 10001 条记录（超过 MAX_ENTRIES）
        for (int i = 0; i < 10001; i++) {
            versions.put("/file/" + i, new FileVersion("hash" + i, i, "agent"));
        }

        // 通过 recordWrite 触发 evictIfNeeded
        Path file = tempDir.resolve("trigger.txt");
        Files.writeString(file, "trigger", StandardCharsets.UTF_8);
        tracker.recordWrite(file.toString(), tracker.computeHash("trigger"), "agent");

        // 驱逐 2000 条 (10000 * 0.2)，剩余 10001 + 1 - 2000 = 8002
        assertThat(versions.size()).isLessThanOrEqualTo(10002 - 2000);
    }

    @Test
    @DisplayName("TC-FILE-009: 驱逐后 size 正确减少")
    void tc009_evictionReducesSize() throws Exception {
        ConcurrentHashMap<String, FileVersion> versions = getVersionsMap();

        // 插入 10001 条（触发阈值）
        for (int i = 0; i < 10001; i++) {
            versions.put("/file/" + i, new FileVersion("hash" + i, i, "agent"));
        }

        // recordWrite 增加 1 条 → 总 10002 > 10000 → 驱逐 2000
        Path file = tempDir.resolve("size-check.txt");
        Files.writeString(file, "content", StandardCharsets.UTF_8);
        tracker.recordWrite(file.toString(), tracker.computeHash("content"), "agent");

        // 驱逐后: 10002 - 2000 = 8002
        assertThat(versions.size()).isEqualTo(8002);
    }

    @Test
    @DisplayName("TC-FILE-010: 驱逐最老的条目（LRU）— 旧条目被驱逐，新条目保留")
    void tc010_evictsOldestEntries() throws Exception {
        ConcurrentHashMap<String, FileVersion> versions = getVersionsMap();

        // 旧条目：lastModified = 0..1999（最老的 2000 条）
        for (int i = 0; i < 2000; i++) {
            versions.put("/old/" + i, new FileVersion("oldHash" + i, i, "old-agent"));
        }
        // 新条目：lastModified = 100000+
        for (int i = 0; i < 8001; i++) {
            versions.put("/new/" + i, new FileVersion("newHash" + i, 100000 + i, "new-agent"));
        }

        // 总 10001 条，触发驱逐
        Path file = tempDir.resolve("lru-test.txt");
        Files.writeString(file, "lru", StandardCharsets.UTF_8);
        tracker.recordWrite(file.toString(), tracker.computeHash("lru"), "agent");

        // 旧条目应该被驱逐
        for (int i = 0; i < 2000; i++) {
            assertThat(versions.containsKey("/old/" + i)).isFalse();
        }
        // 新条目应该保留
        for (int i = 0; i < 8001; i++) {
            assertThat(versions.containsKey("/new/" + i)).isTrue();
        }
    }

    // ─── Helper ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, FileVersion> getVersionsMap() throws Exception {
        Field field = FileVersionTracker.class.getDeclaredField("versions");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, FileVersion>) field.get(tracker);
    }
}
