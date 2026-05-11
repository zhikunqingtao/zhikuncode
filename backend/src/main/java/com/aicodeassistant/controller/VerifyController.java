package com.aicodeassistant.controller;

import com.aicodeassistant.model.RunChecksRequest;
import com.aicodeassistant.model.RunChecksResponse;
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
 *   <li>POST /api/verify/run-checks — 执行验证检查</li>
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

    @PostMapping("/run-checks")
    public ResponseEntity<RunChecksResponse> runChecks(
            @RequestBody RunChecksRequest request,
            Principal principal) {

        log.info("Received run-checks request for operation {} from user {}",
            request.operationId(), principal != null ? principal.getName() : "anonymous");

        // Validate request
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
            // Get workspace path from session (for now use a default)
            String workspacePath = System.getProperty("user.dir");

            // Run checks synchronously (they internally use parallel execution)
            RunChecksResponse response = verifyCheckService.runChecks(request, workspacePath);

            // Push result via WebSocket if principal available
            if (principal != null) {
                CompletableFuture.runAsync(() ->
                    verifyCheckService.pushVerificationResult(
                        principal.getName(), request.sessionId(), response)
                );
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to run checks for operation {}", request.operationId(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
