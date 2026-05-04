package com.aicodeassistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 代码路径分析服务 — 通过 Python 服务扫描 API 端点并追踪调用链路。
 */
@Service
public class CodePathService {

    private static final Logger log = LoggerFactory.getLogger(CodePathService.class);

    private static final String CAPABILITY_DOMAIN = "ANALYSIS";
    private static final String ENDPOINTS_URL = "/api/analysis/api-endpoints";
    private static final String CODE_PATH_URL = "/api/analysis/code-path";

    private final PythonCapabilityAwareClient pythonClient;
    private final ObjectMapper objectMapper;

    public CodePathService(PythonCapabilityAwareClient pythonClient, ObjectMapper objectMapper) {
        this.pythonClient = pythonClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 扫描项目中的 API 端点。
     */
    public CodePathEndpointsResult scanApiEndpoints(String projectRoot, List<String> languages) {
        if (projectRoot == null || projectRoot.isBlank()) {
            return CodePathEndpointsResult.error("projectRoot must not be empty");
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("project_root", projectRoot);
        if (languages != null && !languages.isEmpty()) {
            requestBody.put("languages", languages);
        }

        log.info("扫描 API 端点: projectRoot={}, languages={}", projectRoot, languages);

        Optional<JsonNode> response = pythonClient.callIfAvailable(
                CAPABILITY_DOMAIN, ENDPOINTS_URL, requestBody, JsonNode.class);

        if (response.isEmpty()) {
            log.warn("Python ANALYSIS 能力不可用或调用失败，无法扫描 API 端点");
            return CodePathEndpointsResult.error(
                    "Python ANALYSIS capability is not available. Ensure python-service is running.");
        }

        return parseEndpointsResponse(response.get());
    }

    /**
     * 追踪代码调用路径。
     */
    public CodePathTraceResult traceCodePath(String projectRoot, String entryFile,
                                              String entryFunction, int maxDepth) {
        if (projectRoot == null || projectRoot.isBlank()) {
            return CodePathTraceResult.error("projectRoot must not be empty");
        }
        if (entryFile == null || entryFile.isBlank()) {
            return CodePathTraceResult.error("entryFile must not be empty");
        }
        if (entryFunction == null || entryFunction.isBlank()) {
            return CodePathTraceResult.error("entryFunction must not be empty");
        }
        if (maxDepth < 1 || maxDepth > 20) {
            return CodePathTraceResult.error("maxDepth must be between 1 and 20, got: " + maxDepth);
        }

        Map<String, Object> requestBody = Map.of(
                "project_root", projectRoot,
                "entry_file", entryFile,
                "entry_function", entryFunction,
                "max_depth", maxDepth
        );

        log.info("追踪代码路径: entryFile={}, entryFunction={}, maxDepth={}", entryFile, entryFunction, maxDepth);

        Optional<JsonNode> response = pythonClient.callIfAvailable(
                CAPABILITY_DOMAIN, CODE_PATH_URL, requestBody, JsonNode.class);

        if (response.isEmpty()) {
            log.warn("Python ANALYSIS 能力不可用或调用失败，无法追踪代码路径");
            return CodePathTraceResult.error(
                    "Python ANALYSIS capability is not available. Ensure python-service is running.");
        }

        return parseTraceResponse(response.get());
    }

    // ═══ 响应解析 ═══

    private CodePathEndpointsResult parseEndpointsResponse(JsonNode root) {
        try {
            if (root.has("error") && !root.path("error").isNull()
                    && !root.path("error").asText("").isEmpty()) {
                return CodePathEndpointsResult.error(root.path("error").asText());
            }

            boolean success = root.path("success").asBoolean(true);

            // data 字段可能包裹实际数据，也可能直接在根级
            JsonNode dataNode = root.has("data") ? root.path("data") : root;

            // 优先从 data 内读取 analysis_time_ms，兼容 elapsed_ms
            double elapsedMs = dataNode.path("analysis_time_ms").asDouble(
                    root.path("elapsed_ms").asDouble(0.0));
            JsonNode endpointsNode = dataNode.path("endpoints");

            List<ApiEndpointItem> endpoints = new ArrayList<>();
            if (endpointsNode.isArray()) {
                for (JsonNode ep : endpointsNode) {
                    List<Map<String, String>> params = parseStringMapList(ep.path("parameters"));
                    endpoints.add(new ApiEndpointItem(
                            ep.path("http_method").asText(""),
                            ep.path("path").asText(""),
                            ep.path("handler_function").asText(""),
                            ep.path("handler_class").asText(""),
                            ep.path("file_path").asText(""),
                            ep.path("line_number").asInt(0),
                            ep.path("language").asText(""),
                            params
                    ));
                }
            }

            int total = dataNode.has("total") ? dataNode.path("total").asInt(endpoints.size())
                    : endpoints.size();

            return new CodePathEndpointsResult(success, endpoints, total, elapsedMs, null);

        } catch (Exception e) {
            log.error("解析 Python API 端点响应失败", e);
            return CodePathEndpointsResult.error("Failed to parse Python response: " + e.getMessage());
        }
    }

    private CodePathTraceResult parseTraceResponse(JsonNode root) {
        try {
            if (root.has("error") && !root.path("error").isNull()
                    && !root.path("error").asText("").isEmpty()) {
                return CodePathTraceResult.error(root.path("error").asText());
            }

            boolean success = root.path("success").asBoolean(true);

            JsonNode dataNode = root.has("data") ? root.path("data") : root;

            // 优先从 data 内读取 analysis_time_ms，兼容 elapsed_ms
            double elapsedMs = dataNode.path("analysis_time_ms").asDouble(
                    root.path("elapsed_ms").asDouble(0.0));

            // 解析 nodes
            List<PathNodeItem> nodes = new ArrayList<>();
            JsonNode nodesNode = dataNode.path("nodes");
            if (nodesNode.isArray()) {
                for (JsonNode n : nodesNode) {
                    List<Integer> lineRange = new ArrayList<>();
                    JsonNode lr = n.path("line_range");
                    if (lr.isArray()) {
                        for (JsonNode v : lr) lineRange.add(v.asInt());
                    }

                    List<String> annotations = new ArrayList<>();
                    JsonNode ann = n.path("annotations");
                    if (ann.isArray()) {
                        for (JsonNode v : ann) annotations.add(v.asText());
                    }

                    List<Map<String, String>> params = parseStringMapList(n.path("parameters"));

                    nodes.add(new PathNodeItem(
                            n.path("id").asText(""),
                            n.path("name").asText(""),
                            n.path("class_name").asText(""),
                            n.path("file_path").asText(""),
                            lineRange,
                            n.path("layer").asText(""),
                            n.path("node_type").asText(""),
                            annotations,
                            params,
                            n.path("return_type").asText("")
                    ));
                }
            }

            // 解析 edges
            List<PathEdgeItem> edges = new ArrayList<>();
            JsonNode edgesNode = dataNode.path("edges");
            if (edgesNode.isArray()) {
                for (JsonNode e : edgesNode) {
                    Map<String, String> paramMapping = new HashMap<>();
                    JsonNode pm = e.path("parameter_mapping");
                    if (pm.isObject()) {
                        pm.fields().forEachRemaining(f -> paramMapping.put(f.getKey(), f.getValue().asText()));
                    }
                    edges.add(new PathEdgeItem(
                            e.path("source").asText(""),
                            e.path("target").asText(""),
                            e.path("call_type").asText(""),
                            paramMapping
                    ));
                }
            }

            // 解析 layers
            List<LayerInfoItem> layers = new ArrayList<>();
            JsonNode layersNode = dataNode.path("layers");
            if (layersNode.isArray()) {
                for (JsonNode l : layersNode) {
                    layers.add(new LayerInfoItem(
                            l.path("layer").asText(""),
                            l.path("node_count").asInt(0),
                            l.path("description").asText("")
                    ));
                }
            }

            return new CodePathTraceResult(success, nodes, edges, layers, elapsedMs, null);

        } catch (Exception e) {
            log.error("解析 Python 代码路径响应失败", e);
            return CodePathTraceResult.error("Failed to parse Python response: " + e.getMessage());
        }
    }

    private List<Map<String, String>> parseStringMapList(JsonNode arrayNode) {
        List<Map<String, String>> result = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                Map<String, String> map = new HashMap<>();
                item.fields().forEachRemaining(f -> map.put(f.getKey(), f.getValue().asText()));
                result.add(map);
            }
        }
        return result;
    }

    // ═══ DTOs ═══

    public record ApiEndpointItem(
            String httpMethod,
            String path,
            String handlerFunction,
            String handlerClass,
            String filePath,
            int lineNumber,
            String language,
            List<Map<String, String>> parameters
    ) {}

    public record CodePathEndpointsResult(
            boolean success,
            List<ApiEndpointItem> endpoints,
            int total,
            double elapsedMs,
            String error
    ) {
        public static CodePathEndpointsResult error(String message) {
            return new CodePathEndpointsResult(false, List.of(), 0, 0.0, message);
        }
    }

    public record PathNodeItem(
            String id,
            String name,
            String className,
            String filePath,
            List<Integer> lineRange,
            String layer,
            String nodeType,
            List<String> annotations,
            List<Map<String, String>> parameters,
            String returnType
    ) {}

    public record PathEdgeItem(
            String source,
            String target,
            String callType,
            Map<String, String> parameterMapping
    ) {}

    public record LayerInfoItem(
            String layer,
            int nodeCount,
            String description
    ) {}

    public record CodePathTraceResult(
            boolean success,
            List<PathNodeItem> nodes,
            List<PathEdgeItem> edges,
            List<LayerInfoItem> layers,
            double elapsedMs,
            String error
    ) {
        public static CodePathTraceResult error(String message) {
            return new CodePathTraceResult(false, List.of(), List.of(), List.of(), 0.0, message);
        }
    }
}
