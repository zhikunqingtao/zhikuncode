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
import java.nio.file.StandardCopyOption;
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

    /** 默认关闭的低频或高风险 MCP；用户可在管理页显式启用。 */
    private static final Set<String> DEFAULT_DISABLED_SERVERS = Set.of(
            "github",                 // GitHub MCP Server
            "context7",               // Context7
            "alibaba-cloud-ops",      // Alibaba Cloud Ops
            "market-cmgjmcp00075019", // 新闻查询
            "market-cmgjmcp00074976", // 法律检索
            "market-cmgjmcp00074980", // 企业信息
            "market-cmgjmcp00075121", // 文本内容审核
            "market-cmgjmcp00074946", // A股金融数据
            "market-cmgjmcp00074975", // 企业知识产权
            "market-cmgjmcp00075341", // 万方文献
            "arxiv_paper",
            "market-cmgjmcp00075018", // 1688智能选品
            "market-cmgjmcp00074959", // 物流查询
            "market-cmgjmcp00075146", // 贵金属行情
            "market-cmgjmcp00075054", // 零售洞察
            "market-cmgjmcp00075060"  // 旅游消费
    );

    private final ObjectMapper objectMapper;
    private final Map<String, McpCapabilityDefinition> capabilities = new ConcurrentHashMap<>();
    private final Map<String, Boolean> serviceStates = new ConcurrentHashMap<>();

    /** 通过 McpConfiguration 构造注入，而非 @Value 字段注入 */
    private final String registryPath;
    private final String serviceStatePath;

    public McpCapabilityRegistryService(ObjectMapper objectMapper, McpConfiguration mcpConfiguration) {
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.registryPath = mcpConfiguration.getCapabilityRegistryPath();
        this.serviceStatePath = mcpConfiguration.getServiceStatePath();
    }

    @PostConstruct
    public void loadRegistry() {
        loadServiceStates();
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

    /** 仅返回用户在管理页显式启用、且仍在工具白名单内的能力。 */
    public List<McpCapabilityDefinition> listRuntimeEnabled() {
        return capabilities.values().stream()
                .filter(McpCapabilityDefinition::enabled)
                .filter(cap -> isServiceEnabled(cap.extractServerKey()))
                .toList();
    }

    public List<McpCapabilityDefinition> listByServerKey(String serverKey) {
        return capabilities.values().stream()
                .filter(cap -> serverKey.equals(cap.extractServerKey()))
                .sorted(Comparator.comparing(McpCapabilityDefinition::name,
                        Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    /** 用户选择优先；未选择时使用内置的最小化默认开启策略。 */
    public boolean isServiceEnabled(String serverKey) {
        return serviceStates.getOrDefault(serverKey,
                !DEFAULT_DISABLED_SERVERS.contains(serverKey));
    }

    public Map<String, Boolean> listServiceStates() {
        return Map.copyOf(serviceStates);
    }

    public void setServiceEnabled(String serverKey, boolean enabled) {
        if (serverKey == null || serverKey.isBlank()) {
            throw new IllegalArgumentException("MCP server key is required");
        }
        serviceStates.put(serverKey, enabled);
        saveServiceStates();
        log.info("MCP service '{}' user state changed to enabled={}", serverKey, enabled);
    }

    public Optional<McpCapabilityDefinition> findById(String id) {
        return Optional.ofNullable(capabilities.get(id));
    }

    public Optional<McpCapabilityDefinition> findByToolName(String serverKey, String toolName) {
        return capabilities.values().stream()
                .filter(cap -> toolName.equals(cap.toolName()) && serverKey.equals(cap.extractServerKey()))
                .findFirst();
    }

    public Optional<McpCapabilityDefinition> findEnabledByToolName(String serverKey, String toolName) {
        return capabilities.values().stream()
                .filter(McpCapabilityDefinition::enabled)
                .filter(cap -> toolName.equals(cap.toolName()) && serverKey.equals(cap.extractServerKey()))
                .findFirst();
    }

    public boolean hasDefinitionsForServer(String serverKey) {
        return capabilities.values().stream()
                .anyMatch(cap -> serverKey.equals(cap.extractServerKey()));
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
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("zhiku-mcp-registry-save").factory());
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

    private void loadServiceStates() {
        if (serviceStatePath == null || serviceStatePath.isBlank()) return;
        serviceStates.clear();
        Path path = Path.of(serviceStatePath);
        if (!Files.isRegularFile(path)) {
            log.info("MCP service state file not found; using built-in defaults: {}", path);
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(path.toFile());
            JsonNode states = root.path("services");
            if (!states.isObject()) return;
            states.fields().forEachRemaining(entry ->
                    serviceStates.put(entry.getKey(), entry.getValue().asBoolean(false)));
            log.info("Loaded {} MCP service preference(s) from {}", serviceStates.size(), path);
        } catch (IOException e) {
            log.warn("Failed to load MCP service states from {}: {}", path, e.getMessage());
        }
    }

    /** 小文件同步原子写入，保证接口返回成功时用户选择已落盘。 */
    private void saveServiceStates() {
        if (serviceStatePath == null || serviceStatePath.isBlank()) return;
        Path path = Path.of(serviceStatePath);
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            ObjectNode root = objectMapper.createObjectNode();
            root.put("version", 1);
            ObjectNode services = root.putObject("services");
            serviceStates.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> services.put(entry.getKey(), entry.getValue()));
            Path temp = Files.createTempFile(parent, "mcp-service-states-", ".tmp");
            try {
                objectMapper.writeValue(temp.toFile(), root);
                try {
                    Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist MCP service state", e);
        }
    }
}
