package com.aicodeassistant.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * MCP 审批服务 — 管理第三方 MCP 服务器的信任记录。
 * <p>
 * 信任记录存储在 ~/.qoder/mcp-trusted.json（对齐 §11.5.5 目录约定）。
 * configHash = SHA256(command + args + url)，配置变更需重新审批。
 *
 * @see <a href="SPEC §11.2.2">MCP 服务器审批流程</a>
 */
@Service
public class McpApprovalService {

    private static final Logger log = LoggerFactory.getLogger(McpApprovalService.class);

    private static final Path TRUST_FILE = Path.of(System.getProperty("user.home"),
            ".qoder", "mcp-trusted.json");

    private final ObjectMapper objectMapper;

    public McpApprovalService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 信任记录。
     */
    public record TrustRecord(
            String serverName,
            String configHash,
            Instant approvedAt,
            String scope // SESSION or PERMANENT
    ) {}

    /**
     * 检查服务器是否已被信任。
     *
     * @param config MCP 服务器配置
     * @return true 如果已信任且 configHash 匹配
     */
    public boolean isTrusted(McpServerConfig config) {
        String hash = computeConfigHash(config);
        Map<String, TrustRecord> records = loadTrustRecords();
        TrustRecord record = records.get(config.name());
        if (record == null) return false;
        return hash.equals(record.configHash());
    }

    /**
     * 记录审批（信任）。
     */
    public void recordApproval(McpServerConfig config, String scope) {
        String hash = computeConfigHash(config);
        Map<String, TrustRecord> records = loadTrustRecords();
        records.put(config.name(), new TrustRecord(
                config.name(), hash, Instant.now(), scope));
        saveTrustRecords(records);
        log.info("MCP trust recorded: {} (scope={})", config.name(), scope);
    }

    /**
     * 撤销信任。
     */
    public void revokeTrust(String serverName) {
        Map<String, TrustRecord> records = loadTrustRecords();
        if (records.remove(serverName) != null) {
            saveTrustRecords(records);
            log.info("MCP trust revoked: {}", serverName);
        }
    }

    /**
     * 获取所有信任记录。
     */
    public Map<String, TrustRecord> getAllTrustRecords() {
        return loadTrustRecords();
    }

    /**
     * 计算配置 hash — SHA256(command + args + url)。
     */
    private String computeConfigHash(McpServerConfig config) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String input = (config.type() != null ? config.type().name() : "") + "|"
                    + (config.command() != null ? config.command() : "") + "|"
                    + (config.args() != null ? String.join(",", config.args()) : "") + "|"
                    + (config.url() != null ? config.url() : "");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            log.error("Failed to compute config hash", e);
            return "";
        }
    }

    private Map<String, TrustRecord> loadTrustRecords() {
        if (!Files.exists(TRUST_FILE)) return new LinkedHashMap<>();
        try {
            String json = Files.readString(TRUST_FILE);
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (IOException e) {
            log.warn("Failed to load trust records: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void saveTrustRecords(Map<String, TrustRecord> records) {
        try {
            Path dir = TRUST_FILE.getParent();
            if (dir != null && !Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            String json = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(records);
            Files.writeString(TRUST_FILE, json);
            // 设置权限 600（仅用户可读写）— 仅 POSIX 系统
            try {
                Files.setPosixFilePermissions(TRUST_FILE, Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Windows 不支持 POSIX 权限
            }
        } catch (IOException e) {
            log.error("Failed to save trust records", e);
        }
    }
}
