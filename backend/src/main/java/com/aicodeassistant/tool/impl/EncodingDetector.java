package com.aicodeassistant.tool.impl;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 文件编码检测器 — 纯Java实现，无第三方依赖。
 * <p>
 * 检测策略：BOM → 二进制检测 → UTF-8验证 → ISO-8859-1 fallback。
 * 返回null表示文件为二进制格式。
 */
@Component
public class EncodingDetector {

    private static final int DETECT_BUFFER_SIZE = 8192;

    /**
     * 检测文件编码。返回null表示二进制文件。
     */
    public Charset detectCharset(Path filePath) throws IOException {
        Objects.requireNonNull(filePath, "filePath cannot be null");
        byte[] sample = readSample(filePath, DETECT_BUFFER_SIZE);
        if (sample.length == 0) return StandardCharsets.UTF_8;

        // 1. BOM检测（最优先）
        Charset bomCharset = detectBom(sample);
        if (bomCharset != null) return bomCharset;

        // 2. 二进制检测
        if (isBinary(sample)) return null;

        // 3. UTF-8验证（覆盖90%以上代码文件）
        if (isValidUtf8(sample)) return StandardCharsets.UTF_8;

        // 4. Fallback: 假定ISO-8859-1（全字节范围有效）
        return StandardCharsets.ISO_8859_1;
    }

    /**
     * 判断样本数据是否为二进制内容。
     */
    public boolean isBinary(byte[] sample) {
        if (sample.length == 0) return false;
        int nullCount = 0;
        int controlCount = 0;
        for (byte b : sample) {
            int unsigned = Byte.toUnsignedInt(b);
            if (unsigned == 0) nullCount++;
            // 控制字符（排除常见的\t\n\r）
            if (unsigned < 0x08 || (unsigned > 0x0D && unsigned < 0x20 && unsigned != 0x1B)) controlCount++;
        }
        // 超过1%的null字节或5%的控制字符视为二进制
        return nullCount > sample.length * 0.01 || controlCount > sample.length * 0.05;
    }

    private boolean isValidUtf8(byte[] data) {
        // 在样本末尾找到最后一个完整 UTF-8 字符的边界，截断不完整的尾部字节
        int validLength = findUtf8SafeBoundary(data);
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(data, 0, validLength));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /**
     * 找到 UTF-8 安全边界 — 从末尾回退最多3字节，确保不在多字节序列中间截断。
     * UTF-8 编码规则:
     * - 1字节: 0xxxxxxx
     * - 多字节起始: 11xxxxxx
     * - 续字节: 10xxxxxx
     */
    private int findUtf8SafeBoundary(byte[] data) {
        int length = data.length;
        if (length == 0) return 0;

        // 从末尾向前检查最多3个字节（UTF-8最大4字节序列）
        for (int i = 1; i <= Math.min(3, length); i++) {
            byte b = data[length - i];
            if ((b & 0x80) == 0) {
                // ASCII 单字节字符，边界安全
                return length;
            }
            if ((b & 0xC0) == 0xC0) {
                // 找到多字节序列的起始字节，检查序列是否完整
                int expectedLen = getUtf8SequenceLength(b);
                if (expectedLen <= i) {
                    // 序列完整
                    return length;
                } else {
                    // 序列不完整，截断到此起始字节之前
                    return length - i;
                }
            }
            // 10xxxxxx 续字节，继续向前找起始字节
        }
        // 回退3字节仍未找到起始字节，截断最后3字节
        return length - Math.min(3, length);
    }

    private int getUtf8SequenceLength(byte startByte) {
        if ((startByte & 0xE0) == 0xC0) return 2;
        if ((startByte & 0xF0) == 0xE0) return 3;
        if ((startByte & 0xF8) == 0xF0) return 4;
        return 1; // invalid, treat as single byte
    }

    private Charset detectBom(byte[] data) {
        if (data.length >= 3 && data[0] == (byte) 0xEF && data[1] == (byte) 0xBB && data[2] == (byte) 0xBF)
            return StandardCharsets.UTF_8;
        if (data.length >= 2 && data[0] == (byte) 0xFE && data[1] == (byte) 0xFF)
            return StandardCharsets.UTF_16BE;
        if (data.length >= 2 && data[0] == (byte) 0xFF && data[1] == (byte) 0xFE)
            return StandardCharsets.UTF_16LE;
        return null;
    }

    private byte[] readSample(Path filePath, int maxBytes) throws IOException {
        try (InputStream is = Files.newInputStream(filePath)) {
            return is.readNBytes(maxBytes);
        }
    }
}
