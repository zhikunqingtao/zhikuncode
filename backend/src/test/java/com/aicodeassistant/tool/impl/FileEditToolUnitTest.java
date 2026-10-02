package com.aicodeassistant.tool.impl;

import com.aicodeassistant.history.FileHistoryService;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.security.PathSecurityService.PathCheckResult;
import com.aicodeassistant.service.FileStateCache;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.engine.KeyFileTracker;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FileEditTool 单元测试 — 覆盖搜索替换、新建文件、多匹配、错误处理等场景。
 */
class FileEditToolUnitTest {

    private FileEditTool fileEditTool;
    private ToolUseContext context;
    private FileStateCache fileStateCache;

    @Mock private PathSecurityService pathSecurityService;
    @Mock private FileHistoryService fileHistoryService;
    @Mock private SessionManager sessionManager;
    @Mock private KeyFileTracker keyFileTracker;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Mock PathSecurityService — 默认允许所有路径操作
        when(pathSecurityService.inspectAuthorizedExecutionWritePermission(anyString(), anyString()))
                .thenAnswer(inv -> {
                    String filePath = inv.getArgument(0);
                    String workDir = inv.getArgument(1);
                    Path path = Path.of(filePath);
                    Path target = path.isAbsolute()
                            ? path : Path.of(workDir).resolve(path);
                    return new PathSecurityService.AuthorizedPathCheck(
                            target.toAbsolutePath().normalize(),
                            PathCheckResult.allowed());
                });
        when(pathSecurityService.checkReadPermission(anyString(), anyString()))
                .thenReturn(PathCheckResult.allowed());
        // 注意：简化 mock，模拟真实的路径解析逻辑——绝对路径直接返回，相对路径基于 workingDirectory 解析。
        when(pathSecurityService.resolvePath(anyString(), anyString()))
                .thenAnswer(inv -> {
                    String filePath = inv.getArgument(0);
                    String workDir = inv.getArgument(1);
                    java.nio.file.Path p = Path.of(filePath);
                    return p.isAbsolute() ? p : Path.of(workDir).resolve(filePath);
                });

        // Mock SessionManager — 返回一个共享的真实 FileStateCache 实例
        // 测试中需要在编辑前先 markRead 文件，以满足 Read-before-Edit 校验
        fileStateCache = new FileStateCache() {
            @Override
            public synchronized boolean hasBeenRead(String path) {
                // 测试环境中跳过 Read-before-Edit 校验
                return true;
            }
            @Override
            public synchronized boolean isStale(String path) {
                return false;
            }
        };
        when(sessionManager.getFileStateCache(anyString())).thenReturn(fileStateCache);

        // Mock FileHistoryService — trackEdit 默认无操作（仅 FileEditTool 需要）
        doNothing().when(fileHistoryService).trackEdit(anyString(), anyString(), any(), anyString());

        // 使用 mock 依赖构造工具实例（完整 6 参数构造函数）
        FileVersionTracker fileVersionTracker = new FileVersionTracker();
        AtomicFileWriter atomicFileWriter = new AtomicFileWriter(fileVersionTracker);
        fileEditTool = new FileEditTool(fileHistoryService, pathSecurityService, sessionManager, keyFileTracker, fileVersionTracker, atomicFileWriter);
        context = ToolUseContext.of(tempDir.toString(), "test-session");
    }

    @Test
    void testEdit_searchReplace_single() throws IOException {
        Path file = tempDir.resolve("test.java");
        Files.writeString(file, "public class Foo {\n    int x = 1;\n}\n", StandardCharsets.UTF_8);

        ToolInput input = ToolInput.from(Map.of(
                "file_path", file.toString(),
                "old_string", "int x = 1;",
                "new_string", "int x = 42;"
        ));
        ToolResult result = fileEditTool.call(input, context);

        assertFalse(result.isError());
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(content.contains("int x = 42;"));
        assertFalse(content.contains("int x = 1;"));
    }

    @Test
    void testEdit_searchReplace_multiple() throws IOException {
        Path file = tempDir.resolve("multi.txt");
        Files.writeString(file, "foo bar foo baz foo", StandardCharsets.UTF_8);

        ToolInput input = ToolInput.from(Map.of(
                "file_path", file.toString(),
                "old_string", "foo",
                "new_string", "qux",
                "replace_all", true
        ));
        ToolResult result = fileEditTool.call(input, context);

        assertFalse(result.isError());
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals("qux bar qux baz qux", content);
    }

    @Test
    void testEdit_searchReplace_notFound() throws IOException {
        Path file = tempDir.resolve("nf.txt");
        Files.writeString(file, "hello world", StandardCharsets.UTF_8);

        ToolInput input = ToolInput.from(Map.of(
                "file_path", file.toString(),
                "old_string", "xyz_not_found",
                "new_string", "replacement"
        ));
        ToolResult result = fileEditTool.call(input, context);

        assertTrue(result.isError());
        assertTrue(result.content().contains("No match found"));
    }

    @Test
    void testEdit_createNewFile() {
        Path file = tempDir.resolve("newdir/newfile.txt");

        ToolInput input = ToolInput.from(Map.of(
                "file_path", file.toString(),
                "old_string", "",
                "new_string", "new file content"
        ));
        ToolResult result = fileEditTool.call(input, context);

        assertFalse(result.isError());
        assertTrue(Files.exists(file));
        try {
            assertEquals("new file content", Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            fail("Failed to read created file: " + e.getMessage());
        }
    }

    @Test
    void testEdit_identicalStrings() throws IOException {
        Path file = tempDir.resolve("ident.txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);

        ToolInput input = ToolInput.from(Map.of(
                "file_path", file.toString(),
                "old_string", "hello",
                "new_string", "hello"
        ));
        ToolResult result = fileEditTool.call(input, context);

        assertTrue(result.isError());
        assertTrue(result.content().contains("identical"));
    }

    @Test
    void testEdit_multipleMatches_noReplaceAll() throws IOException {
        Path file = tempDir.resolve("dup.txt");
        Files.writeString(file, "abc abc abc", StandardCharsets.UTF_8);

        ToolInput input = ToolInput.from(Map.of(
                "file_path", file.toString(),
                "old_string", "abc",
                "new_string", "xyz"
        ));
        ToolResult result = fileEditTool.call(input, context);

        assertTrue(result.isError());
        assertTrue(result.content().contains("Found 3 matches"));
    }

    @Test
    void testEdit_nonExistentFile_withOldString() {
        Path file = tempDir.resolve("nofile.txt");

        ToolInput input = ToolInput.from(Map.of(
                "file_path", file.toString(),
                "old_string", "something",
                "new_string", "other"
        ));
        ToolResult result = fileEditTool.call(input, context);

        assertTrue(result.isError());
        assertTrue(result.content().contains("does not exist"));
    }

    @Test
    void testEdit_preserveLineEndings() throws IOException {
        Path file = tempDir.resolve("endings.txt");
        String content = "line1\nline2\nline3\n";
        Files.writeString(file, content, StandardCharsets.UTF_8);

        ToolInput input = ToolInput.from(Map.of(
                "file_path", file.toString(),
                "old_string", "line2",
                "new_string", "LINE_TWO"
        ));
        ToolResult result = fileEditTool.call(input, context);

        assertFalse(result.isError());
        String newContent = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals("line1\nLINE_TWO\nline3\n", newContent);
    }

    @Test
    void testEdit_smartQuoteNormalization() throws IOException {
        // File uses ASCII quotes
        Path file = tempDir.resolve("quotes.txt");
        Files.writeString(file, "String s = \"hello\";", StandardCharsets.UTF_8);

        // Search with smart quotes (should be normalized)
        ToolInput input = ToolInput.from(Map.of(
                "file_path", file.toString(),
                "old_string", "String s = \u201Chello\u201D;",
                "new_string", "String s = \"world\";"
        ));
        ToolResult result = fileEditTool.call(input, context);

        assertFalse(result.isError());
        String newContent = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(newContent.contains("\"world\""));
    }

    @Test
    void testEdit_generatesDiff() throws IOException {
        Path file = tempDir.resolve("diff.txt");
        Files.writeString(file, "aaa\nbbb\nccc\n", StandardCharsets.UTF_8);

        ToolInput input = ToolInput.from(Map.of(
                "file_path", file.toString(),
                "old_string", "bbb",
                "new_string", "BBB"
        ));
        ToolResult result = fileEditTool.call(input, context);

        assertFalse(result.isError());
        assertNotNull(result.metadata().get("diff"));
        String diff = result.metadata().get("diff").toString();
        assertTrue(diff.contains("-bbb") || diff.contains("+BBB"));
    }

    @Test
    void actualDiffUsesMatchedFileTextAndAllReplacements() throws IOException {
        Path file = tempDir.resolve("actual.txt");
        Files.writeString(file, "say \"hello\"\nsay \"hello\"\n");
        ToolResult result = fileEditTool.call(ToolInput.from(Map.of(
                "file_path", file.toString(), "old_string", "say “hello”",
                "new_string", "say \"world\"", "replace_all", true)), context);
        assertFalse(result.isError());
        assertEquals("say \"world\"\nsay \"world\"\n", Files.readString(file));
        Map<?, ?> display = (Map<?, ?>) result.metadata().get("structuredResult");
        assertEquals("edit-diff/v1", display.get("schema"));
        assertEquals(file.toString(), display.get("filePath"));
        assertEquals(result.metadata().get("diff"), display.get("diff"));
        assertEquals(false, display.get("truncated"));
        String diff = (String) display.get("diff");
        assertEquals(2, diff.lines().filter(line -> line.equals("-say \"hello\"")).count());
        assertEquals(2, diff.lines().filter(line -> line.equals("+say \"world\"")).count());
        assertFalse(diff.contains("“hello”"));
        assertEquals("Edited: " + file, result.content());
        assertEquals(ToolResult.EffectState.APPLIED, result.effectState());
    }

    @Test
    void displayBoundsDoNotTruncateTheFileOrOriginalDiff() throws IOException {
        Path file = tempDir.resolve("large.txt");
        String original = "before\n".repeat(600);
        String replacement = "after 中文\n".repeat(600);
        Files.writeString(file, original);
        ToolResult result = fileEditTool.call(ToolInput.from(Map.of(
                "file_path", file.toString(), "old_string", original, "new_string", replacement)), context);
        assertFalse(result.isError());
        assertEquals(replacement, Files.readString(file));
        Map<?, ?> display = (Map<?, ?>) result.metadata().get("structuredResult");
        String preview = (String) display.get("diff");
        assertEquals(true, display.get("truncated"));
        assertEquals(500, preview.split("\n", -1).length);
        assertTrue(((String) result.metadata().get("diff")).startsWith(preview));
        assertTrue(((String) result.metadata().get("diff")).contains("+after 中文"));
    }

    @Test
    void hugeSingleLinePreviewEndsBeforeTheIncompleteLine() throws IOException {
        Path file = tempDir.resolve("long-line.txt");
        String original = "旧".repeat(70_000) + "\n";
        Files.writeString(file, original);
        ToolResult result = fileEditTool.call(ToolInput.from(Map.of(
                "file_path", file.toString(), "old_string", original, "new_string", "new\n")), context);
        assertFalse(result.isError());
        assertEquals("new\n", Files.readString(file));
        Map<?, ?> display = (Map<?, ?>) result.metadata().get("structuredResult");
        assertEquals(true, display.get("truncated"));
        String preview = (String) display.get("diff");
        assertTrue(preview.length() <= 64 * 1024);
        assertTrue(preview.startsWith("--- " + file));
        assertTrue(preview.contains("@@"));
        assertFalse(preview.contains("旧"));
    }

    @Test
    void displayRetainsCompleteChangedLineEndingExactlyAtCharacterLimit() throws IOException {
        Path file = tempDir.resolve("exact-boundary.txt");
        String header = "--- " + file + "\n+++ " + file + "\n@@ -1,1 +1,1 @@\n";
        String original = "x".repeat(64 * 1024 - header.length() - 1);
        Files.writeString(file, original);
        ToolResult result = fileEditTool.call(ToolInput.from(Map.of(
                "file_path", file.toString(), "old_string", original, "new_string", "new")), context);
        assertFalse(result.isError());
        assertEquals("new", Files.readString(file));
        String full = (String) result.metadata().get("diff");
        assertTrue(full.startsWith(header));
        assertEquals('\n', full.charAt(64 * 1024));
        Map<?, ?> display = (Map<?, ?>) result.metadata().get("structuredResult");
        assertEquals(full.substring(0, 64 * 1024), display.get("diff"));
        assertEquals(true, display.get("truncated"));
    }

    @Test
    void displayRetainsExactlyFiveHundredLinesWithoutTruncation() throws IOException {
        Path file = tempDir.resolve("exact-lines.txt");
        String original = String.join("\n", java.util.Collections.nCopies(248, "before"));
        String replacement = String.join("\n", java.util.Collections.nCopies(249, "after"));
        Files.writeString(file, original);
        ToolResult result = fileEditTool.call(ToolInput.from(Map.of(
                "file_path", file.toString(), "old_string", original, "new_string", replacement)), context);
        assertFalse(result.isError());
        assertEquals(replacement, Files.readString(file));
        String full = (String) result.metadata().get("diff");
        assertEquals(500, full.split("\n", -1).length);
        Map<?, ?> display = (Map<?, ?>) result.metadata().get("structuredResult");
        assertEquals(full, display.get("diff"));
        assertEquals(false, display.get("truncated"));
    }

    @Test
    void bookkeepingFailureKeepsAppliedResultWithoutInventingDiff() throws IOException {
        Path file = tempDir.toRealPath().resolve("history-failure.txt");
        Files.writeString(file, "before");
        when(fileHistoryService.trackAppliedEdit(anyString(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("history unavailable"));
        ToolResult result = fileEditTool.call(ToolInput.from(Map.of(
                "file_path", file.toString(), "old_string", "before", "new_string", "after")), context);
        assertFalse(result.isError());
        assertEquals(ToolResult.EffectState.APPLIED, result.effectState());
        assertEquals("after", Files.readString(file));
        assertEquals("POST_COMMIT_BOOKKEEPING_FAILED", result.metadata().get("postCommitErrorCode"));
        assertEquals("", result.metadata().get("diff"));
        assertFalse(result.metadata().containsKey("structuredResult"));
    }

    @Nested
    class EmptyMatchGuardTests {
        private Path root;
        private FileStateCache readCache;
        private AtomicFileWriter writer;
        private FileEditTool tool;
        private ToolUseContext toolContext;

        @BeforeEach
        void useRealReadState() throws IOException {
            root = tempDir.toRealPath();
            readCache = spy(new FileStateCache());
            when(sessionManager.getFileStateCache(anyString())).thenReturn(readCache);
            FileVersionTracker versions = new FileVersionTracker();
            writer = spy(new AtomicFileWriter(versions));
            tool = new FileEditTool(fileHistoryService, pathSecurityService, sessionManager,
                    keyFileTracker, versions, writer);
            toolContext = ToolUseContext.of(root.toString(), "empty-match-session");
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        void rejectsEmptyOldStringForExistingFiles(boolean replaceAll) throws IOException {
            Path empty = Files.writeString(root.resolve("empty.txt"), "");
            Path nonempty = Files.writeString(root.resolve("nonempty.txt"), "abc");
            assertRejectedWithoutWrites(empty, "", "X", replaceAll, "FILE_EDIT_EMPTY_MATCH");
            assertRejectedWithoutWrites(nonempty, "", "X", replaceAll, "FILE_EDIT_EMPTY_MATCH");
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        void rejectsEmptyMatchProducedByNormalization(boolean replaceAll) throws IOException {
            Path file = writeAndRead("normalized-empty.txt", "abc\n");
            assertRejectedWithoutWrites(file, " ", "X", replaceAll, "FILE_EDIT_EMPTY_MATCH");
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        void missingFileStillAllowsCreation(boolean replaceAll) throws IOException {
            Path file = root.resolve("newdir/new.txt");
            ToolResult result = edit(file, "", "created", replaceAll);

            assertFalse(result.isError());
            assertEquals(ToolResult.EffectState.APPLIED, result.effectState());
            assertEquals("created", Files.readString(file));
        }

        @Test
        void doubleEmptyStringsKeepNoChangePriority() throws IOException {
            Path empty = Files.writeString(root.resolve("empty.txt"), "");
            Path nonempty = Files.writeString(root.resolve("nonempty.txt"), "abc");
            assertRejectedWithoutWrites(root.resolve("missing.txt"), "", "", false, "FILE_EDIT_NO_CHANGE");
            assertRejectedWithoutWrites(empty, "", "", false, "FILE_EDIT_NO_CHANGE");
            assertRejectedWithoutWrites(nonempty, "", "", false, "FILE_EDIT_NO_CHANGE");
        }

        @Test
        void unreadNonemptyInputStillRequiresRead() throws IOException {
            Path file = Files.writeString(root.resolve("unread.txt"), "abc\n");
            assertRejectedWithoutWrites(file, " ", "X", false, "FILE_READ_REQUIRED");
        }

        @Test
        void staleReadStillRequiresReread() throws IOException {
            Path file = writeAndRead("stale.txt", "abc\n");
            long readAt = readCache.toMap().get(file.toString()).timestamp();
            Files.setLastModifiedTime(file, FileTime.fromMillis(readAt + 2000));
            assertRejectedWithoutWrites(file, " ", "X", false, "FILE_READ_STATE_STALE");
        }

        @ParameterizedTest
        @ValueSource(strings = {" ", "\t", "\n"})
        void realWhitespaceMatchesRemainEditable(String whitespace) throws IOException {
            Path file = writeAndRead("whitespace.txt", "head" + whitespace + "tail");
            ToolResult result = edit(file, whitespace, "X", false);

            assertFalse(result.isError());
            assertEquals(ToolResult.EffectState.APPLIED, result.effectState());
            assertEquals("headXtail", Files.readString(file));
        }

        @Test
        void normalizedSearchMayBeEmptyWhenActualMatchIsNot() throws IOException {
            Path file = writeAndRead("normalized-space.txt", "head\n \ntail");
            ToolResult result = edit(file, "  ", "X", false);

            assertFalse(result.isError());
            assertEquals(ToolResult.EffectState.APPLIED, result.effectState());
            assertEquals("head\nX\ntail", Files.readString(file));
        }

        private Path writeAndRead(String name, String content) throws IOException {
            Path file = Files.writeString(root.resolve(name), content, StandardCharsets.UTF_8);
            readCache.markRead(file.toString(), content, null, null, false);
            return file;
        }

        private ToolResult edit(Path file, String oldString, String newString, boolean replaceAll) {
            return tool.call(ToolInput.from(Map.of(
                    "file_path", file.toString(), "old_string", oldString,
                    "new_string", newString, "replace_all", replaceAll)), toolContext);
        }

        private void assertRejectedWithoutWrites(Path file, String oldString, String newString,
                                                  boolean replaceAll, String failureCode) throws IOException {
            byte[] before = Files.exists(file) ? Files.readAllBytes(file) : null;
            Map<String, FileStateCache.FileState> cacheBefore = readCache.toMap();

            ToolResult result = edit(file, oldString, newString, replaceAll);

            assertEquals(ToolResult.ExecutionStatus.FAILED, result.executionStatus());
            assertEquals(ToolResult.ToolFailureType.VALIDATION, result.failureType());
            assertEquals(failureCode, result.failureCode());
            assertEquals(ToolResult.EffectState.NOT_STARTED, result.effectState());
            assertEquals(ToolResult.Retryability.NEVER, result.retryability());
            if (before == null) {
                assertFalse(Files.exists(file));
            } else {
                assertArrayEquals(before, Files.readAllBytes(file));
            }
            assertEquals(cacheBefore, readCache.toMap());
            verify(writer, never()).writeAuthorized(any(), any(), any(), any(), any());
            verify(fileHistoryService, never()).trackAppliedEdit(any(), any(), any(), any(), any());
            verify(readCache, never()).markModified(anyString());
        }
    }

    @Test
    void testToolMetadata() {
        assertEquals("Edit", fileEditTool.getName());
        assertEquals("edit", fileEditTool.getGroup());
    }
}
