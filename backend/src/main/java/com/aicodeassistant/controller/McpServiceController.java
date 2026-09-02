package com.aicodeassistant.controller;

import com.aicodeassistant.mcp.McpCapabilityDefinition;
import com.aicodeassistant.mcp.McpCapabilityRegistryService;
import com.aicodeassistant.mcp.McpClientManager;
import com.aicodeassistant.mcp.McpServerConfig;
import com.aicodeassistant.mcp.McpServerConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 用户可见的 MCP 服务级管理接口。服务默认关闭，只有显式启用才建立连接。 */
@RestController
@RequestMapping("/api/mcp/services")
public class McpServiceController {

    private final McpCapabilityRegistryService registry;
    private final McpClientManager manager;

    public McpServiceController(McpCapabilityRegistryService registry, McpClientManager manager) {
        this.registry = registry;
        this.manager = manager;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listServices() {
        List<Map<String, Object>> services = buildServices();
        long enabledCount = services.stream()
                .filter(service -> Boolean.TRUE.equals(service.get("enabled")))
                .count();
        return ResponseEntity.ok(Map.of(
                "services", services,
                "total", services.size(),
                "enabledCount", enabledCount));
    }

    @PatchMapping("/{serverKey}/toggle")
    public ResponseEntity<Map<String, Object>> toggleService(
            @PathVariable String serverKey, @RequestParam boolean enabled) {
        List<McpCapabilityDefinition> definitions = registry.listByServerKey(serverKey);
        boolean configured = manager.listConfiguredServers().stream()
                .anyMatch(config -> serverKey.equals(config.name()));
        if (definitions.isEmpty() && !configured) return ResponseEntity.notFound().build();

        registry.setServiceEnabled(serverKey, enabled);
        String status = enabled ? "not_connected" : "disabled";
        if (!enabled) {
            manager.removeServer(serverKey);
        } else {
            try {
                McpServerConnection connection;
                if (!definitions.isEmpty()) {
                    McpCapabilityDefinition representative = definitions.stream()
                            .filter(McpCapabilityDefinition::enabled)
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "This MCP service has no allowlisted tools"));
                    connection = manager.enableFromRegistry(representative);
                } else {
                    connection = manager.enableConfiguredServer(serverKey);
                }
                status = connection.getStatus().name().toLowerCase();
            } catch (Exception e) {
                status = "failed";
            }
        }

        Map<String, Object> service = buildServices().stream()
                .filter(item -> serverKey.equals(item.get("serverKey")))
                .findFirst().orElseGet(LinkedHashMap::new);
        service.put("status", status);
        return ResponseEntity.ok(service);
    }

    private List<Map<String, Object>> buildServices() {
        Map<String, List<McpCapabilityDefinition>> grouped = new LinkedHashMap<>();
        for (McpCapabilityDefinition capability : registry.listAll()) {
            grouped.computeIfAbsent(capability.extractServerKey(), ignored -> new ArrayList<>())
                    .add(capability);
        }
        grouped.values().forEach(list -> list.sort(Comparator.comparing(
                McpCapabilityDefinition::name, Comparator.nullsLast(String::compareTo))));

        Map<String, McpServerConfig> configured = new LinkedHashMap<>();
        for (McpServerConfig config : manager.listConfiguredServers()) {
            configured.put(config.name(), config);
        }

        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(grouped.keySet());
        keys.addAll(configured.keySet());

        List<Map<String, Object>> result = new ArrayList<>();
        for (String key : keys) {
            List<McpCapabilityDefinition> definitions = grouped.getOrDefault(key, List.of());
            McpServerConfig config = configured.get(key);
            result.add(toView(key, definitions, config));
        }
        result.sort(Comparator
                .comparing((Map<String, Object> view) -> !Boolean.TRUE.equals(view.get("enabled")))
                .thenComparing(view -> String.valueOf(view.get("displayName"))));
        return result;
    }

    private Map<String, Object> toView(String serverKey,
                                       List<McpCapabilityDefinition> definitions,
                                       McpServerConfig configured) {
        boolean enabled = registry.isServiceEnabled(serverKey);
        McpServerConnection connection = manager.getConnection(serverKey).orElse(null);
        String status = connection != null
                ? connection.getStatus().name().toLowerCase()
                : enabled ? "not_connected" : "disabled";
        String readiness = "ready";
        if (!definitions.isEmpty()) {
            readiness = manager.registryReadiness(definitions.get(0));
            if (enabled && connection == null && !"ready".equals(readiness)) status = readiness;
        }

        List<Map<String, Object>> tools = definitions.stream().map(definition -> {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("id", definition.id());
            tool.put("name", definition.name());
            tool.put("toolName", definition.toolName());
            tool.put("description", valueOrEmpty(definition.briefDescription()));
            tool.put("allowlisted", definition.enabled());
            return tool;
        }).toList();

        McpCapabilityDefinition first = definitions.isEmpty() ? null : definitions.get(0);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("serverKey", serverKey);
        view.put("displayName", displayName(serverKey, first));
        view.put("description", first != null ? valueOrEmpty(first.briefDescription())
                : "来自 ZHIKUN_MCP_SERVERS 或配置文件的 MCP 服务");
        view.put("domain", first != null ? valueOrEmpty(first.domain()) : "configured");
        view.put("source", definitions.isEmpty() ? "configured" : "registry");
        view.put("transportType", first != null ? first.resolvedTransportType().name()
                : configured != null ? configured.type().name() : "UNKNOWN");
        view.put("enabled", enabled);
        view.put("status", status);
        view.put("readiness", readiness);
        view.put("toolCount", definitions.isEmpty() && connection != null
                ? connection.getTools().size() : tools.size());
        view.put("tools", tools);
        return view;
    }

    private static String valueOrEmpty(String value) {
        return value != null ? value : "";
    }

    private static String displayName(String key, McpCapabilityDefinition first) {
        return switch (key) {
            case "github" -> "GitHub";
            case "context7" -> "Context7";
            case "alibaba-cloud-ops" -> "Alibaba Cloud Ops";
            case "zhipu-websearch" -> "智谱网络搜索";
            case "market-cmgjmcp00075019" -> "新闻查询";
            case "market-cmgjmcp00074976" -> "法律检索";
            case "market-cmgjmcp00074980" -> "企业信息";
            case "market-cmgjmcp00075121" -> "文本内容审核";
            case "amap-maps" -> "高德地图";
            case "QwenImage" -> "千问图像";
            case "WanVideo" -> "万相视频";
            case "market-cmgjmcp00074946" -> "A股金融数据";
            case "market-cmgjmcp00074975" -> "企业知识产权";
            case "market-cmgjmcp00075341" -> "万方文献";
            case "arxiv_paper" -> "arXiv 论文";
            case "market-cmgjmcp00075018" -> "1688 智能选品";
            case "market-cmgjmcp00074959" -> "物流查询";
            case "market-cmgjmcp00075146" -> "贵金属行情";
            case "market-cmgjmcp00075054" -> "零售洞察";
            case "market-cmgjmcp00075060" -> "旅游消费";
            default -> first != null && first.name() != null ? first.name() : key;
        };
    }
}
