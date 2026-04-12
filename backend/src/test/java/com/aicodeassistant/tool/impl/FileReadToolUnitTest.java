package com.aicodeassistant.tool.impl;

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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * FileReadTool 单元测试 — 覆盖读取、行号范围、大文件截断、图片、错误处理等场景。
 */
class FileReadToolUnitTest {

    private FileReadTool fileReadTool;
    private ToolUseContext context;

    @Mock private PathSecurityService pathSecurityService;
    @Mock private SessionManager sessionManager;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Mock PathSecurityService — 默认允许所有路径操作
        when(pathSecurityService.checkReadPermission(anyString(), anyString()))
                .thenReturn(PathCheckResult.allowed());
        // 设备文件路径返回 denied，对齐 PathSecurityService 真实行为
        when(pathSecurityService.checkReadPermission(org.mockito.ArgumentMatchers.eq("/dev/zero"), anyString()))
                .thenReturn(PathCheckResult.denied("Cannot read device file: /dev/zero"));
        // 注意：简化 mock，模拟真实的路径解析逻辑——绝对路径直接返回，相对路径基于 workingDirectory 解析。
        when(pathSecurityService.resolvePath(anyString(), anyString()))
                .thenAnswer(inv -> {
                    String filePath = inv.getArgument(0);
                    String workDir = inv.getArgument(1);
                    java.nio.file.Path p = Path.of(filePath);
                    return p.isAbsolute() ? p : Path.of(workDir).resolve(filePath);
                });

        // Mock SessionManager — 返回一个真实的 FileStateCache 实例
        FileStateCache fileStateCache = new FileStateCache();
        when(sessionManager.getFileStateCache(anyString())).thenReturn(fileStateCache);

        // FileReadTool 构造函数为 2 参数（PathSecurityService, SessionManager）
        fileReadTool = new FileReadTool(pathSecurityService, sessionManager);
        context = ToolUseContext.of(tempDir.toString(), "test-session");
    }

    @Test
    void testRead_entireFile() throws IOException {
        Path file = tempDir.resolve("hello.txt");
        Files.writeString(file, "line1\nline2\nline3", StandardCharsets.UTF_8);

        ToolInput input = ToolInput.from(Map.of("file_path", file.toString()));
        ToolResult result = fileReadTool.call(input, context);

        assertFalse(result.isError());
        assertEquals("line1\nline2\nline3", result.content());
        assertEquals(3, result.metadata().get("numLines"));
        assertEquals(3, result.metadata().get("totalLines"));
    }

    @Test
    void testRead_lineRange() throws IOException {
        Path file = tempDir.resolve("lines.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            if (i > 0) sb.append("\n");
            sb.append("line ").append(i);
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);

        // Read lines 10-14 (5 lines, 0-based offset)
        ToolInput input = ToolInput.from(Map.of(
                "file_path", file.toString(),
                "offset", 10,
                "limit", 5
        ));
        ToolResult result = fileReadTool.call(input, context);

        assertFalse(result.isError());
        String[] lines = result.content().split("\n");
        assertEquals(5, lines.length);
        assertEquals("line 10", lines[0]);
        assertEquals("line 14", lines[4]);
        assertEquals(10, result.metadata().get("startLine"));
    }

    @Test
    void testRead_nonExistentFile() {
        ToolInput input = ToolInput.from(Map.of("file_path", tempDir.resolve("no-such-file.txt").toString()));
        ToolResult result = fileReadTool.call(input, context);

        assertTrue(result.isError());
        assertTrue(result.content().contains("does not exist"));
    }

    @Test
    void testRead_deviceFile_blocked() {
        ToolInput input = ToolInput.from(Map.of("file_path", "/dev/zero"));
        ToolResult result = fileReadTool.call(input, context);

        assertTrue(result.isError());
        assertTrue(result.content().contains("Cannot read device file"));
    }

    @Test
    void testRead_emptyFile() throws IOException {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "", StandardCharsets.UTF_8);

        ToolInput input = ToolInput.from(Map.of("file_path", file.toString()));
        ToolResult result = fileReadTool.call(input, context);

        assertFalse(result.isError());
        assertEquals("", result.content());
    }

    @Test
    void testRead_utf8Encoding() throws IOException {
        Path file = tempDir.resolve("unicode.txt");
        String content = "中文内容\nEmoji: 🎉\nJapanese: こんにちは";
        Files.writeString(file, content, StandardCharsets.UTF_8);

        ToolInput input = ToolInput.from(Map.of("file_path", file.toString()));
        ToolResult result = fileReadTool.call(input, context);

        assertFalse(result.isError());
        assertEquals(content, result.content());
    }

    @Test
    void testRead_relativePath() throws IOException {
        Path file = tempDir.resolve("relative.txt");
        Files.writeString(file, "relative content", StandardCharsets.UTF_8);

        ToolInput input = ToolInput.from(Map.of("file_path", "relative.txt"));
        ToolResult result = fileReadTool.call(input, context);

        assertFalse(result.isError());
        assertEquals("relative content", result.content());
    }

    @Test
    void testRead_imageFile() throws IOException {
        // Create a fake PNG file (just header bytes)
        Path file = tempDir.resolve("test.png");
        byte[] pngHeader = new byte[]{(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        Files.write(file, pngHeader);

        ToolInput input = ToolInput.from(Map.of("file_path", file.toString()));
        ToolResult result = fileReadTool.call(input, context);

        assertFalse(result.isError());
        assertEquals("image", result.metadata().get("type"));
        assertEquals("image/png", result.metadata().get("mimeType"));
    }

    @Test
    void testRead_offsetBeyondFileEnd() throws IOException {
        Path file = tempDir.resolve("short.txt");
        Files.writeString(file, "line1\nline2", StandardCharsets.UTF_8);

        ToolInput input = ToolInput.from(Map.of(
                "file_path", file.toString(),
                "offset", 100,
                "limit", 10
        ));
        ToolResult result = fileReadTool.call(input, context);

        assertFalse(result.isError());
        assertEquals("", result.content());
        assertEquals(0, result.metadata().get("numLines"));
    }

    @Test
    void testToolMetadata() {
        assertEquals("Read", fileReadTool.getName());
        assertEquals("read", fileReadTool.getGroup());
        assertTrue(fileReadTool.isReadOnly(ToolInput.from(Map.of())));
    }
}
