package com.aicodeassistant.authorization;

import com.aicodeassistant.tool.ToolInput;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** 规范字节是授权和后续实际执行共同使用的唯一输入。 */
public final class FrozenToolInput implements AutoCloseable {
    private final String toolName;
    private final int schemaVersion;
    private final byte[] canonicalJson;
    private final int canonicalLength;
    private final String inputHash;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    FrozenToolInput(String toolName, int schemaVersion, byte[] canonicalJson, int canonicalLength,
                    String inputHash, Runnable release) {
        this.toolName = toolName;
        this.schemaVersion = schemaVersion;
        // 工厂将新建字节数组的所有权移交给本对象；此处复制会让瞬时内存翻倍而配额只统计一份。
        // 对外访问器仍返回防御性副本。
        this.canonicalJson = canonicalJson;
        this.canonicalLength = canonicalLength;
        this.inputHash = inputHash;
        this.release = release;
    }

    public String toolName() { return toolName; }
    public int schemaVersion() { return schemaVersion; }
    public byte[] canonicalJsonBytes() { return Arrays.copyOf(canonicalJson, canonicalLength); }
    public String canonicalJson() { return new String(canonicalJson, 0, canonicalLength,
            java.nio.charset.StandardCharsets.UTF_8); }
    public String inputHash() { return inputHash; }
    public int byteSize() { return canonicalLength; }
    int allocatedBytes() { return canonicalJson.length; }

    public ToolInput toToolInput(ObjectMapper mapper) {
        try {
            Map<String, Object> value = mapper.readValue(canonicalJson, 0, canonicalLength,
                    new TypeReference<>() { });
            return ToolInput.from(value);
        } catch (Exception invalid) {
            throw new IllegalStateException("TOOL_INPUT_INVALID: frozen input cannot be reconstructed", invalid);
        }
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) release.run();
    }
}
