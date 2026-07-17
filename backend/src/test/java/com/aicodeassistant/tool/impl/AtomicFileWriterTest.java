package com.aicodeassistant.tool.impl;

import com.aicodeassistant.tool.impl.AtomicFileWriter.WriteResult;
import com.aicodeassistant.security.PathSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AtomicFileWriter 单元测试 — 原子写入、回滚、临时文件清理。
 */
class AtomicFileWriterTest {

    private FileVersionTracker tracker;
    private AtomicFileWriter writer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        tracker = new FileVersionTracker();
        writer = new AtomicFileWriter(tracker);
    }

    @Test
    @DisplayName("TC-FILE-011: 正常写入成功 — 文件内容正确且 hash 非空")
    void tc011_normalWriteSuccess() {
        Path target = tempDir.resolve("output.txt");
        String content = "Hello, Atomic World!";

        WriteResult result = writer.write(target, content.getBytes(StandardCharsets.UTF_8), "agent-1",
                AtomicFileWriter.ExpectedOldState.absent(), tempDir.toString());

        assertThat(result.success()).isTrue();
        assertThat(result.newHash()).isNotNull().isNotEmpty();
        assertThat(result.error()).isNull();

        // 验证文件内容
        assertThat(target).exists().hasContent(content);

        // 验证 hash 与内容匹配
        String expectedHash = tracker.computeHash(content);
        assertThat(result.newHash()).isEqualTo(expectedHash);
    }

    @Test
    @DisplayName("TC-FILE-012: 历史记录失败不伪装成文件写入失败")
    void tc012_historyFailureDoesNotInvalidateAtomicWrite() throws IOException {
        // 创建原始文件
        Path target = tempDir.resolve("rollback-test.txt");
        String originalContent = "original content that must survive";
        Files.writeString(target, originalContent, StandardCharsets.UTF_8);

        // 使用 Spy 的 FileVersionTracker，让 computeHash(String) 在写入流程中抛异常
        FileVersionTracker spyTracker = Mockito.spy(new FileVersionTracker());
        // 第一次 computeHash(String) 调用在 atomicWrite 的最后阶段，让它抛异常触发回滚
        doThrow(new RuntimeException("Simulated failure after atomic move"))
                .when(spyTracker).recordWrite(anyString(), anyString(), anyString());

        AtomicFileWriter failingWriter = new AtomicFileWriter(spyTracker);
        WriteResult result = failingWriter.write(target, "new content".getBytes(StandardCharsets.UTF_8), "agent-fail",
                AtomicFileWriter.ExpectedOldState.sha256(spyTracker.computeHash(originalContent)), tempDir.toString());

        assertThat(result.success()).isTrue();
        assertThat(result.historyRecorded()).isFalse();
        assertThat(result.error()).isNull();

        // 原子替换已经成功；审计历史失败不能回滚或破坏新文件。
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("new content");
    }

    @Test
    @DisplayName("TC-FILE-013: 临时文件写入中断时清理 — 无残留 .tmp 文件")
    void tc013_noResidualTmpFiles() throws IOException {
        // 创建原始文件
        Path target = tempDir.resolve("cleanup-test.txt");
        String originalContent = "existing content";
        Files.writeString(target, originalContent, StandardCharsets.UTF_8);

        // 让 recordWrite 抛异常模拟中断
        FileVersionTracker spyTracker = Mockito.spy(new FileVersionTracker());
        doThrow(new RuntimeException("Simulated interruption"))
                .when(spyTracker).recordWrite(anyString(), anyString(), anyString());

        AtomicFileWriter failingWriter = new AtomicFileWriter(spyTracker);
        failingWriter.write(target, "interrupted content".getBytes(StandardCharsets.UTF_8), "agent-x",
                AtomicFileWriter.ExpectedOldState.sha256(spyTracker.computeHash(originalContent)), tempDir.toString());

        // 验证目录中没有残留的 .tmp 文件
        List<Path> tmpFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempDir, "*.tmp")) {
            StreamSupport.stream(stream.spliterator(), false).forEach(tmpFiles::add);
        }
        assertThat(tmpFiles).isEmpty();
    }

    @Test
    @DisplayName("TC-FILE-014: 目标文件不存在时的新建路径 — 文件创建成功")
    void tc014_createNewFile() {
        // 目标路径在子目录中（子目录也不存在）
        Path target = tempDir.resolve("subdir").resolve("brand-new.txt");
        String content = "brand new file content";

        WriteResult result = writer.write(target, content.getBytes(StandardCharsets.UTF_8), "agent-create",
                AtomicFileWriter.ExpectedOldState.absent(), tempDir.toString());

        assertThat(result.success()).isTrue();
        assertThat(result.newHash()).isNotNull().isNotEmpty();
        assertThat(result.error()).isNull();

        // 验证文件已创建且内容正确
        assertThat(target).exists().hasContent(content);
    }

    @Test
    void managedWriteSafelyCreatesMissingParents() {
        PathSecurityService security = mock(PathSecurityService.class);
        when(security.checkWritePermission(anyString(), anyString()))
                .thenReturn(PathSecurityService.PathCheckResult.allowed());
        AtomicFileWriter managed = new AtomicFileWriter(tracker, security);
        Path target = tempDir.resolve("missing").resolve("nested").resolve("deep").resolve("file.txt");

        WriteResult result = managed.atomicWrite(target, "data", "agent", tempDir.toString(), null);

        assertThat(result.success()).isTrue();
        assertThat(target).hasContent("data");
    }

    @Test
    void managedWriteRejectsSymbolicLinkInExistingParentChain() throws IOException {
        Path outside = Files.createDirectory(tempDir.resolveSibling(tempDir.getFileName() + "-outside"));
        try {
            Path link = tempDir.resolve("linked-parent");
            Files.createSymbolicLink(link, outside);
            PathSecurityService security = mock(PathSecurityService.class);
            when(security.checkWritePermission(anyString(), anyString()))
                    .thenReturn(PathSecurityService.PathCheckResult.allowed());
            AtomicFileWriter managed = new AtomicFileWriter(tracker, security);

            WriteResult result = managed.atomicWrite(link.resolve("file.txt"), "new", "agent",
                    tempDir.toString(), null);

            assertThat(result.success()).isFalse();
            assertThat(outside.resolve("file.txt")).doesNotExist();
        } finally {
            Files.deleteIfExists(outside.resolve("file.txt"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void managedWriteRejectsSymbolicLinkTarget() throws IOException {
        Path real = Files.writeString(tempDir.resolve("real.txt"), "old");
        Path link = tempDir.resolve("link.txt");
        Files.createSymbolicLink(link, real.getFileName());
        PathSecurityService security = mock(PathSecurityService.class);
        when(security.checkWritePermission(anyString(), anyString()))
                .thenReturn(PathSecurityService.PathCheckResult.allowed());
        AtomicFileWriter managed = new AtomicFileWriter(tracker, security);

        WriteResult result = managed.atomicWrite(link, "new", "agent", tempDir.toString(), null);

        assertThat(result.success()).isFalse();
        assertThat(Files.readString(real)).isEqualTo("old");
    }
}
