package com.aicodeassistant.controller;

import com.aicodeassistant.model.RunChecksRequest;
import com.aicodeassistant.model.RunChecksResponse;
import com.aicodeassistant.model.dto.VerifyCheckRequest;
import com.aicodeassistant.model.dto.VerifyCheckResponse;
import com.aicodeassistant.service.VerifyCheckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.concurrent.CompletableFuture;

/**
 * VerifyController — 确定性验证 API。
 * <p>
 * 端点:
 * <ul>
 *   <li>POST /api/verify/run-checks — 执行验证检查（Phase 2 增强版）</li>
 *   <li>POST /api/verify/legacy-checks — 旧版检查（向后兼容）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/verify")
public class VerifyController {
    private static final Logger log = LoggerFactory.getLogger(VerifyController.class);

    private final VerifyCheckService verifyCheckService;

    public VerifyController(VerifyCheckService verifyCheckService) {
        this.verifyCheckService = verifyCheckService;
    }

    /**
     * Phase 2 增强版 — 每文件独立检查 + heuristic + Signal 计算。
     */
    @PostMapping("/run-checks")
    public ResponseEntity<VerifyCheckResponse> runChecks(
            @RequestBody VerifyCheckRequest request,
            Principal principal) {

        log.info("Received Phase 2 run-checks request for session {} from user {}",
            request.sessionId(), principal != null ? principal.getName() : "anonymous");

        // Validate request
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.filePaths() == null || request.filePaths().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            String workspacePath = request.workingDirectory() != null
                    ? request.workingDirectory()
                    : System.getProperty("user.dir");

            VerifyCheckResponse response = verifyCheckService.executeChecks(request, workspacePath);

            // Push verification_result via WebSocket
            if (principal != null) {
                CompletableFuture.runAsync(() ->
                    verifyCheckService.pushVerificationResult(request.sessionId(), response)
                );
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to run Phase 2 checks for session {}", request.sessionId(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 旧版检查 — 向后兼容。
     */
    @PostMapping("/legacy-checks")
    public ResponseEntity<RunChecksResponse> legacyChecks(
            @RequestBody RunChecksRequest request,
            Principal principal) {

        log.info("Received legacy run-checks request for operation {} from user {}",
            request.operationId(), principal != null ? principal.getName() : "anonymous");

        if (request.sessionId() == null || request.operationId() == null) {
            return ResponseEntity.badRequest().build();
        }
        if (request.checks() == null || request.checks().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.filePaths() == null || request.filePaths().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            String workspacePath = System.getProperty("user.dir");
            RunChecksResponse response = verifyCheckService.runLegacyChecks(request, workspacePath);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to run legacy checks for operation {}", request.operationId(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
