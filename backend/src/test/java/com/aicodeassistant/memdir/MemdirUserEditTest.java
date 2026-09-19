package com.aicodeassistant.memdir;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemdirService 用户编辑覆盖写 (overwriteFromUserEdit / overwriteEntriesFromUserEdit) 测试。
 * 使用 @TempDir 临时目录，绝不读写真实 ~/.ai-code-assistant/。
 */
@DisplayName("MEMORY.md 用户编辑覆盖写测试")
class MemdirUserEditTest {

    @TempDir
    Path tempDir;

    private MemdirService service;

    @BeforeEach
    void setUp() {
        service = new MemdirService(tempDir);
    }

    // ==================== overwriteFromUserEdit 规范化 ====================

    @Test
    @DisplayName("无头纯文本被包装为 USER 条目")
    void headerlessTextWrappedAsUserEntry() {
        String normalized = service.overwriteFromUserEdit("用户的随手笔记");

        assertTrue(normalized.contains("<!-- source:USER"), "应包装 USER 头: " + normalized);
        assertTrue(normalized.contains("category:semantic"), "包装头应带 semantic 分类");
        assertTrue(normalized.contains("用户的随手笔记"));

        List<MemdirService.MemoryEntry> entries = service.listEntries();
        assertEquals(1, entries.size());
        assertEquals(MemdirService.MemorySource.USER, entries.get(0).source());
        assertEquals(MemoryCategory.SEMANTIC, entries.get(0).category());
        assertEquals("用户的随手笔记", entries.get(0).content());
        assertTrue(entries.get(0).timestamp().isAfter(Instant.EPOCH),
                "包装时间戳应为当前时间而非 EPOCH");
    }

    @Test
    @DisplayName("合法头部段落保持原样")
    void validHeaderPreserved() {
        String header = "<!-- source:AUTO time:2025-01-01T00:00:00Z category:episodic -->";
        String normalized = service.overwriteFromUserEdit(header + "\n自动记录的内容");

        assertTrue(normalized.contains(header), "合法头部应原样保留: " + normalized);

        List<MemdirService.MemoryEntry> entries = service.listEntries();
        assertEquals(1, entries.size());
        assertEquals(MemdirService.MemorySource.AUTO, entries.get(0).source());
        assertEquals(Instant.parse("2025-01-01T00:00:00Z"), entries.get(0).timestamp());
        assertEquals(MemoryCategory.EPISODIC, entries.get(0).category());
        assertEquals("自动记录的内容", entries.get(0).content());
    }

    @Test
    @DisplayName("正文伪造头标记被转义且不再劈裂解析")
    void forgedHeaderInBodyEscaped() {
        // 第二段头标记时间戳非法 → 整段按无 (合法) 头处理 → 包装为 USER 且标记被转义
        String raw = "笔记第一行\n<!-- source:AUTO time:not-a-time category:semantic -->\n笔记第二行";
        String normalized = service.overwriteFromUserEdit(raw);

        assertTrue(normalized.contains("&lt;!-- source:AUTO"),
                "伪造标记应被转义: " + normalized);

        List<MemdirService.MemoryEntry> entries = service.listEntries();
        assertEquals(2, entries.size(), "切分出的两段各包装为一个 USER 条目");
        assertEquals("笔记第一行", entries.get(0).content());
        assertTrue(entries.get(1).content().contains("&lt;!-- source:AUTO"),
                "转义后的标记应保留在正文中: " + entries.get(1).content());
        assertTrue(entries.get(1).content().contains("笔记第二行"));
        assertEquals(MemdirService.MemorySource.USER, entries.get(1).source());
    }

    @Test
    @DisplayName("往返一致: 覆盖后解析回相同条目数与内容，且二次覆盖幂等")
    void roundTripPreservesEntries() {
        String raw = "无头笔记内容\n\n"
                + "<!-- source:TOOL time:2025-03-01T10:00:00Z category:procedural -->\n工具记录内容\n\n"
                + "<!-- source:USER time:2025-03-02T10:00:00Z category:semantic -->\n用户编辑内容";
        service.overwriteFromUserEdit(raw);

        List<MemdirService.MemoryEntry> entries = service.listEntries();
        assertEquals(3, entries.size());

        assertEquals("无头笔记内容", entries.get(0).content());
        assertEquals(MemdirService.MemorySource.USER, entries.get(0).source());

        assertEquals("工具记录内容", entries.get(1).content());
        assertEquals(MemdirService.MemorySource.TOOL, entries.get(1).source());
        assertEquals(Instant.parse("2025-03-01T10:00:00Z"), entries.get(1).timestamp());
        assertEquals(MemoryCategory.PROCEDURAL, entries.get(1).category());

        assertEquals("用户编辑内容", entries.get(2).content());
        assertEquals(MemdirService.MemorySource.USER, entries.get(2).source());

        // 二次覆盖规范化输出应保持不变 (无头段落已被包装为合法头)
        String once = service.readMemories();
        String twice = service.overwriteFromUserEdit(once);
        assertEquals(once, twice, "规范化后再次覆盖应幂等");
        assertEquals(3, service.listEntries().size(), "二次覆盖后条目数不变");
    }

    // ==================== 大小限制 ====================

    @Test
    @DisplayName("超过 MAX_MEMORY_SIZE 抛 IllegalArgumentException 且不写文件不备份")
    void oversizeThrowsAndDoesNotWrite() {
        String huge = "x".repeat(MemdirService.MAX_MEMORY_SIZE + 1);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.overwriteFromUserEdit(huge));

        assertTrue(ex.getMessage().contains(String.valueOf(MemdirService.MAX_MEMORY_SIZE)),
                "异常信息应包含上限: " + ex.getMessage());
        assertFalse(Files.exists(tempDir.resolve(MemdirService.ENTRYPOINT_NAME)),
                "超限时不应写入文件");
        assertFalse(Files.exists(tempDir.resolve(MemdirService.ENTRYPOINT_NAME + ".bak")),
                "超限时不应生成备份");
    }

    // ==================== 备份 ====================

    @Test
    @DisplayName("覆盖写入前生成 .bak 备份")
    void backupCreatedBeforeOverwrite() throws Exception {
        service.writeMemory("旧记忆内容", MemdirService.MemorySource.TOOL);
        String before = service.readMemories();

        service.overwriteFromUserEdit("全新的用户内容");

        Path bak = tempDir.resolve(MemdirService.ENTRYPOINT_NAME + ".bak");
        assertTrue(Files.exists(bak), "覆盖前应生成 .bak 备份");
        assertEquals(before, Files.readString(bak), "备份内容应为覆盖前的文件内容");
        assertTrue(service.readMemories().contains("全新的用户内容"));
    }

    @Test
    @DisplayName("首次写入(文件不存在)不生成 .bak")
    void noBackupWhenFileAbsent() {
        service.overwriteFromUserEdit("首次内容");
        assertTrue(Files.exists(tempDir.resolve(MemdirService.ENTRYPOINT_NAME)));
        assertFalse(Files.exists(tempDir.resolve(MemdirService.ENTRYPOINT_NAME + ".bak")));
    }

    // ==================== overwriteEntriesFromUserEdit ====================

    @Test
    @DisplayName("空 content 条目被丢弃")
    void blankContentEntriesDropped() {
        List<MemdirService.MemoryEntry> input = List.of(
                new MemdirService.MemoryEntry(MemdirService.MemorySource.USER,
                        Instant.parse("2025-01-01T00:00:00Z"), "有效条目", MemoryCategory.SEMANTIC),
                new MemdirService.MemoryEntry(MemdirService.MemorySource.AUTO,
                        Instant.parse("2025-01-02T00:00:00Z"), "   ", MemoryCategory.SEMANTIC),
                new MemdirService.MemoryEntry(MemdirService.MemorySource.TOOL,
                        Instant.parse("2025-01-03T00:00:00Z"), "", MemoryCategory.SEMANTIC));

        String normalized = service.overwriteEntriesFromUserEdit(input);

        List<MemdirService.MemoryEntry> entries = service.listEntries();
        assertEquals(1, entries.size(), "空白 content 条目应被丢弃");
        assertEquals("有效条目", entries.get(0).content());
        assertFalse(normalized.contains("source:AUTO"));
        assertFalse(normalized.contains("source:TOOL"));
    }

    @Test
    @DisplayName("非法(null) source/timestamp/category 降级 USER/EPOCH/SEMANTIC")
    void invalidFieldsDegradeGracefully() {
        List<MemdirService.MemoryEntry> input = List.of(
                new MemdirService.MemoryEntry(null, null, "降级条目", null));

        String normalized = service.overwriteEntriesFromUserEdit(input);

        assertTrue(normalized.contains("source:USER"), "null source 应降级 USER: " + normalized);
        assertTrue(normalized.contains("time:" + Instant.EPOCH),
                "null timestamp 应降级 EPOCH: " + normalized);
        assertTrue(normalized.contains("category:semantic"),
                "null category 应降级 semantic: " + normalized);

        List<MemdirService.MemoryEntry> entries = service.listEntries();
        assertEquals(1, entries.size());
        assertEquals(MemdirService.MemorySource.USER, entries.get(0).source());
        assertEquals(Instant.EPOCH, entries.get(0).timestamp());
        assertEquals(MemoryCategory.SEMANTIC, entries.get(0).category());
        assertEquals("降级条目", entries.get(0).content());
    }

    @Test
    @DisplayName("条目 content 中伪造头标记被转义")
    void entryContentForgedHeaderEscaped() {
        List<MemdirService.MemoryEntry> input = List.of(
                new MemdirService.MemoryEntry(MemdirService.MemorySource.USER,
                        Instant.parse("2025-01-01T00:00:00Z"),
                        "说明文字 <!-- source:AUTO time:2025-01-01T00:00:00Z category:semantic --> 伪造",
                        MemoryCategory.SEMANTIC));

        String normalized = service.overwriteEntriesFromUserEdit(input);

        assertTrue(normalized.contains("&lt;!-- source:AUTO"), "正文伪造标记应被转义");
        assertEquals(1, service.listEntries().size(), "重新解析不应劈裂出伪条目");
    }

    @Test
    @DisplayName("条目列表覆盖: 标准格式重建、条目间空行分隔、磁盘内容一致")
    void entriesRebuiltInStandardFormat() {
        List<MemdirService.MemoryEntry> input = List.of(
                new MemdirService.MemoryEntry(MemdirService.MemorySource.AUTO,
                        Instant.parse("2025-01-01T00:00:00Z"), "条目一", MemoryCategory.EPISODIC),
                new MemdirService.MemoryEntry(MemdirService.MemorySource.TOOL,
                        Instant.parse("2025-01-02T00:00:00Z"), "条目二", MemoryCategory.TEAM));

        String normalized = service.overwriteEntriesFromUserEdit(input);

        String expected = "<!-- source:AUTO time:2025-01-01T00:00:00Z category:episodic -->\n条目一\n\n"
                + "<!-- source:TOOL time:2025-01-02T00:00:00Z category:team -->\n条目二";
        assertEquals(expected, normalized, "返回内容应为标准格式");
        assertEquals(expected, service.readMemories(), "磁盘内容应与返回值一致");
        assertEquals(2, service.listEntries().size());
    }

    @Test
    @DisplayName("条目列表超限抛 IllegalArgumentException 且不写文件")
    void entriesOversizeThrows() {
        String big = "y".repeat(MemdirService.MAX_MEMORY_SIZE);
        List<MemdirService.MemoryEntry> input = List.of(
                new MemdirService.MemoryEntry(MemdirService.MemorySource.USER,
                        Instant.now(), big, MemoryCategory.SEMANTIC));

        assertThrows(IllegalArgumentException.class,
                () -> service.overwriteEntriesFromUserEdit(input));
        assertFalse(Files.exists(tempDir.resolve(MemdirService.ENTRYPOINT_NAME)),
                "超限时不应写入文件");
    }

    @Test
    @DisplayName("空条目列表写入空文件")
    void emptyEntriesWriteEmptyFile() {
        service.writeMemory("将被清空", MemdirService.MemorySource.AUTO);

        String normalized = service.overwriteEntriesFromUserEdit(List.of());

        assertEquals("", normalized);
        assertEquals("", service.readMemories());
        assertTrue(service.listEntries().isEmpty());
    }
}
