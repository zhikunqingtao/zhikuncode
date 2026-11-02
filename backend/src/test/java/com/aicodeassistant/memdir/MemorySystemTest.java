package com.aicodeassistant.memdir;

import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-MEM-001/003/004/005 记忆系统测试。
 */
@DisplayName("记忆系统测试")
class MemorySystemTest {

    /**
     * TC-MEM-001 个人记忆 CRUD 验证。
     */
    @Nested
    @DisplayName("TC-MEM-001 个人记忆 CRUD 验证")
    class MemoryCrudTest {

        @TempDir
        Path tempDir;
        private MemdirService service;

        @BeforeEach
        void setUp() {
            service = new MemdirService(tempDir);
        }

        @Test
        @DisplayName("写入记忆后读取验证存在")
        void writeAndReadMemory() {
            service.writeMemory("用户偏好Java 21", MemdirService.MemorySource.TOOL);
            String content = service.readMemories();
            assertTrue(content.contains("用户偏好Java 21"), "写入的记忆应可读取");
        }

        @Test
        @DisplayName("更新记忆后读取验证已更新")
        void updateMemory() {
            service.writeMemory("用户偏好Java 17", MemdirService.MemorySource.TOOL);
            assertTrue(service.deleteMemory("用户偏好Java 17"));
            service.writeMemory("用户偏好Java 21", MemdirService.MemorySource.TOOL);
            String content = service.readMemories();
            assertFalse(content.contains("用户偏好Java 17"), "旧记忆应被删除");
            assertTrue(content.contains("用户偏好Java 21"), "新记忆应存在");
        }

        @Test
        @DisplayName("删除记忆后读取验证已删除")
        void deleteMemory() {
            service.writeMemory("待删除记忆", MemdirService.MemorySource.TOOL);
            assertTrue(service.deleteMemory("待删除记忆"));
            String content = service.readMemories();
            assertFalse(content.contains("待删除记忆"), "已删除记忆不应存在");
        }

        @Test
        @DisplayName("搜索记忆返回相关结果")
        void searchMemories() {
            service.writeMemory("## Java编码规范\n使用Java 21新特性", MemdirService.MemorySource.TOOL);
            service.writeMemory("## Python项目\n使用Python 3.12", MemdirService.MemorySource.TOOL);
            List<MemdirService.Memory> results = service.searchMemories("Java编码", 3);
            assertFalse(results.isEmpty(), "搜索应返回结果");
            assertTrue(results.get(0).content().contains("Java"), "首个结果应包含Java");
        }

        @Test
        @DisplayName("超限验证：写入超过200行记忆后截断保护")
        void truncationProtection() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 210; i++) {
                sb.append("记忆行 ").append(i).append("\n");
            }
            service.writeMemory(sb.toString(), MemdirService.MemorySource.TOOL);
            String prompt = service.readMemoriesForPrompt();
            long lineCount = prompt.lines().count();
            assertTrue(lineCount <= MemdirService.MAX_ENTRYPOINT_LINES + 2,
                    "截断后行数应不超过限制: " + lineCount);
        }
    }

    /**
     * TC-MEM-003 LLM 重排与 BM25 降级验证。
     */
    @Nested
    @DisplayName("TC-MEM-003 LLM 重排与 BM25 降级验证")
    class LlmRerankFallbackTest {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("LLM 不可用时自动降级到纯 BM25")
        void fallbackToBm25WhenLlmUnavailable() {
            MemorySearchEngine engine = new MemorySearchEngine();
            MemdirService service = new MemdirService(tempDir, engine);

            service.writeMemory("## Java规范\n使用Java 21", MemdirService.MemorySource.TOOL);
            service.writeMemory("## Python规范\n使用Python 3.12", MemdirService.MemorySource.TOOL);
            service.writeMemory("## 安全策略\n输入校验必须", MemdirService.MemorySource.TOOL);

            List<MemdirService.Memory> results = service.searchMemories("Java", 3);
            assertNotNull(results, "降级后结果不应为null");
            assertFalse(results.isEmpty(), "降级后结果不应为空");
        }

        @Test
        @DisplayName("降级后结果仍然可用且包含相关内容")
        void fallbackResultsAreUsable() {
            MemdirService service = new MemdirService(tempDir);
            service.writeMemory("## 数据库配置\nMySQL连接池大小50", MemdirService.MemorySource.TOOL);
            service.writeMemory("## 前端框架\nReact 18 + TypeScript", MemdirService.MemorySource.TOOL);

            List<MemdirService.Memory> results = service.searchMemories("数据库", 2);
            assertFalse(results.isEmpty(), "结果不应为空");
            assertTrue(results.stream().anyMatch(m -> m.content().contains("数据库")),
                    "结果应包含相关内容");
        }
    }

    /**
     * TC-MEM-004 容量保护与显式清理验证。
     */
    @Nested
    @DisplayName("TC-MEM-004 容量保护与显式清理验证")
    class CompactionAndExpiryTest {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("写入后超过50000字符时拒绝写入，原文件不变")
        void rejectWriteWhenOverLimit() {
            MemdirService service = new MemdirService(tempDir);

            service.writeMemory("A".repeat(49_900), MemdirService.MemorySource.TOOL);
            String before = service.readMemories();

            assertThrows(MemdirService.MemoryFullException.class,
                    () -> service.writeMemory("B".repeat(49_900), MemdirService.MemorySource.TOOL));

            assertEquals(before, service.readMemories(), "超限写入不得修改原文件");
            assertTrue(service.readMemories().length() <= MemdirService.MAX_MEMORY_SIZE);
        }

        @Test
        @DisplayName("写满后保留原文件，经 Memory delete 删除指定条目后可重新写入")
        void fullMemoryRecoversAfterDeletingSelectedEntry() throws Exception {
            MemdirService service = new MemdirService(tempDir);
            MemoryTool tool = new MemoryTool(service);
            ToolUseContext context = ToolUseContext.of(tempDir.toString(), "memory-capacity-recovery");
            service.writeMemory("KEEP_RECORD", MemdirService.MemorySource.USER);
            service.writeMemory("REMOVE_RECORD\n" + "A".repeat(49_600), MemdirService.MemorySource.TOOL);
            byte[] before = Files.readAllBytes(service.getMemoryFile());
            String nextEntry = "NEW_RECORD\n" + "B".repeat(500);

            ToolResult rejected = tool.call(ToolInput.from(Map.of(
                    "action", "write", "content", nextEntry)), context);

            assertTrue(rejected.isError());
            assertEquals("MEMORY_FULL", rejected.failureCode());
            assertArrayEquals(before, Files.readAllBytes(service.getMemoryFile()),
                    "拒绝追加必须逐字节保留原文件");

            ToolResult deleted = tool.call(ToolInput.from(Map.of(
                    "action", "delete", "content", "REMOVE_RECORD")), context);
            assertFalse(deleted.isError(), deleted.content());
            assertEquals(List.of("KEEP_RECORD"), service.listEntries().stream()
                    .map(MemdirService.MemoryEntry::content).toList(), "只删除匹配条目");

            ToolResult saved = tool.call(ToolInput.from(Map.of(
                    "action", "write", "content", nextEntry)), context);
            assertFalse(saved.isError(), saved.content());
            assertEquals(List.of("KEEP_RECORD", nextEntry), service.listEntries().stream()
                    .map(MemdirService.MemoryEntry::content).toList());
            assertTrue(service.readMemories().length() <= MemdirService.MAX_MEMORY_SIZE);
        }

        @Test
        @DisplayName("内部压缩辅助函数减少字符数（不表示自动压缩已启用）")
        void compactReducesSize() {
            MemdirService service = new MemdirService(tempDir);

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                sb.append(String.format(
                    "\n<!-- source:AUTO time:%s category:semantic -->\n记忆条目 %d 的详细内容描述\n",
                    Instant.now().plusSeconds(i), i));
            }
            String original = sb.toString();
            String compacted = service.compactMemories(original);

            assertTrue(compacted.length() <= original.length(),
                    "压缩后长度应 <= 原始: " + compacted.length() + " vs " + original.length());
        }

        @Test
        @DisplayName("过期记忆被清理")
        void purgeExpiredMemories() throws Exception {
            MemdirService service = new MemdirService(tempDir);

            Instant expired = Instant.now().minus(Duration.ofDays(91));
            String expiredEntry = String.format(
                    "<!-- source:AUTO time:%s category:semantic -->\n过期记忆内容", expired);
            Files.writeString(tempDir.resolve(MemdirService.ENTRYPOINT_NAME), expiredEntry);

            int purged = service.purgeExpiredMemories();
            assertTrue(purged >= 1, "应至少清理1条过期记忆: purged=" + purged);

            String remaining = service.readMemories();
            assertFalse(remaining.contains("过期记忆内容"), "过期记忆应被清除");
        }

        @Test
        @DisplayName("未过期记忆保留")
        void retainNonExpiredMemories() throws Exception {
            MemdirService service = new MemdirService(tempDir);

            Instant recent = Instant.now().minus(Duration.ofDays(10));
            Instant expired = Instant.now().minus(Duration.ofDays(91));
            String content = String.format(
                    "<!-- source:AUTO time:%s category:semantic -->\n新鲜记忆\n\n"
                  + "<!-- source:AUTO time:%s category:semantic -->\n过期记忆",
                    recent, expired);
            Files.writeString(tempDir.resolve(MemdirService.ENTRYPOINT_NAME), content);

            service.purgeExpiredMemories();
            String remaining = service.readMemories();
            assertTrue(remaining.contains("新鲜记忆"), "未过期记忆应保留");
        }
    }

    /**
     * TC-MEM-005 团队记忆类别支持。
     */
    @Nested
    @DisplayName("TC-MEM-005 团队记忆类别支持")
    class MemoryCategoryTeamTest {

        @Test
        @DisplayName("TEAM 枚举值存在")
        void testTeamCategoryExists() {
            MemoryCategory team = MemoryCategory.TEAM;
            assertNotNull(team);
            assertEquals("team", team.tag());
        }

        @Test
        @DisplayName("fromTag 能正确识别 team 标签")
        void testFromTagParsesTeam() {
            MemoryCategory result = MemoryCategory.fromTag("team");
            assertEquals(MemoryCategory.TEAM, result);
        }

        @Test
        @DisplayName("fromTag 大小写不敏感")
        void testFromTagCaseInsensitive() {
            assertEquals(MemoryCategory.TEAM, MemoryCategory.fromTag("TEAM"));
            assertEquals(MemoryCategory.TEAM, MemoryCategory.fromTag("Team"));
        }

        @Test
        @DisplayName("values() 包含 TEAM")
        void testAllCategoriesIncludeTeam() {
            List<String> allTags = Arrays.stream(MemoryCategory.values())
                .map(MemoryCategory::tag)
                .collect(Collectors.toList());
            assertTrue(allTags.contains("team"), "MemoryCategory.values() 应包含 team");
        }
    }
}
