package com.aicodeassistant.tool.impl;

import com.aicodeassistant.history.FileHistoryService;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.security.PathSecurityService.PathCheckResult;
import com.aicodeassistant.service.FileStateCache;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
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

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Mock PathSecurityService — 默认允许所有路径操作
        when(pathSecurityService.checkWritePermission(anyString(), anyString()))
                .thenReturn(PathCheckResult.allowed());
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
        doNothing().when(fileHistoryService).trackEdit(anyString(), anyString(), any());

        // 使用 mock 依赖构造工具实例（完整 3 参数构造函数）
        fileEditTool = new FileEditTool(fileHistoryService, pathSecurityService, sessionManager);
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
    void testToolMetadata() {
        assertEquals("Edit", fileEditTool.getName());
        assertEquals("edit", fileEditTool.getGroup());
    }
}
