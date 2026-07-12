package com.aicodeassistant.tool.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * EncodingDetector 单元测试 — 覆盖 BOM 检测、ASCII、中文 UTF-8、二进制、空文件、多字节截断等场景。
 */
class EncodingDetectorTest {

    private EncodingDetector encodingDetector;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        encodingDetector = new EncodingDetector();
    }

    @Test
    void shouldDetectUtf8Bom_whenFileHasBomHeader() throws IOException {
        // Given: 带 UTF-8 BOM 头的文件
        Path file = tempDir.resolve("bom.txt");
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] content = "Hello BOM".getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[bom.length + content.length];
        System.arraycopy(bom, 0, combined, 0, bom.length);
        System.arraycopy(content, 0, combined, bom.length, content.length);
        Files.write(file, combined);

        // When
        Charset result = encodingDetector.detectCharset(file);

        // Then
        assertThat(result).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void shouldReturnUtf8_whenFileIsPureAscii() throws IOException {
        // Given: 纯 ASCII 内容
        Path file = tempDir.resolve("ascii.txt");
        Files.writeString(file, "Hello, World!\nLine 2\nLine 3", StandardCharsets.US_ASCII);

        // When
        Charset result = encodingDetector.detectCharset(file);

        // Then: ASCII 是 UTF-8 子集，应返回 UTF-8
        assertThat(result).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void shouldReturnUtf8_whenFileContainsChinese() throws IOException {
        // Given: 包含中文的 UTF-8 文件
        Path file = tempDir.resolve("chinese.txt");
        Files.writeString(file, "你好世界\n中文内容测试\nUTF-8编码", StandardCharsets.UTF_8);

        // When
        Charset result = encodingDetector.detectCharset(file);

        // Then
        assertThat(result).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void shouldReturnNull_whenFileIsBinary() throws IOException {
        // Given: 二进制文件（超过 1% 的 null 字节）
        Path file = tempDir.resolve("binary.dat");
        byte[] binaryContent = new byte[200];
        // 填充 3 个 null 字节（3/200 = 1.5% > 1%）
        for (int i = 0; i < binaryContent.length; i++) {
            binaryContent[i] = (byte) (i % 128 + 1); // 非零 ASCII
        }
        binaryContent[0] = 0;
        binaryContent[50] = 0;
        binaryContent[100] = 0;
        Files.write(file, binaryContent);

        // When
        Charset result = encodingDetector.detectCharset(file);

        // Then: 二进制文件返回 null
        assertThat(result).isNull();
    }

    @Test
    void shouldReturnUtf8_whenFileIsEmpty() throws IOException {
        // Given: 空文件
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "");

        // When
        Charset result = encodingDetector.detectCharset(file);

        // Then: 空文件默认返回 UTF-8
        assertThat(result).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void shouldNotThrowException_whenUtf8MultibyteCharTruncatedAtBoundary() throws IOException {
        // Given: UTF-8 多字节字符在样本边界被截断
        // 构造一个接近 8192 字节边界的文件，末尾有不完整的多字节字符
        Path file = tempDir.resolve("truncated.txt");
        StringBuilder sb = new StringBuilder();
        // 填充到接近 8192 字节（使用单字节 ASCII）
        while (sb.toString().getBytes(StandardCharsets.UTF_8).length < 8189) {
            sb.append('A');
        }
        // 追加一个 3 字节的 UTF-8 字符（如 '中'），使其可能被截断
        sb.append("中");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);

        // When & Then: 不应抛出异常
        assertDoesNotThrow(() -> encodingDetector.detectCharset(file));
    }

    @Test
    void shouldDetectBinary_whenHighNullByteRatio() {
        // Given: 高比例 null 字节的样本
        byte[] sample = new byte[100];
        for (int i = 0; i < 5; i++) {
            sample[i] = 0; // 5% null 字节 > 1% 阈值
        }
        for (int i = 5; i < 100; i++) {
            sample[i] = (byte) ('A' + (i % 26));
        }

        // When
        boolean result = encodingDetector.isBinary(sample);

        // Then
        assertTrue(result);
    }

    @Test
    void shouldNotDetectBinary_whenNoNullBytes() {
        // Given: 无 null 字节的纯文本样本
        byte[] sample = "Hello, this is a normal text file content without any null bytes."
                .getBytes(StandardCharsets.UTF_8);

        // When
        boolean result = encodingDetector.isBinary(sample);

        // Then
        assertFalse(result);
    }

    @Test
    void shouldNotDetectBinary_whenEmptySample() {
        // Given: 空样本
        byte[] sample = new byte[0];

        // When
        boolean result = encodingDetector.isBinary(sample);

        // Then
        assertFalse(result);
    }
}
