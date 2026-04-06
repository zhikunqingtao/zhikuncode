package com.aicodeassistant.model;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 品牌化代理 ID — 标识一个代理实例（主代理或子代理）。
 * <p>
 * 格式: "a" + 可选 "&lt;label&gt;-" + 16位十六进制
 * 示例: "a1a2b3c4d5e6f7a8" (无标签) 或 "asearch-1a2b3c4d5e6f7a8" (有标签)
 *
 * @see <a href="SPEC §5.0">品牌化 ID 类型</a>
 */
public record AgentId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^a(?:[a-z]+-)?[0-9a-f]{16}$");

    public AgentId {
        Objects.requireNonNull(value, "AgentId cannot be null");
    }

    public static AgentId create(String label) {
        String hex = String.format("%016x", ThreadLocalRandom.current().nextLong());
        String id = label != null ? "a" + label + "-" + hex : "a" + hex;
        return new AgentId(id);
    }

    public static AgentId of(String raw) {
        return new AgentId(raw);
    }

    @Override
    public String toString() {
        return value;
    }
}
