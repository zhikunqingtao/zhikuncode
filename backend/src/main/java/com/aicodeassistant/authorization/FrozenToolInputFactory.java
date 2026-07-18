package com.aicodeassistant.authorization;

import com.aicodeassistant.tool.ToolInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 有大小上限的规范 JSON 构建器，并按在途缓冲区实际容量统计内存。 */
@Component
public final class FrozenToolInputFactory {
    public static final int INPUT_SCHEMA_VERSION = 1;
    private final ObjectMapper mapper;
    private final int maxBytes;
    private final long maxInflightBytes;
    private final AtomicLong inflightBytes = new AtomicLong();

    public FrozenToolInputFactory(ObjectMapper mapper,
            @Value("${authorization.max-canonical-input-bytes:10485760}") int maxBytes,
            @Value("${authorization.max-inflight-canonical-bytes:67108864}") long maxInflightBytes) {
        if (maxBytes < 1 || maxInflightBytes < maxBytes) {
            throw new IllegalArgumentException("Invalid authorization canonical input limits");
        }
        this.mapper = mapper.copy();
        this.maxBytes = maxBytes;
        this.maxInflightBytes = maxInflightBytes;
    }

    /**
     * 将工具输入规范化并冻结，后续分析与执行都必须使用该快照。
     *
     * @param toolName 工具稳定名称，用于隔离不同工具的输入哈希域
     * @param input 原始工具输入
     * @return 带规范字节和完整性哈希的冻结输入；调用方必须关闭以释放内存配额
     */
    public FrozenToolInput freeze(String toolName, ToolInput input) {
        reserve(maxBytes);
        boolean reservationOwned = true;
        try {
            LimitedOutputStream limited = new LimitedOutputStream(maxBytes);
            try (JsonGenerator generator = mapper.getFactory().createGenerator(limited)) {
                writeCanonical(generator, input.getRawData());
            }
            LimitedOutputStream.Detached detached = limited.detach();
            byte[] bytes = detached.bytes();
            int length = detached.length();
            // 统计数组容量而不是逻辑长度，避免低估 JVM 中真实保留的堆内存。
            long releasedReservation = maxBytes - bytes.length;
            inflightBytes.addAndGet(-releasedReservation);
            reservationOwned = false;
            String hash = sha256("authz-input-v1\0" + toolName + "\0" + INPUT_SCHEMA_VERSION + "\0",
                    bytes, length);
            return new FrozenToolInput(toolName, INPUT_SCHEMA_VERSION, bytes, length, hash,
                    () -> inflightBytes.addAndGet(-bytes.length));
        } catch (LimitExceededException tooLarge) {
            throw new AuthorizationException("TOOL_INPUT_TOO_LARGE", tooLarge.getMessage());
        } catch (Exception invalid) {
            throw new AuthorizationException("TOOL_INPUT_INVALID", "Unable to canonicalize tool input", invalid);
        } finally {
            if (reservationOwned) inflightBytes.addAndGet(-maxBytes);
        }
    }

    public long inflightBytes() { return inflightBytes.get(); }

    private void reserve(long amount) {
        while (true) {
            long current = inflightBytes.get();
            if (amount > maxInflightBytes - current) {
                throw new AuthorizationException("AUTHORIZATION_INPUT_CAPACITY_EXCEEDED",
                        "Authorization input memory budget is exhausted");
            }
            if (inflightBytes.compareAndSet(current, current + amount)) return;
        }
    }

    private void writeCanonical(JsonGenerator generator, Object value) throws IOException {
        if (value == null) { generator.writeNull(); return; }
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<String, Object>> entries = new ArrayList<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IOException("Canonical JSON object keys must be strings");
                }
                entries.add(new java.util.AbstractMap.SimpleImmutableEntry<>(key, entry.getValue()));
            }
            entries.sort(Comparator.comparing(Map.Entry::getKey));
            generator.writeStartObject();
            for (Map.Entry<String, Object> entry : entries) {
                generator.writeFieldName(entry.getKey());
                writeCanonical(generator, entry.getValue());
            }
            generator.writeEndObject();
            return;
        }
        if (value instanceof List<?> list) {
            generator.writeStartArray();
            for (Object element : list) writeCanonical(generator, element);
            generator.writeEndArray();
            return;
        }
        if (value instanceof String text) { generator.writeString(text); return; }
        if (value instanceof Boolean bool) { generator.writeBoolean(bool); return; }
        if (value instanceof Integer number) { generator.writeNumber(number); return; }
        if (value instanceof Long number) { generator.writeNumber(number); return; }
        if (value instanceof Short number) { generator.writeNumber(number); return; }
        if (value instanceof Byte number) { generator.writeNumber(number); return; }
        if (value instanceof BigInteger number) { generator.writeNumber(number); return; }
        if (value instanceof BigDecimal number) { generator.writeNumber(number.stripTrailingZeros()); return; }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) throw new IOException("Non-finite JSON number");
            generator.writeNumber(number);
            return;
        }
        if (value instanceof Float number) {
            if (!Float.isFinite(number)) throw new IOException("Non-finite JSON number");
            generator.writeNumber(number);
            return;
        }
        throw new IOException("Unsupported canonical JSON value: " + value.getClass().getName());
    }

    private static String sha256(String prefix, byte[] bytes, int length) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(prefix.getBytes(StandardCharsets.UTF_8));
        digest.update(bytes, 0, length);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static final class LimitedOutputStream extends OutputStream {
        private final int limit;
        private byte[] bytes;
        private int count;
        private boolean detached;
        private LimitedOutputStream(int limit) {
            this.limit = limit;
            this.bytes = new byte[Math.min(limit, 8192)];
        }
        @Override public void write(int value) throws IOException {
            ensure(1); bytes[count++] = (byte) value;
        }
        @Override public void write(byte[] value, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset > value.length - length) {
                throw new IndexOutOfBoundsException();
            }
            ensure(length); System.arraycopy(value, offset, bytes, count, length); count += length;
        }
        private void ensure(int additional) throws LimitExceededException {
            if (detached) throw new IllegalStateException("Canonical buffer already detached");
            if (additional > limit - count) {
                throw new LimitExceededException("Canonical tool input exceeds " + limit + " UTF-8 bytes");
            }
            int required = count + additional;
            if (required <= bytes.length) return;
            int next = Math.min(limit, Math.max(required, Math.max(1, bytes.length) * 2));
            bytes = java.util.Arrays.copyOf(bytes, next);
        }
        private Detached detach() {
            if (detached) throw new IllegalStateException("Canonical buffer already detached");
            detached = true;
            return new Detached(bytes, count);
        }
        private record Detached(byte[] bytes, int length) { }
    }

    private static final class LimitExceededException extends IOException {
        private LimitExceededException(String message) { super(message); }
    }
}
