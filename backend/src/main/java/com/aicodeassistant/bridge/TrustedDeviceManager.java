package com.aicodeassistant.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 受信设备管理器 — 管理设备信任关系。
 * <p>
 * 已信任的设备可跳过额外认证步骤。
 * 信任数据持久化到 {@code ~/.ai-code-assistant/trusted-devices.json}。
 *
 * @see <a href="SPEC §4.5.6">受信设备管理</a>
 */
public class TrustedDeviceManager {

    private static final Logger log = LoggerFactory.getLogger(TrustedDeviceManager.class);

    /** 默认信任文件路径 */
    static final Path DEFAULT_TRUST_FILE = Path.of(
            System.getProperty("user.home"), ".ai-code-assistant", "trusted-devices.json");

    private final Path trustFile;
    private final ConcurrentHashMap<String, TrustedDevice> trustedDevices = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    public TrustedDeviceManager() {
        this(DEFAULT_TRUST_FILE);
    }

    public TrustedDeviceManager(Path trustFile) {
        this.trustFile = trustFile;
    }

    // ==================== 公开 API ====================

    /** 检查当前设备是否已受信 */
    public boolean isDeviceTrusted(String deviceId) {
        ensureLoaded();
        return trustedDevices.containsKey(deviceId);
    }

    /** 信任当前设备 */
    public void trustDevice(String deviceId, String description) {
        ensureLoaded();
        trustedDevices.put(deviceId, new TrustedDevice(
                deviceId, description, Instant.now().toString()));
        saveTrustedDevices();
        log.info("Device trusted: {} ({})", deviceId, description);
    }

    /** 撤销设备信任 */
    public void revokeDevice(String deviceId) {
        ensureLoaded();
        TrustedDevice removed = trustedDevices.remove(deviceId);
        if (removed != null) {
            saveTrustedDevices();
            log.info("Device trust revoked: {}", deviceId);
        }
    }

    /** 获取所有受信设备列表 */
    public List<TrustedDevice> listTrustedDevices() {
        ensureLoaded();
        return List.copyOf(trustedDevices.values());
    }

    /** 受信设备数量 */
    public int deviceCount() {
        ensureLoaded();
        return trustedDevices.size();
    }

    /** 清除所有受信设备 */
    public void clearAll() {
        trustedDevices.clear();
        saveTrustedDevices();
        log.info("All trusted devices cleared");
    }

    // ==================== 受信设备记录 ====================

    /**
     * 受信设备数据。
     *
     * @param deviceId    设备唯一标识
     * @param description 设备描述
     * @param trustedAt   信任时间（ISO 8601）
     */
    public record TrustedDevice(String deviceId, String description, String trustedAt) {}

    // ==================== 内部实现 ====================

    private void ensureLoaded() {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    loadTrustedDevices();
                    loaded = true;
                }
            }
        }
    }

    void loadTrustedDevices() {
        if (!Files.exists(trustFile)) {
            log.debug("Trust file not found: {}", trustFile);
            return;
        }
        try {
            String content = Files.readString(trustFile, StandardCharsets.UTF_8);
            parseTrustedDevices(content);
        } catch (IOException e) {
            log.warn("Failed to load trusted devices from {}", trustFile, e);
        }
    }

    /** 简易 JSON 数组解析 — 提取 deviceId, description, trustedAt */
    void parseTrustedDevices(String json) {
        if (json == null || json.isBlank()) return;
        json = json.trim();
        if (!json.startsWith("[")) return;

        // 简单按 { } 分割每个设备对象
        int depth = 0;
        int objStart = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String obj = json.substring(objStart, i + 1);
                    String deviceId = extractJsonString(obj, "deviceId");
                    String description = extractJsonString(obj, "description");
                    String trustedAt = extractJsonString(obj, "trustedAt");
                    if (deviceId != null) {
                        trustedDevices.put(deviceId,
                                new TrustedDevice(deviceId, description, trustedAt));
                    }
                }
            }
        }
    }

    void saveTrustedDevices() {
        try {
            Files.createDirectories(trustFile.getParent());
            StringBuilder sb = new StringBuilder("[\n");
            boolean first = true;
            for (TrustedDevice device : trustedDevices.values()) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append("  {")
                        .append("\"deviceId\":\"").append(escapeJson(device.deviceId())).append("\",")
                        .append("\"description\":\"").append(escapeJson(
                                device.description() != null ? device.description() : "")).append("\",")
                        .append("\"trustedAt\":\"").append(escapeJson(
                                device.trustedAt() != null ? device.trustedAt() : "")).append("\"")
                        .append("}");
            }
            sb.append("\n]");
            Files.writeString(trustFile, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to save trusted devices to {}", trustFile, e);
        }
    }

    /** 从 JSON 对象字符串中提取指定字段的字符串值 */
    static String extractJsonString(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return null;
        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return null;
        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) return null;
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;
        return json.substring(quoteStart + 1, quoteEnd);
    }

    static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
