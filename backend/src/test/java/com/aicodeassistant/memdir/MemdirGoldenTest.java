package com.aicodeassistant.memdir;

import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Memdir 自动记忆系统黄金测试 — 覆盖 §4.11 全部功能。
 */
class MemdirGoldenTest {

    // ==================== MemdirService ====================

    @Nested
    @DisplayName("§4.11 MemdirService")
    class MemdirServiceTests {

        @TempDir
        Path tempDir;

        private MemdirService service;

        @BeforeEach
        void setUp() {
            service = new MemdirService(tempDir);
        }

        // ----- 读取 -----

        @Test
        @DisplayName("空目录读取返回空字符串")
        void readEmptyReturnsEmpty() {
            assertEquals("", service.readMemories());
        }

        @Test
        @DisplayName("写入后可读取")
        void writeAndRead() {
            service.writeMemory("User prefers dark mode", MemdirService.MemorySource.TOOL);
            String content = service.readMemories();
            assertTrue(content.contains("User prefers dark mode"));
        }

        @Test
        @DisplayName("多次写入追加")
        void multipleWrites() {
            service.writeMemory("Memory 1", MemdirService.MemorySource.AUTO);
            service.writeMemory("Memory 2", MemdirService.MemorySource.TOOL);
            String content = service.readMemories();
            assertTrue(content.contains("Memory 1"));
            assertTrue(content.contains("Memory 2"));
        }

        // ----- 写入格式 -----

        @Test
        @DisplayName("写入包含 source 标记")
        void writeIncludesSourceMarker() {
            service.writeMemory("test content", MemdirService.MemorySource.AUTO);
            String content = service.readMemories();
            assertTrue(content.contains("<!-- source:AUTO"));
        }

        @Test
        @DisplayName("写入包含 time 标记")
        void writeIncludesTimeMarker() {
            service.writeMemory("test content", MemdirService.MemorySource.TOOL);
            String content = service.readMemories();
            assertTrue(content.contains("time:"));
        }

        @Test
        @DisplayName("TOOL 来源标记正确")
        void toolSourceMarker() {
            service.writeMemory("tool memory", MemdirService.MemorySource.TOOL);
            assertTrue(service.readMemories().contains("source:TOOL"));
        }

        @Test
        @DisplayName("USER 来源标记正确")
        void userSourceMarker() {
            service.writeMemory("user memory", MemdirService.MemorySource.USER);
            assertTrue(service.readMemories().contains("source:USER"));
        }

        // ----- 删除 -----

        @Test
        @DisplayName("删除匹配条目")
        void deleteMatching() {
            service.writeMemory("Keep this", MemdirService.MemorySource.AUTO);
            service.writeMemory("Delete this", MemdirService.MemorySource.AUTO);
            assertTrue(service.deleteMemory("Delete"));
            String content = service.readMemories();
            assertTrue(content.contains("Keep this"));
            assertFalse(content.contains("Delete this"));
        }

        @Test
        @DisplayName("删除不存在的条目返回 false")
        void deleteNonExistent() {
            service.writeMemory("Some memory", MemdirService.MemorySource.AUTO);
            assertFalse(service.deleteMemory("NonExistent_XYZ"));
        }

        @Test
        @DisplayName("空文件删除返回 false")
        void deleteFromEmpty() {
            assertFalse(service.deleteMemory("anything"));
        }

        @Test
        @DisplayName("删除大小写不敏感")
        void deleteCaseInsensitive() {
            service.writeMemory("Dark Mode Preference", MemdirService.MemorySource.AUTO);
            assertTrue(service.deleteMemory("dark mode"));
        }

        // ----- 解析 -----

        @Test
        @DisplayName("解析记忆条目")
        void parseEntries() {
            service.writeMemory("Entry 1", MemdirService.MemorySource.AUTO);
            service.writeMemory("Entry 2", MemdirService.MemorySource.TOOL);
            List<MemdirService.MemoryEntry> entries = service.parseEntries(service.readMemories());
            assertEquals(2, entries.size());
        }

        @Test
        @DisplayName("解析空内容返回空列表")
        void parseEmptyContent() {
            assertTrue(service.parseEntries("").isEmpty());
            assertTrue(service.parseEntries(null).isEmpty());
        }

        @Test
        @DisplayName("解析保留来源信息")
        void parsePreservesSource() {
            service.writeMemory("auto mem", MemdirService.MemorySource.AUTO);
            service.writeMemory("tool mem", MemdirService.MemorySource.TOOL);
            List<MemdirService.MemoryEntry> entries = service.parseEntries(service.readMemories());
            assertTrue(entries.stream().anyMatch(e -> e.source() == MemdirService.MemorySource.AUTO));
            assertTrue(entries.stream().anyMatch(e -> e.source() == MemdirService.MemorySource.TOOL));
        }

        @Test
        @DisplayName("解析保留时间戳")
        void parsePreservesTimestamp() {
            service.writeMemory("test", MemdirService.MemorySource.AUTO);
            List<MemdirService.MemoryEntry> entries = service.parseEntries(service.readMemories());
            assertFalse(entries.isEmpty());
            assertNotNull(entries.get(0).timestamp());
            assertTrue(entries.get(0).timestamp().isAfter(Instant.EPOCH));
        }

        // ----- 压缩 -----

        @Test
        @DisplayName("压缩保留 70% 条目")
        void compactKeeps70Percent() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                sb.append(String.format("\n<!-- source:AUTO time:%s -->\nEntry %d\n",
                        Instant.now().plusSeconds(i), i));
            }
            String compacted = service.compactMemories(sb.toString());
            List<MemdirService.MemoryEntry> remaining = service.parseEntries(compacted);
            assertEquals(7, remaining.size()); // 70% of 10
        }

        @Test
        @DisplayName("压缩空内容返回空")
        void compactEmpty() {
            assertEquals("", service.compactMemories(""));
        }

        // ----- 截断保护 -----

        @Test
        @DisplayName("readMemoriesForPrompt 截断超长行")
        void truncateLongLines() throws IOException {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 300; i++) {
                sb.append("Line ").append(i).append("\n");
            }
            Files.writeString(tempDir.resolve(MemdirService.ENTRYPOINT_NAME), sb.toString());
            String result = service.readMemoriesForPrompt();
            assertTrue(result.contains("truncated"));
            // 确保行数不超过限制 + 截断注释
            long lineCount = result.lines().count();
            assertTrue(lineCount <= MemdirService.MAX_ENTRYPOINT_LINES + 1);
        }

        @Test
        @DisplayName("readMemoriesForPrompt 截断超大字节")
        void truncateLargeBytes() throws IOException {
            // 创建超过 25KB 的内容
            StringBuilder sb = new StringBuilder();
            while (sb.toString().getBytes(StandardCharsets.UTF_8).length < 30_000) {
                sb.append("This is a line of memory content for testing byte truncation.\n");
            }
            Files.writeString(tempDir.resolve(MemdirService.ENTRYPOINT_NAME), sb.toString());
            String result = service.readMemoriesForPrompt();
            assertTrue(result.getBytes(StandardCharsets.UTF_8).length <=
                    MemdirService.MAX_ENTRYPOINT_BYTES + 200); // 200 余量给截断注释
        }

        @Test
        @DisplayName("readMemoriesForPrompt 正常内容不截断")
        void noTruncationForSmallContent() {
            service.writeMemory("Short memory", MemdirService.MemorySource.AUTO);
            String result = service.readMemoriesForPrompt();
            assertFalse(result.contains("truncated"));
        }

        // ----- 文件路径 -----

        @Test
        @DisplayName("记忆文件名为 MEMORY.md")
        void memoryFileName() {
            assertTrue(service.getMemoryFile().getFileName().toString().equals("MEMORY.md"));
        }

        @Test
        @DisplayName("ENTRYPOINT_NAME 全大写")
        void entrypointNameUpperCase() {
            assertEquals("MEMORY.md", MemdirService.ENTRYPOINT_NAME);
        }

        // ----- 条目计数 -----

        @Test
        @DisplayName("getEntryCount 正确计数")
        void entryCount() {
            assertEquals(0, service.getEntryCount());
            service.writeMemory("First", MemdirService.MemorySource.AUTO);
            assertEquals(1, service.getEntryCount());
            service.writeMemory("Second", MemdirService.MemorySource.TOOL);
            assertEquals(2, service.getEntryCount());
        }

        // ----- 常量 -----

        @Test
        @DisplayName("MAX_ENTRYPOINT_LINES = 200")
        void maxLines() {
            assertEquals(200, MemdirService.MAX_ENTRYPOINT_LINES);
        }

        @Test
        @DisplayName("MAX_ENTRYPOINT_BYTES = 25000")
        void maxBytes() {
            assertEquals(25_000, MemdirService.MAX_ENTRYPOINT_BYTES);
        }

        @Test
        @DisplayName("MAX_MEMORY_SIZE = 50000")
        void maxMemorySize() {
            assertEquals(50_000, MemdirService.MAX_MEMORY_SIZE);
        }

        // ----- MemorySource 枚举 -----

        @Test
        @DisplayName("MemorySource 有 3 个值")
        void memorySourceValues() {
            assertEquals(3, MemdirService.MemorySource.values().length);
            assertNotNull(MemdirService.MemorySource.AUTO);
            assertNotNull(MemdirService.MemorySource.USER);
            assertNotNull(MemdirService.MemorySource.TOOL);
        }

        // ----- MemoryEntry record -----

        @Test
        @DisplayName("MemoryEntry record 字段")
        void memoryEntryFields() {
            var entry = new MemdirService.MemoryEntry(
                    MemdirService.MemorySource.AUTO, Instant.now(), "test content");
            assertEquals(MemdirService.MemorySource.AUTO, entry.source());
            assertNotNull(entry.timestamp());
            assertEquals("test content", entry.content());
        }
    }

    // ==================== MemoryTool ====================

    @Nested
    @DisplayName("§4.11 MemoryTool")
    class MemoryToolTests {

        @TempDir
        Path tempDir;

        private MemdirService memdirService;
        private MemoryTool memoryTool;

        @BeforeEach
        void setUp() {
            memdirService = new MemdirService(tempDir);
            memoryTool = new MemoryTool(memdirService);
        }

        // ----- 基础属性 -----

        @Test
        @DisplayName("工具名称为 Memory")
        void toolName() {
            assertEquals("Memory", memoryTool.getName());
        }

        @Test
        @DisplayName("工具描述包含 persistent memories")
        void toolDescription() {
            assertTrue(memoryTool.getDescription().contains("persistent memories"));
        }

        @Test
        @DisplayName("输入 Schema 包含 action 和 content")
        void inputSchema() {
            Map<String, Object> schema = memoryTool.getInputSchema();
            assertNotNull(schema);
            assertTrue(schema.containsKey("properties"));
        }

        // ----- read 操作 -----

        @Test
        @DisplayName("read: 空记忆返回提示")
        void readEmpty() {
            ToolResult result = memoryTool.call(
                    ToolInput.from(Map.of("action", "read")), null);
            assertFalse(result.isError());
            assertTrue(result.content().contains("No memories"));
        }

        @Test
        @DisplayName("read: 有记忆时返回内容")
        void readWithContent() {
            memdirService.writeMemory("Test memory", MemdirService.MemorySource.AUTO);
            ToolResult result = memoryTool.call(
                    ToolInput.from(Map.of("action", "read")), null);
            assertFalse(result.isError());
            assertTrue(result.content().contains("Test memory"));
        }

        // ----- write 操作 -----

        @Test
        @DisplayName("write: 成功写入")
        void writeSuccess() {
            ToolResult result = memoryTool.call(
                    ToolInput.from(Map.of("action", "write", "content", "Remember this")), null);
            assertFalse(result.isError());
            assertTrue(result.content().contains("saved"));

            // 验证写入
            assertTrue(memdirService.readMemories().contains("Remember this"));
        }

        @Test
        @DisplayName("write: 使用 TOOL 来源")
        void writeUsesToolSource() {
            memoryTool.call(
                    ToolInput.from(Map.of("action", "write", "content", "tool write")), null);
            assertTrue(memdirService.readMemories().contains("source:TOOL"));
        }

        // ----- delete 操作 -----

        @Test
        @DisplayName("delete: 成功删除")
        void deleteSuccess() {
            memdirService.writeMemory("Delete me", MemdirService.MemorySource.AUTO);
            ToolResult result = memoryTool.call(
                    ToolInput.from(Map.of("action", "delete", "content", "Delete me")), null);
            assertFalse(result.isError());
            assertTrue(result.content().contains("deleted"));
        }

        @Test
        @DisplayName("delete: 未找到匹配返回错误")
        void deleteNotFound() {
            memdirService.writeMemory("Keep me", MemdirService.MemorySource.AUTO);
            ToolResult result = memoryTool.call(
                    ToolInput.from(Map.of("action", "delete", "content", "NotExist_XYZ")), null);
            assertTrue(result.isError());
            assertTrue(result.content().contains("No matching"));
        }

        // ----- 未知操作 -----

        @Test
        @DisplayName("未知 action 返回错误")
        void unknownAction() {
            ToolResult result = memoryTool.call(
                    ToolInput.from(Map.of("action", "update")), null);
            assertTrue(result.isError());
            assertTrue(result.content().contains("Unknown"));
        }

        // ----- 并发安全 -----

        @Test
        @DisplayName("read 操作并发安全")
        void readConcurrencySafe() {
            assertTrue(memoryTool.isConcurrencySafe(ToolInput.from(Map.of("action", "read"))));
        }

        @Test
        @DisplayName("write 操作非并发安全")
        void writeConcurrencyUnsafe() {
            assertFalse(memoryTool.isConcurrencySafe(ToolInput.from(Map.of("action", "write"))));
        }

        @Test
        @DisplayName("read 为只读操作")
        void readIsReadOnly() {
            assertTrue(memoryTool.isReadOnly(ToolInput.from(Map.of("action", "read"))));
        }

        @Test
        @DisplayName("write 非只读操作")
        void writeNotReadOnly() {
            assertFalse(memoryTool.isReadOnly(ToolInput.from(Map.of("action", "write"))));
        }

        // ----- 权限要求 -----

        @Test
        @DisplayName("无权限要求")
        void noPermissionRequired() {
            assertEquals(com.aicodeassistant.tool.PermissionRequirement.NONE,
                    memoryTool.getPermissionRequirement());
        }

        // ----- 端到端流程 -----

        @Test
        @DisplayName("端到端: write → read → delete → read")
        void endToEndFlow() {
            // write
            ToolResult writeResult = memoryTool.call(
                    ToolInput.from(Map.of("action", "write", "content", "E2E test memory")), null);
            assertFalse(writeResult.isError());

            // read
            ToolResult readResult = memoryTool.call(
                    ToolInput.from(Map.of("action", "read")), null);
            assertTrue(readResult.content().contains("E2E test memory"));

            // delete
            ToolResult deleteResult = memoryTool.call(
                    ToolInput.from(Map.of("action", "delete", "content", "E2E test")), null);
            assertFalse(deleteResult.isError());

            // read again
            ToolResult readAgain = memoryTool.call(
                    ToolInput.from(Map.of("action", "read")), null);
            assertFalse(readAgain.content().contains("E2E test memory"));
        }
    }
}
