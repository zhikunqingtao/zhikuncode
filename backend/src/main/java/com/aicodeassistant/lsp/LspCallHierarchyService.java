package com.aicodeassistant.lsp;

import com.aicodeassistant.lsp.model.CallLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * LSP Call Hierarchy 服务 — 提供调用层级分析能力。
 * <p>
 * 实现策略:
 * <ul>
 *   <li>Phase 1: prepareCallHierarchy + incomingCalls (核心: 谁调用了我)</li>
 *   <li>Phase 2: outgoingCalls + implementations</li>
 * </ul>
 * <p>
 * 降级策略: 当 LSP Server 不支持 callHierarchy (LSP 3.16+) 时，
 * 自动回退到 textDocument/references 作为兜底方案。
 */
@Component
public class LspCallHierarchyService {

    private static final Logger log = LoggerFactory.getLogger(LspCallHierarchyService.class);

    private final LSPServerManager serverManager;

    public LspCallHierarchyService(LSPServerManager serverManager) {
        this.serverManager = serverManager;
    }

    /**
     * 准备调用层级 — callHierarchy/prepare 第一步。
     * <p>
     * 返回指定位置的 CallHierarchyItem，后续 incoming/outgoing 操作依赖此结果。
     *
     * @param filePath  文件路径
     * @param line      行号 (1-based)
     * @param character 列号 (1-based)
     * @return 调用层级项信息，不支持时返回 Optional.empty()
     */
    public Optional<Map<String, Object>> prepareCallHierarchy(String filePath, int line, int character) {
        LSPServerInstance server = serverManager.getServerForFile(filePath);
        if (server == null) {
            log.warn("No LSP server available for file: {}", filePath);
            return Optional.empty();
        }

        Map<String, Object> params = new HashMap<>();
        params.put("textDocument", Map.of("uri", toUri(filePath)));
        params.put("position", Map.of("line", line - 1, "character", character - 1));

        try {
            Map<String, Object> result = server.sendRequest("textDocument/prepareCallHierarchy", params);
            if (result == null || result.isEmpty() || isErrorResponse(result)) {
                log.debug("prepareCallHierarchy returned empty/error for {}:{}:{}", filePath, line, character);
                return Optional.empty();
            }
            return Optional.of(result);
        } catch (Exception e) {
            log.warn("prepareCallHierarchy failed, server may not support LSP 3.16+: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 获取谁调用了指定位置的函数 — callHierarchy/incomingCalls。
     * <p>
     * Phase 1 核心功能。降级策略: 如果 callHierarchy 不可用，回退到 textDocument/references。
     *
     * @param filePath  文件路径
     * @param line      行号 (1-based)
     * @param character 列号 (1-based)
     * @return 调用者位置列表
     */
    public List<CallLocation> getIncomingCalls(String filePath, int line, int character) {
        LSPServerInstance server = serverManager.getServerForFile(filePath);
        if (server == null) {
            log.warn("No LSP server for incoming calls: {}", filePath);
            return Collections.emptyList();
        }

        // Step 1: 尝试 prepareCallHierarchy
        Optional<Map<String, Object>> prepared = prepareCallHierarchy(filePath, line, character);
        if (prepared.isEmpty()) {
            log.info("callHierarchy not available, falling back to references for {}:{}:{}", filePath, line, character);
            return fallbackToReferences(filePath, line, character);
        }

        // Step 2: callHierarchy/incomingCalls
        Map<String, Object> params = new HashMap<>();
        params.put("item", prepared.get());

        try {
            Map<String, Object> result = server.sendRequest("callHierarchy/incomingCalls", params);
            if (result == null || result.isEmpty() || isErrorResponse(result)) {
                log.info("incomingCalls returned empty/error, falling back to references");
                return fallbackToReferences(filePath, line, character);
            }
            return parseCallLocations(result);
        } catch (Exception e) {
            log.warn("incomingCalls failed: {}, falling back to references", e.getMessage());
            return fallbackToReferences(filePath, line, character);
        }
    }

    /**
     * 获取指定位置的函数调用了谁 — callHierarchy/outgoingCalls。
     * <p>
     * Phase 2 功能，当前使用 fallback 实现。
     *
     * @param filePath  文件路径
     * @param line      行号 (1-based)
     * @param character 列号 (1-based)
     * @return 被调用者位置列表
     */
    public List<CallLocation> getOutgoingCalls(String filePath, int line, int character) {
        LSPServerInstance server = serverManager.getServerForFile(filePath);
        if (server == null) {
            log.warn("No LSP server for outgoing calls: {}", filePath);
            return Collections.emptyList();
        }

        // Step 1: 尝试 prepareCallHierarchy
        Optional<Map<String, Object>> prepared = prepareCallHierarchy(filePath, line, character);
        if (prepared.isEmpty()) {
            log.info("callHierarchy not available for outgoingCalls, falling back to references");
            return fallbackToReferences(filePath, line, character);
        }

        // Step 2: callHierarchy/outgoingCalls
        Map<String, Object> params = new HashMap<>();
        params.put("item", prepared.get());

        try {
            Map<String, Object> result = server.sendRequest("callHierarchy/outgoingCalls", params);
            if (result == null || result.isEmpty() || isErrorResponse(result)) {
                log.info("outgoingCalls returned empty/error, falling back to references");
                return fallbackToReferences(filePath, line, character);
            }
            return parseCallLocations(result);
        } catch (Exception e) {
            log.warn("outgoingCalls failed: {}, falling back to references", e.getMessage());
            return fallbackToReferences(filePath, line, character);
        }
    }

    /**
     * 获取接口/抽象类的实现 — textDocument/implementation。
     * <p>
     * Phase 2 功能。
     *
     * @param filePath  文件路径
     * @param line      行号 (1-based)
     * @param character 列号 (1-based)
     * @return 实现位置列表
     */
    public List<CallLocation> getImplementations(String filePath, int line, int character) {
        LSPServerInstance server = serverManager.getServerForFile(filePath);
        if (server == null) {
            log.warn("No LSP server for implementations: {}", filePath);
            return Collections.emptyList();
        }

        Map<String, Object> params = new HashMap<>();
        params.put("textDocument", Map.of("uri", toUri(filePath)));
        params.put("position", Map.of("line", line - 1, "character", character - 1));

        try {
            Map<String, Object> result = server.sendRequest("textDocument/implementation", params);
            if (result == null || result.isEmpty() || isErrorResponse(result)) {
                log.info("implementation returned empty/error, falling back to references");
                return fallbackToReferences(filePath, line, character);
            }
            return parseLocationResults(result);
        } catch (Exception e) {
            log.warn("implementation failed: {}, falling back to references", e.getMessage());
            return fallbackToReferences(filePath, line, character);
        }
    }

    // ===== 降级方案 =====

    /**
     * 降级方案: LSP 不支持 callHierarchy 时回退到 textDocument/references。
     * <p>
     * references 能提供所有引用位置，虽然不区分调用方向，
     * 但作为 fallback 足以提供基本的影响分析数据。
     */
    private List<CallLocation> fallbackToReferences(String filePath, int line, int character) {
        LSPServerInstance server = serverManager.getServerForFile(filePath);
        if (server == null) {
            return Collections.emptyList();
        }

        Map<String, Object> params = new HashMap<>();
        params.put("textDocument", Map.of("uri", toUri(filePath)));
        params.put("position", Map.of("line", line - 1, "character", character - 1));
        params.put("context", Map.of("includeDeclaration", false));

        try {
            Map<String, Object> result = server.sendRequest("textDocument/references", params);
            if (result == null || result.isEmpty()) {
                return Collections.emptyList();
            }
            return parseLocationResults(result);
        } catch (Exception e) {
            log.error("fallbackToReferences also failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ===== 结果解析 =====

    /**
     * 解析 callHierarchy 响应为 CallLocation 列表。
     * <p>
     * LSP callHierarchy 响应格式:
     * <pre>
     * [{ "from": { "name": "...", "uri": "...", "range": {...} }, "fromRanges": [...] }]
     * </pre>
     */
    @SuppressWarnings("unchecked")
    private List<CallLocation> parseCallLocations(Map<String, Object> result) {
        List<CallLocation> locations = new ArrayList<>();

        Object items = result.get("result");
        if (items instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> callItem) {
                    Map<String, Object> from = (Map<String, Object>) callItem.get("from");
                    if (from == null) {
                        from = (Map<String, Object>) callItem.get("to");
                    }
                    if (from != null) {
                        CallLocation loc = extractCallLocation(from);
                        if (loc != null) {
                            locations.add(loc);
                        }
                    }
                }
            }
        }

        return locations;
    }

    /**
     * 解析 Location[] 格式的响应 (references, implementation)。
     */
    @SuppressWarnings("unchecked")
    private List<CallLocation> parseLocationResults(Map<String, Object> result) {
        List<CallLocation> locations = new ArrayList<>();

        Object items = result.get("result");
        if (items instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> locMap) {
                    String uri = (String) locMap.get("uri");
                    Map<String, Object> range = (Map<String, Object>) locMap.get("range");
                    if (uri != null && range != null) {
                        locations.add(locationFromRange(uri, range));
                    }
                }
            }
        }

        return locations;
    }

    @SuppressWarnings("unchecked")
    private CallLocation extractCallLocation(Map<String, Object> hierarchyItem) {
        try {
            String uri = (String) hierarchyItem.get("uri");
            String name = (String) hierarchyItem.getOrDefault("name", "unknown");
            String container = (String) hierarchyItem.getOrDefault("containerName", "");
            Map<String, Object> range = (Map<String, Object>) hierarchyItem.get("range");

            if (uri == null || range == null) return null;

            String filePath = fromUri(uri);
            Map<String, Object> start = (Map<String, Object>) range.get("start");
            Map<String, Object> end = (Map<String, Object>) range.get("end");

            if (start == null || end == null) return null;

            return new CallLocation(
                    filePath,
                    ((Number) start.get("line")).intValue() + 1,  // LSP 0-based → 1-based
                    ((Number) start.get("character")).intValue() + 1,
                    ((Number) end.get("line")).intValue() + 1,
                    ((Number) end.get("character")).intValue() + 1,
                    name,
                    container
            );
        } catch (Exception e) {
            log.debug("Failed to parse call hierarchy item: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private CallLocation locationFromRange(String uri, Map<String, Object> range) {
        String filePath = fromUri(uri);
        Map<String, Object> start = (Map<String, Object>) range.get("start");
        Map<String, Object> end = (Map<String, Object>) range.get("end");

        int startLine = start != null ? ((Number) start.get("line")).intValue() + 1 : 1;
        int startChar = start != null ? ((Number) start.get("character")).intValue() + 1 : 1;
        int endLine = end != null ? ((Number) end.get("line")).intValue() + 1 : startLine;
        int endChar = end != null ? ((Number) end.get("character")).intValue() + 1 : startChar;

        return new CallLocation(filePath, startLine, startChar, endLine, endChar, "", "");
    }

    // ===== 工具方法 =====

    private boolean isErrorResponse(Map<String, Object> result) {
        return result.containsKey("error") || "error".equals(result.get("status"));
    }

    private String toUri(String filePath) {
        return "file://" + filePath;
    }

    private String fromUri(String uri) {
        if (uri.startsWith("file://")) {
            return uri.substring("file://".length());
        }
        return uri;
    }
}
