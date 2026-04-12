package com.aicodeassistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 能力注册表服务 — 加载、查询、修改、持久化 mcp_capability_registry.json。
 *
 * @see McpCapabilityDefinition
 * @see McpCapabilityController
 */
@Service
public class McpCapabilityRegistryService {

    private static final Logger log = LoggerFactory.getLogger(McpCapabilityRegistryService.class);

    private final ObjectMapper objectMapper;
    private final Map<String, McpCapabilityDefinition> capabilities = new ConcurrentHashMap<>();

    /** 通过 McpConfiguration 构造注入，而非 @Value 字段注入 */
    private final String registryPath;

    public McpCapabilityRegistryService(ObjectMapper objectMapper, McpConfiguration mcpConfiguration) {
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.registryPath = mcpConfiguration.getCapabilityRegistryPath();
    }

    @PostConstruct
    public void loadRegistry() {
        Path path = Path.of(registryPath);
        if (!Files.exists(path)) {
            log.warn("MCP capability registry not found: {}", registryPath);
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(path.toFile());
            JsonNode mcpTools = root.get("mcp_tools");
            if (mcpTools == null || !mcpTools.isArray()) {
                log.warn("MCP capability registry has no 'mcp_tools' array");
                return;
            }
            int loaded = 0;
            for (JsonNode node : mcpTools) {
                try {
                    McpCapabilityDefinition def = objectMapper.treeToValue(
                            node, McpCapabilityDefinition.class);
                    if (def.id() != null) {
                        capabilities.put(def.id(), def);
                        loaded++;
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse MCP capability: {}", e.getMessage());
                }
            }
            log.info("MCP capability registry loaded: {} tools from {}", loaded, registryPath);
        } catch (IOException e) {
            log.error("Failed to load MCP capability registry: {}", e.getMessage(), e);
        }
    }

    // ===== 查询接口 =====

    public List<McpCapabilityDefinition> listAll() { return List.copyOf(capabilities.values()); }

    public List<McpCapabilityDefinition> listByDomain(String domain) {
        return capabilities.values().stream().filter(d -> domain.equals(d.domain())).toList();
    }

    public List<McpCapabilityDefinition> listEnabled() {
        return capabilities.values().stream().filter(McpCapabilityDefinition::enabled).toList();
    }

    public Optional<McpCapabilityDefinition> findById(String id) {
        return Optional.ofNullable(capabilities.get(id));
    }

    public Optional<McpCapabilityDefinition> findByToolName(String serverKey, String toolName) {
        return capabilities.values().stream()
                .filter(cap -> toolName.equals(cap.toolName()) && serverKey.equals(cap.extractServerKey()))
                .findFirst();
    }

    public List<String> listDomains() {
        return capabilities.values().stream()
                .map(McpCapabilityDefinition::domain).filter(Objects::nonNull)
                .distinct().sorted().toList();
    }

    public int size() { return capabilities.size(); }

    public long enabledCount() {
        return capabilities.values().stream().filter(McpCapabilityDefinition::enabled).count();
    }

    // ===== 修改接口 =====

    public McpCapabilityDefinition toggleEnabled(String id, boolean enabled) {
        McpCapabilityDefinition existing = capabilities.get(id);
        if (existing == null) throw new IllegalArgumentException("MCP capability not found: " + id);
        McpCapabilityDefinition updated = existing.withEnabled(enabled);
        capabilities.put(id, updated);
        saveToFileAsync();
        log.info("MCP capability '{}' toggled to enabled={}", id, enabled);
        return updated;
    }

    public McpCapabilityDefinition updateCapability(String id, McpCapabilityDefinition updated) {
        if (!capabilities.containsKey(id)) throw new IllegalArgumentException("Not found: " + id);
        capabilities.put(id, updated);
        saveToFileAsync();
        return updated;
    }

    public McpCapabilityDefinition addCapability(McpCapabilityDefinition def) {
        if (capabilities.containsKey(def.id()))
            throw new IllegalArgumentException("Already exists: " + def.id());
        capabilities.put(def.id(), def);
        saveToFileAsync();
        return def;
    }

    public boolean deleteCapability(String id) {
        McpCapabilityDefinition removed = capabilities.remove(id);
        if (removed != null) { saveToFileAsync(); return true; }
        return false;
    }

    // ===== 持久化 (防抖 + 互斥) =====

    private final java.util.concurrent.locks.ReentrantLock saveLock =
            new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.ScheduledExecutorService saveScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    /** AtomicReference 确保 cancel + schedule 的原子性 */
    private final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>>
            pendingSave = new java.util.concurrent.atomic.AtomicReference<>();

    private void saveToFileAsync() {
        java.util.concurrent.ScheduledFuture<?> prev = pendingSave.getAndSet(
                saveScheduler.schedule(this::saveToFile, 500,
                        java.util.concurrent.TimeUnit.MILLISECONDS));
        if (prev != null) prev.cancel(false);
    }

    public void saveToFile() {
        Path path = Path.of(registryPath);
        saveLock.lock();
        try {
            ObjectNode root;
            if (Files.exists(path)) {
                root = (ObjectNode) objectMapper.readTree(path.toFile());
            } else {
                root = objectMapper.createObjectNode();
                root.put("_schema_version", "1.0");
            }
            ArrayNode toolsArray = objectMapper.createArrayNode();
            capabilities.values().stream()
                    .sorted(Comparator.comparing(McpCapabilityDefinition::id))
                    .forEach(def -> toolsArray.add(objectMapper.valueToTree(def)));
            root.set("mcp_tools", toolsArray);
            root.put("lastUpdated", java.time.LocalDate.now().toString());
            objectMapper.writeValue(path.toFile(), root);
            log.debug("MCP capability registry saved to {}", registryPath);
        } catch (IOException e) {
            log.error("Failed to save MCP capability registry: {}", e.getMessage(), e);
        } finally {
            saveLock.unlock();
        }
    }
}
