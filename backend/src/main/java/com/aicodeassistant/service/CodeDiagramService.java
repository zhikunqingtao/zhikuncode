package com.aicodeassistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 代码图表生成服务 — 通过 Python 服务生成 Mermaid 时序图/流程图。
 */
@Service
public class CodeDiagramService {

    private static final Logger log = LoggerFactory.getLogger(CodeDiagramService.class);

    private static final String CAPABILITY_DOMAIN = "ANALYSIS";
    private static final String GENERATE_ENDPOINT = "/api/analysis/generate-diagram";
    private static final Set<String> VALID_DIAGRAM_TYPES = Set.of("sequence", "flowchart");

    private final PythonCapabilityAwareClient pythonClient;
    private final ObjectMapper objectMapper;

    public CodeDiagramService(PythonCapabilityAwareClient pythonClient, ObjectMapper objectMapper) {
        this.pythonClient = pythonClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成代码图表。
     *
     * @param diagramType 图表类型: "sequence" 或 "flowchart"
     * @param target      分析目标（API 路径、类名等）
     * @param projectRoot 项目根目录
     * @param depth       分析深度 1-5
     * @return 图表生成结果
     */
    public DiagramGenerationResult generateDiagram(String diagramType, String target,
                                                    String projectRoot, int depth) {
        // 参数校验
        if (diagramType == null || !VALID_DIAGRAM_TYPES.contains(diagramType)) {
            return DiagramGenerationResult.error(
                    "Invalid diagramType: must be 'sequence' or 'flowchart', got: " + diagramType);
        }
        if (target == null || target.isBlank()) {
            return DiagramGenerationResult.error("target must not be empty");
        }
        if (depth < 1 || depth > 5) {
            return DiagramGenerationResult.error("depth must be between 1 and 5, got: " + depth);
        }

        // 构造 Python 请求体 (snake_case)
        Map<String, Object> requestBody = Map.of(
                "diagram_type", diagramType,
                "target", target,
                "project_root", projectRoot != null ? projectRoot : "",
                "options", Map.of(
                        "depth", depth,
                        "include_tests", false,
                        "format", "mermaid"
                )
        );

        log.info("生成代码图表: type={}, target={}, depth={}", diagramType, target, depth);

        // 调用 Python 服务
        Optional<JsonNode> response = pythonClient.callIfAvailable(
                CAPABILITY_DOMAIN, GENERATE_ENDPOINT, requestBody, JsonNode.class);

        if (response.isEmpty()) {
            log.warn("Python ANALYSIS 能力不可用或调用失败，无法生成图表");
            return DiagramGenerationResult.error(
                    "Python ANALYSIS capability is not available. Ensure python-service is running.");
        }

        // 解析 Python 响应 (snake_case -> camelCase)
        return parsePythonResponse(response.get(), diagramType);
    }

    private DiagramGenerationResult parsePythonResponse(JsonNode root, String diagramType) {
        try {
            // Python 成功响应不含 success 字段 — 2xx 即成功
            // 仅当存在 error 字段时视为失败
            if (root.has("error") && !root.path("error").isNull()) {
                String errorMsg = root.path("error").asText("Unknown error from Python service");
                return DiagramGenerationResult.error(errorMsg);
            }

            String mermaidSyntax = root.path("mermaid_syntax").asText("");
            double confidenceScore = root.path("confidence_score").asDouble(0.0);

            // 解析 metadata — Python 返回 snake_case 字段
            DiagramMetadata metadata = null;
            JsonNode metaNode = root.path("metadata");
            if (!metaNode.isMissingNode() && !metaNode.isNull()) {
                // 解析 languages_analyzed 数组
                List<String> languages = new ArrayList<>();
                JsonNode langsNode = metaNode.path("languages_analyzed");
                if (langsNode.isArray()) {
                    for (JsonNode lang : langsNode) {
                        languages.add(lang.asText());
                    }
                }

                metadata = new DiagramMetadata(
                        metaNode.path("nodes_count").asInt(0),
                        metaNode.path("edges_count").asInt(0),
                        languages,
                        metaNode.path("analysis_time_ms").asDouble(0.0)
                );
            }

            // 解析 warnings
            List<String> warnings = List.of();
            JsonNode warningsNode = root.path("warnings");
            if (warningsNode.isArray()) {
                warnings = objectMapper.convertValue(warningsNode,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            }

            return new DiagramGenerationResult(
                    true, diagramType, mermaidSyntax, confidenceScore, metadata, warnings, null);

        } catch (Exception e) {
            log.error("解析 Python 图表响应失败", e);
            return DiagramGenerationResult.error("Failed to parse Python response: " + e.getMessage());
        }
    }

    // ═══ DTOs ═══

    /**
     * 图表生成结果。
     */
    public record DiagramGenerationResult(
            boolean success,
            String diagramType,
            String mermaidSyntax,
            double confidenceScore,
            DiagramMetadata metadata,
            List<String> warnings,
            String error
    ) {
        public static DiagramGenerationResult error(String message) {
            return new DiagramGenerationResult(false, null, null, 0.0, null, List.of(), message);
        }
    }

    /**
     * 图表元数据。
     */
    public record DiagramMetadata(
            int nodesCount,
            int edgesCount,
            List<String> languagesAnalyzed,
            double analysisTimeMs
    ) {
    }
}
