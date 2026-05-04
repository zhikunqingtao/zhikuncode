package com.aicodeassistant.controller;

import com.aicodeassistant.service.CodePathService;
import com.aicodeassistant.service.CodePathService.CodePathEndpointsResult;
import com.aicodeassistant.service.CodePathService.CodePathTraceResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 代码路径分析 REST API — 扫描 API 端点、追踪调用链路。
 */
@RestController
@RequestMapping("/api/code-path")
public class CodePathController {

    private final CodePathService codePathService;

    public CodePathController(CodePathService codePathService) {
        this.codePathService = codePathService;
    }

    /**
     * 扫描项目中的 API 端点。
     */
    @PostMapping("/endpoints")
    public ResponseEntity<?> scanEndpoints(@RequestBody EndpointsRequest request) {
        if (request.projectRoot() == null || request.projectRoot().isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "projectRoot is required"));
        }

        CodePathEndpointsResult result = codePathService.scanApiEndpoints(
                request.projectRoot(), request.languages());

        if (!result.success()) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 追踪代码调用路径。
     */
    @PostMapping("/trace")
    public ResponseEntity<?> traceCodePath(@RequestBody TraceRequest request) {
        if (request.projectRoot() == null || request.projectRoot().isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "projectRoot is required"));
        }
        if (request.entryFile() == null || request.entryFile().isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "entryFile is required"));
        }
        if (request.entryFunction() == null || request.entryFunction().isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "entryFunction is required"));
        }

        int maxDepth = request.maxDepth() != null ? request.maxDepth() : 5;

        CodePathTraceResult result = codePathService.traceCodePath(
                request.projectRoot(), request.entryFile(), request.entryFunction(), maxDepth);

        return ResponseEntity.ok(result);
    }

    // ═══ Request DTOs ═══

    public record EndpointsRequest(
            String projectRoot,
            List<String> languages
    ) {}

    public record TraceRequest(
            String projectRoot,
            String entryFile,
            String entryFunction,
            Integer maxDepth
    ) {}
}
