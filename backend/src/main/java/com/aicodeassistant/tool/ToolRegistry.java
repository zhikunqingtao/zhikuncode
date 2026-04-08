package com.aicodeassistant.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    /** 获取所有工具的 API 定义格式 */
    public List<Map<String, Object>> getToolDefinitions() {
        return getEnabledTools().stream()
                .map(Tool::toToolDefinition)
                .toList();
    }

    /** 工具数量 */
    public int size() {
        return toolsByName.size();
    }
}
