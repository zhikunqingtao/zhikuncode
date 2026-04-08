package com.aicodeassistant.bridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 桥接系统内部工具类集合 — 消息去重、epoch 管理、会话 ID 兼容、工作密钥。
 *
 * @see <a href="SPEC §4.5.8">桥接系统内部实现细节</a>
 */
public final class BridgeUtils {

    private BridgeUtils() {}

    // ==================== §4.5.8 BoundedUUIDSet ====================

    /**
     * 有界 UUID 去重集 — 环形缓冲区实现的消息去重。
     * <p>
     * 防止桥接消息被重复处理（网络重传/客户端重发场景）。
     * 满时丢弃最旧条目（LRU 语义）。
     */
    public static class BoundedUUIDSet {
        private static final int DEFAULT_CAPACITY = 10000;

        private final LinkedHashSet<String> uuids;
        private final int capacity;

        public BoundedUUIDSet() { this(DEFAULT_CAPACITY); }

        public BoundedUUIDSet(int capacity) {
            this.capacity = capacity;
            this.uuids = new LinkedHashSet<>();
        }

        /** 添加消息 ID，返回 true 表示是新消息，false 表示重复 */
        public boolean add(String uuid) {
            if (uuids.contains(uuid)) return false;
            if (uuids.size() >= capacity) {
                var it = uuids.iterator();
                it.next();
                it.remove();
            }
            uuids.add(uuid);
            return true;
        }

        /** 是否包含指定 UUID */
        public boolean contains(String uuid) {
            return uuids.contains(uuid);
        }

        /** 当前大小 */
        public int size() { return uuids.size(); }

        /** 清空 */
        public void clear() { uuids.clear(); }
    }

    // ==================== §4.5.8 EpochManager ====================

    /**
     * Epoch 管理 — 会话版本控制机制。
     * <p>
     * 客户端断线重连后，epoch 递增标记新连接周期，
     * 旧 epoch 的消息自动丢弃。
     */
    public static class EpochManager {
        private final AtomicLong epoch = new AtomicLong(0);

        /** 递增 epoch（每次重连时调用） */
        public long incrementEpoch() { return epoch.incrementAndGet(); }

        /** 获取当前 epoch */
        public long currentEpoch() { return epoch.get(); }

        /** 验证消息 epoch 是否匹配当前 epoch */
        public boolean isCurrentEpoch(long messageEpoch) {
            return messageEpoch == epoch.get();
        }
    }

    // ==================== §4.5.8 SessionIdCompat ====================

    /**
     * 会话 ID 兼容层 — 处理不同格式的会话 ID 互转。
     */
    public static class SessionIdCompat {

        /** 规范化会话 ID（去除前缀，统一格式） */
        public static String normalize(String rawSessionId) {
            if (rawSessionId == null) return null;
            String id = rawSessionId.startsWith("session:")
                    ? rawSessionId.substring(8) : rawSessionId;
            return id.trim();
        }

        /** 生成 IDE 兼容的会话 ID */
        public static String toIdeFormat(String sessionId) {
            return "session:" + sessionId;
        }

        /** cse_* → session_* 格式转换 */
        public static String toCompatSessionId(String infraId) {
            if (infraId == null) return null;
            if (infraId.startsWith("cse_")) {
                return "session_" + infraId.substring(4);
            }
            return infraId;
        }

        /** session_* → cse_* 格式转换 */
        public static String toInfraSessionId(String compatId) {
            if (compatId == null) return null;
            if (compatId.startsWith("session_")) {
                return "cse_" + compatId.substring(8);
            }
            return compatId;
        }
    }

    // ==================== §4.5.8 WorkSecret ====================

    /**
     * 工作密钥 — 桥接会话的认证令牌。
     */
    public static class WorkSecretUtil {

        /** 生成随机工作密钥（32 字节 hex） */
        public static String generate() {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            return HexFormat.of().formatHex(bytes);
        }

        /** 验证工作密钥（时间恒定比较，防止时序攻击） */
        public static boolean verify(String expected, String actual) {
            if (expected == null || actual == null) return false;
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    actual.getBytes(StandardCharsets.UTF_8));
        }
    }
}
