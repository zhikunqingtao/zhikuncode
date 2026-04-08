package com.aicodeassistant.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextAttachmentResolver + ProcessedInput 单元测试。
 */
class ContextAttachmentResolverTest {

    private ContextAttachmentResolver resolver;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        resolver = new ContextAttachmentResolver();
    }

    @Test
    @DisplayName("解析整个文件引用")
    void resolveFullFile() throws IOException {
        Files.writeString(tempDir.resolve("test.txt"), "hello world");

        var result = resolver.resolve("look at @test.txt", tempDir);
        assertEquals(1, result.size());
        assertEquals("test.txt", result.getFirst().filePath());
        assertEquals("hello world", result.getFirst().content());
    }

    @Test
    @DisplayName("解析行范围引用 @file:L2-L3")
    void resolveLineRange() throws IOException {
        Files.writeString(tempDir.resolve("code.java"), "line1\nline2\nline3\nline4");

        var result = resolver.resolve("see @code.java:L2-3", tempDir);
        assertEquals(1, result.size());
        assertEquals("line2\nline3", result.getFirst().content());
        assertEquals(2, result.getFirst().startLine());
        assertEquals(3, result.getFirst().endLine());
    }

    @Test
    @DisplayName("解析单行引用 @file:L2")
    void resolveSingleLine() throws IOException {
        Files.writeString(tempDir.resolve("code.java"), "line1\nline2\nline3");

        var result = resolver.resolve("see @code.java:L2", tempDir);
        assertEquals(1, result.size());
        assertEquals("line2", result.getFirst().content());
    }

    @Test
    @DisplayName("路径遍历防护")
    void pathTraversalBlocked() throws IOException {
        Files.writeString(tempDir.resolve("safe.txt"), "safe");

        var result = resolver.resolve("@../../../etc/passwd", tempDir);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("文件不存在时跳过")
    void nonExistentFileSkipped() {
        var result = resolver.resolve("@nonexistent.txt", tempDir);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("多个文件引用")
    void multipleReferences() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "alpha");
        Files.writeString(tempDir.resolve("b.txt"), "beta");

        var result = resolver.resolve("@a.txt and @b.txt", tempDir);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("空输入返回空列表")
    void emptyInput() {
        assertTrue(resolver.resolve("", tempDir).isEmpty());
        assertTrue(resolver.resolve(null, tempDir).isEmpty());
    }

    @Test
    @DisplayName("stripFileReferences 移除引用")
    void stripReferences() {
        String result = resolver.stripFileReferences("look at @test.txt please");
        assertEquals("look at please", result);
    }

    @Test
    @DisplayName("嵌套目录中的文件引用")
    void nestedDirectoryFile() throws IOException {
        Path subDir = tempDir.resolve("src");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("main.java"), "public class Main {}");

        var result = resolver.resolve("@src/main.java", tempDir);
        assertEquals(1, result.size());
        assertEquals("public class Main {}", result.getFirst().content());
    }

    // ===== ProcessedInput 测试 =====

    @Test
    @DisplayName("ProcessedInput.command 创建命令结果")
    void processedInput_command() {
        var result = ProcessedInput.command("help", "--verbose");
        assertTrue(result.isCommand());
        assertFalse(result.isMessage());
        assertEquals("help", result.commandName());
        assertEquals("--verbose", result.commandArgs());
    }

    @Test
    @DisplayName("ProcessedInput.message 创建消息结果")
    void processedInput_message() {
        var result = ProcessedInput.message("hello world");
        assertFalse(result.isCommand());
        assertTrue(result.isMessage());
        assertNotNull(result.message());
        assertTrue(result.attachments().isEmpty());
    }

    @Test
    @DisplayName("ProcessedInput.message 带附件")
    void processedInput_messageWithAttachments() {
        var attachment = ProcessedInput.ContextAttachment.ofFile("test.txt", "content");
        var result = ProcessedInput.message("check this", List.of(attachment));
        assertEquals(1, result.attachments().size());
        assertEquals("test.txt", result.attachments().getFirst().filePath());
    }
}
