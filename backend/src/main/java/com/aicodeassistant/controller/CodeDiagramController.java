package com.aicodeassistant.controller;

import com.aicodeassistant.service.CodeDiagramService;
import com.aicodeassistant.service.CodeDiagramService.DiagramGenerationResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 代码图表生成 REST API — 生成 Mermaid 时序图/流程图。
 */
@RestController
@RequestMapping("/api/code-diagrams")
public class CodeDiagramController {

    private final CodeDiagramService codeDiagramService;

    public CodeDiagramController(CodeDiagramService codeDiagramService) {
        this.codeDiagramService = codeDiagramService;
    }

    /**
     * 生成代码图表。
     *
     * @param request 请求体
     * @return 图表生成结果
     */
    @PostMapping("/generate")
    public ResponseEntity<?> generateDiagram(@RequestBody GenerateRequest request) {
        // 必填参数校验
        if (request.diagramType() == null || request.diagramType().isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "diagramType is required"));
        }
        if (request.target() == null || request.target().isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "target is required"));
        }

        int depth = request.depth() != null ? request.depth() : 3;

        DiagramGenerationResult result = codeDiagramService.generateDiagram(
                request.diagramType(), request.target(), request.projectRoot(), depth);

        return ResponseEntity.ok(result);
    }

    /**
     * 图表生成请求 DTO。
     */
    public record GenerateRequest(
            String diagramType,
            String target,
            String projectRoot,
            Integer depth
    ) {
    }
}
