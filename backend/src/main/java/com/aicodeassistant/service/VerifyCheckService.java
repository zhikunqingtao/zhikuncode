package com.aicodeassistant.service;

import com.aicodeassistant.model.RunChecksRequest;
import com.aicodeassistant.model.RunChecksResponse;
import com.aicodeassistant.model.RunChecksResponse.CheckResult;
import com.aicodeassistant.model.RunChecksResponse.CheckIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 确定性验证服务 — 执行检查、计算 Signal、推送结果。
 */
@Service
public class VerifyCheckService {
    private static final Logger log = LoggerFactory.getLogger(VerifyCheckService.class);
    private static final int DEFAULT_TIMEOUT_MS = 10_000;

    private final SimpMessagingTemplate messaging;

    public VerifyCheckService(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public RunChecksResponse runChecks(RunChecksRequest request, String workspacePath) {
        long startTime = System.currentTimeMillis();
        int timeout = Math.min(request.effectiveTimeout(), DEFAULT_TIMEOUT_MS);

        List<CompletableFuture<CheckResult>> futures = new ArrayList<>();

        for (String check : request.checks()) {
            futures.add(CompletableFuture.supplyAsync(() ->
                executeCheck(check, request.filePaths(), workspacePath, timeout)
            ));
        }

        // Wait for all checks with timeout
        List<CheckResult> results = new ArrayList<>();
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(timeout, TimeUnit.MILLISECONDS);
            for (CompletableFuture<CheckResult> f : futures) {
                results.add(f.get());
            }
        } catch (TimeoutException e) {
            log.warn("Verification timed out after {}ms for operation {}", timeout, request.operationId());
            // Collect completed results, mark timed-out as failed
            for (CompletableFuture<CheckResult> f : futures) {
                if (f.isDone()) {
                    try { results.add(f.get()); } catch (Exception ex) { /* skip */ }
                } else {
                    results.add(new CheckResult("timeout", false,
                        List.of(new CheckIssue("", 0, 0, "Check timed out", null)),
                        List.of(), timeout));
                    f.cancel(true);
                }
            }
        } catch (Exception e) {
            log.error("Verification failed for operation {}", request.operationId(), e);
            results.add(new CheckResult("error", false,
                List.of(new CheckIssue("", 0, 0, e.getMessage(), null)),
                List.of(), System.currentTimeMillis() - startTime));
        }

        // Compute signal
        SignalResult signalResult = computeSignal(results);
        return RunChecksResponse.create(request.operationId(), results, signalResult.signal(), signalResult.reason());
    }

    private CheckResult executeCheck(String check, List<String> filePaths, String workspacePath, int timeoutMs) {
        long start = System.currentTimeMillis();
        try {
            return switch (check) {
                case "typescript" -> runTypeScript(filePaths, workspacePath, timeoutMs);
                case "eslint" -> runEslint(filePaths, workspacePath, timeoutMs);
                case "test_match" -> runVitest(filePaths, workspacePath, timeoutMs);
                case "build" -> runBuild(workspacePath, timeoutMs);
                default -> new CheckResult(check, false,
                    List.of(new CheckIssue("", 0, 0, "Unknown check type: " + check, null)),
                    List.of(), System.currentTimeMillis() - start);
            };
        } catch (Exception e) {
            return new CheckResult(check, false,
                List.of(new CheckIssue("", 0, 0, e.getMessage(), null)),
                List.of(), System.currentTimeMillis() - start);
        }
    }

    private CheckResult runTypeScript(List<String> filePaths, String workspacePath, int timeoutMs) {
        return executeProcess("typescript", workspacePath, timeoutMs,
            "npx", "tsc", "--noEmit", "--incremental");
    }

    private CheckResult runEslint(List<String> filePaths, String workspacePath, int timeoutMs) {
        List<String> cmd = new ArrayList<>(List.of("npx", "eslint", "--format", "json"));
        cmd.addAll(filePaths);
        return executeProcess("eslint", workspacePath, timeoutMs, cmd.toArray(new String[0]));
    }

    private CheckResult runVitest(List<String> filePaths, String workspacePath, int timeoutMs) {
        return executeProcess("test_match", workspacePath, timeoutMs,
            "npx", "vitest", "run", "--reporter=json", "--bail");
    }

    private CheckResult runBuild(String workspacePath, int timeoutMs) {
        return executeProcess("build", workspacePath, timeoutMs,
            "npx", "vite", "build", "--mode", "production");
    }

    private CheckResult executeProcess(String checkName, String workDir, int timeoutMs, String... command) {
        long start = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(workDir));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            long duration = System.currentTimeMillis() - start;

            if (!completed) {
                process.destroyForcibly();
                return new CheckResult(checkName, false,
                    List.of(new CheckIssue("", 0, 0, "Process timed out after " + timeoutMs + "ms", null)),
                    List.of(), duration);
            }

            int exitCode = process.exitValue();
            boolean passed = exitCode == 0;

            List<CheckIssue> errors = new ArrayList<>();
            List<CheckIssue> warnings = new ArrayList<>();

            if (!passed) {
                errors.add(new CheckIssue("", 0, 0, output.toString().trim(), null));
            }

            return new CheckResult(checkName, passed, errors, warnings, duration);
        } catch (Exception e) {
            return new CheckResult(checkName, false,
                List.of(new CheckIssue("", 0, 0, "Failed to execute: " + e.getMessage(), null)),
                List.of(), System.currentTimeMillis() - start);
        }
    }

    /** Signal 计算结果。 */
    public record SignalResult(String signal, String reason) {}

    /** Signal 计算规则引擎。 */
    public SignalResult computeSignal(List<CheckResult> results) {
        // blocked: 任何确定性检查失败
        for (CheckResult r : results) {
            if (!r.passed()) {
                return new SignalResult("blocked", r.check() + " 检查失败");
            }
        }

        // Check for warnings
        boolean hasWarnings = results.stream()
            .anyMatch(r -> r.warnings() != null && !r.warnings().isEmpty());
        if (hasWarnings) {
            return new SignalResult("review_recommended", "存在 lint 警告");
        }

        // Check for test coverage
        boolean hasTests = results.stream()
            .anyMatch(r -> "test_match".equals(r.check()));
        if (!hasTests) {
            return new SignalResult("review_recommended", "无匹配的测试文件");
        }

        // All pass
        return new SignalResult("auto_approve", "全部验证通过");
    }

    /** Push verification result via WebSocket. */
    public void pushVerificationResult(String username, String sessionId, RunChecksResponse response) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "verification_result");
        message.put("sessionId", sessionId);
        message.put("operationId", response.operationId());
        message.put("result", response);
        message.put("timestamp", java.time.Instant.now().toString());

        messaging.convertAndSendToUser(username, "/queue/messages", message);
        log.info("Pushed verification_result for operation {} to user {}", response.operationId(), username);
    }
}
