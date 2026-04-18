package com.aicodeassistant.tool;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具注册表 — Spring Bean 自动发现并注册所有 Tool 实现。
 * <p>
 * 支持按名称和别名查找，按分组列出，动态注册/注销。
 *
 * @see <a href="SPEC §3.2.4">工具池装配流程</a>
 */
@Service
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> toolsByName = new ConcurrentHashMap<>();
    private final Map<String, Tool> toolsByAlias = new ConcurrentHashMap<>();

    /** Caffeine 缓存：排序后的工具列表（key = sessionId::toolSetHash） */
    private final Cache<String, List<Tool>> sortedToolCache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    /**
     * 构造函数 — 通过 Spring 自动注入所有 Tool 实现。
     */
    public ToolRegistry(List<Tool> tools) {
        for (Tool tool : tools) {
            register(tool);
        }
        log.info("ToolRegistry initialized with {} tools: {}",
                toolsByName.size(), toolsByName.keySet());
    }

    /** 注册工具 */
    public void register(Tool tool) {
        toolsByName.put(tool.getName(), tool);
        for (String alias : tool.getAliases()) {
            toolsByAlias.put(alias, tool);
        }
    }

    /** 注销工具 */
    public void unregister(String toolName) {
        Tool removed = toolsByName.remove(toolName);
        if (removed != null) {
            for (String alias : removed.getAliases()) {
                toolsByAlias.remove(alias);
            }
        }
    }

    /** 按名称查找工具（含别名） */
    public Tool findByName(String name) {
        Tool tool = toolsByName.get(name);
        if (tool != null) return tool;
        tool = toolsByAlias.get(name);
        if (tool != null) return tool;
        throw new IllegalArgumentException("Unknown tool: " + name);
    }

    /** 安全查找（不抛异常） */
    public Optional<Tool> findByNameOptional(String name) {
        Tool tool = toolsByName.get(name);
        if (tool != null) return Optional.of(tool);
        return Optional.ofNullable(toolsByAlias.get(name));
    }

    /** 列出所有已注册的工具 */
    public List<Tool> getAllTools() {
        return List.copyOf(toolsByName.values());
    }

    /** 列出启用的工具 */
    public List<Tool> getEnabledTools() {
        return toolsByName.values().stream()
                .filter(Tool::isEnabled)
                .toList();
    }

    /** 列出启用的工具（按会话级过滤，当前实现与无参版本一致） */
    public List<Tool> getEnabledTools(String sessionId) {
        // P1: 未来可根据 sessionId 做会话级工具过滤
        return getEnabledTools();
    }

    /** 按分组列出工具 */
    public Map<String, List<Tool>> getToolsByGroup() {
        Map<String, List<Tool>> grouped = new LinkedHashMap<>();
        for (Tool tool : toolsByName.values()) {
            grouped.computeIfAbsent(tool.getGroup(), k -> new ArrayList<>()).add(tool);
        }
        return grouped;
    }

    /** 获取所有工具的 API 定义格式（排序：内建在前，MCP在后，组内按名称排序） */
    public List<Map<String, Object>> getToolDefinitions() {
        return getEnabledToolsSorted().stream()
                .map(Tool::toToolDefinition)
                .toList();
    }

    /**
     * 获取工具定义并在内建/MCP分界处插入 cache_control 断点。
     * <p>
     * 在最后一个内建工具的定义上添加 cache_control: {type: "ephemeral"}，
     * 使 MCP 工具变更不影响内建工具部分的缓存命中。
     *
     * @return 带缓存断点的工具定义列表
     */
    public List<Map<String, Object>> getToolDefinitionsWithCacheBreakpoint() {
        List<Tool> sorted = getEnabledToolsSorted();
        List<Map<String, Object>> definitions = new ArrayList<>();

        // 找到内建工具的最后一个索引
        int lastBuiltInIdx = -1;
        for (int i = 0; i < sorted.size(); i++) {
            if (!sorted.get(i).isMcp()) {
                lastBuiltInIdx = i;
            }
        }

        boolean hasMcpTools = sorted.stream().anyMatch(Tool::isMcp);

        for (int i = 0; i < sorted.size(); i++) {
            Map<String, Object> def = sorted.get(i).toToolDefinition();
            // 在最后一个内建工具上添加 cache_control 断点（仅当存在MCP工具时）
            if (i == lastBuiltInIdx && hasMcpTools) {
                Map<String, Object> defWithCache = new LinkedHashMap<>(def);
                defWithCache.put("cache_control", Map.of("type", "ephemeral"));
                definitions.add(defWithCache);
            } else {
                definitions.add(def);
            }
        }
        return definitions;
    }

    /** 工具数量 */
    public int size() {
        return toolsByName.size();
    }

    // ============ 动态注册方法 ============

    /**
     * 动态注册工具 — 用于 MCP 服务器工具、认证工具等运行时发现的工具。
     */
    public void registerDynamic(Tool tool) {
        register(tool);
        invalidateSortedCache();
        log.info("Dynamically registered tool: {}", tool.getName());
    }

    /**
     * 按名称前缀批量注销工具 — 用于 MCP 服务器重连时清理旧工具。
     *
     * @param prefix 工具名称前缀
     * @return 被注销的工具数量
     */
    public int unregisterByPrefix(String prefix) {
        List<String> toRemove = toolsByName.keySet().stream()
                .filter(name -> name.startsWith(prefix))
                .toList();
        for (String name : toRemove) {
            unregister(name);
        }
        if (!toRemove.isEmpty()) {
            log.info("Unregistered {} tools with prefix '{}'", toRemove.size(), prefix);
            invalidateSortedCache();
        }
        return toRemove.size();
    }

    /**
     * 获取启用工具（排序：内建工具按名称排序在前，MCP工具按名称排序在后）。
     * <p>
     * prompt cache 分区排序：内建工具（非 MCP）排在前面，确保 cache hit 率最大化。
     * MCP 工具（动态注册、可能变化）排在后面。组内均按名称字母序排列以保证顺序稳定。
     */
    public List<Tool> getEnabledToolsSorted() {
        return getEnabledTools().stream()
                .sorted(Comparator.comparing((Tool t) -> t.isMcp() ? 1 : 0)
                        .thenComparing(Tool::getName))
                .toList();
    }

    /**
     * 获取启用工具（带 Caffeine 缓存，避免每轮重新排序）。
     * <p>
     * 缓存 key 格式：sessionId::toolSetHash，工具集变更时自动失效。
     *
     * @param sessionId 会话ID
     * @return 排序后的工具列表（内建在前，MCP在后，组内按名称排序）
     */
    public List<Tool> getEnabledToolsSortedCached(String sessionId) {
        String cacheKey = sessionId + "::" + computeToolSetHash();
        return sortedToolCache.get(cacheKey, k -> getEnabledToolsSorted());
    }

    /**
     * 计算当前启用工具集的 SHA-256 哈希（前16位）。
     * 用于缓存 key 构建，工具增减时哈希自动变化。
     */
    private String computeToolSetHash() {
        String toolNames = getEnabledTools().stream()
                .map(Tool::getName)
                .sorted()
                .collect(Collectors.joining(","));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(toolNames.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return String.valueOf(toolNames.hashCode());
        }
    }

    /**
     * 使排序工具缓存全部失效 — 在工具注册/注销后调用。
     */
    public void invalidateSortedCache() {
        sortedToolCache.invalidateAll();
        log.debug("Sorted tool cache invalidated");
    }

    /** 子代理禁用的工具名集合 */
    private static final Set<String> SUB_AGENT_DENIED_TOOLS = Set.of(
            "Agent", "TeamCreate", "TeamDelete", "TaskCreate",
            "VerifyPlanExecution"  // 计划验证仅主代理可用
    );

    /**
     * 获取子代理可用工具子集 — 过滤掉 Agent/Team/Task 等子代理禁用的工具。
     */
    public List<Tool> getSubAgentTools() {
        return getEnabledTools().stream()
                .filter(t -> !SUB_AGENT_DENIED_TOOLS.contains(t.getName()))
                .toList();
    }
}
