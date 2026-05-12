package com.aicodeassistant.service;

import com.aicodeassistant.model.RunChecksRequest;
import com.aicodeassistant.model.RunChecksResponse;
import com.aicodeassistant.model.RunChecksResponse.CheckResult;
import com.aicodeassistant.model.RunChecksResponse.CheckIssue;
import com.aicodeassistant.model.dto.*;
import com.aicodeassistant.websocket.WebSocketController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * 确定性验证服务 — Phase 2 增强版。
 * <p>
 * 功能:
 * <ul>
 *   <li>每文件独立执行 TypeScript / ESLint / Vitest 检查</li>
 *   <li>异步调用 Python /api/analysis/change-impact 获取 heuristic</li>
 *   <li>计算 Signal（auto_approve / review_recommended / manual_required / blocked）</li>
 *   <li>通过 WebSocket 推送 verify_progress 和 verification_result</li>
 * </ul>
 */
@Service
public class VerifyCheckService {
    private static final Logger log = LoggerFactory.getLogger(VerifyCheckService.class);
    private static final int DEFAULT_TIMEOUT_MS = 60_000;
    private static final int HEURISTIC_TIMEOUT_MS = 500;

    private final WebSocketController webSocketController;
    private final PythonCapabilityAwareClient pythonClient;

    public VerifyCheckService(@Lazy WebSocketController webSocketController,
                              PythonCapabilityAwareClient pythonClient) {
        this.webSocketController = webSocketController;
        this.pythonClient = pythonClient;
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase 2: executeChecks — 每文件独立检查
    // ═══════════════════════════════════════════════════════════════

    /**
     * 执行 Phase 2 验证检查。
     */
    public VerifyCheckResponse executeChecks(VerifyCheckRequest request, String workspacePath) {
        long startTime = System.currentTimeMillis();
        List<String> filePaths = request.filePaths();
        List<String> checks = request.checks();

        // 异步获取 heuristic（500ms 超时）
        CompletableFuture<HeuristicAnalysis> heuristicFuture = CompletableFuture.supplyAsync(() ->
            fetchHeuristic(filePaths, workspacePath)
        );

        // 逐文件执行检查
        List<FileCheckResult> fileResults = new ArrayList<>();
        for (int i = 0; i < filePaths.size(); i++) {
            String filePath = filePaths.get(i);
            FileCheckResult result = checkSingleFile(filePath, checks, workspacePath);
            fileResults.add(result);

            // 推送 verify_progress
            pushVerifyProgress(request.sessionId(), filePath, i + 1, filePaths.size(), result);
        }

        // 等待 heuristic（超时则降级）
        HeuristicAnalysis heuristic;
        try {
            heuristic = heuristicFuture.get(HEURISTIC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Heuristic analysis unavailable (timeout/error): {}", e.getMessage());
            heuristic = HeuristicAnalysis.unavailable();
        }

        // 计算 Signal
        String signal = computeSignal(fileResults, heuristic);
        String signalReason = computeSignalReason(fileResults, heuristic, signal);

        // 计算 overallStatus
        String overallStatus = computeOverallStatus(fileResults);

        long duration = System.currentTimeMillis() - startTime;

        return new VerifyCheckResponse(
                fileResults, heuristic, signal, signalReason,
                overallStatus, duration, Instant.now().toString()
        );
    }

    /**
     * 单文件检查 — 依次执行 TypeScript、ESLint、Vitest。
     */
    private FileCheckResult checkSingleFile(String filePath, List<String> checks, String workspacePath) {
        CheckDetail tsResult = checks.contains("typescript")
                ? runTypescriptCheck(filePath, workspacePath)
                : CheckDetail.skipped();

        CheckDetail eslintResult = checks.contains("eslint")
                ? runEslintCheck(filePath, workspacePath)
                : CheckDetail.skipped();

        TestCheckDetail vitestResult = checks.contains("vitest")
                ? runVitestCheck(filePath, workspacePath)
                : TestCheckDetail.skipped();

        return new FileCheckResult(filePath, tsResult, eslintResult, vitestResult);
    }

    // ═══════════════════════════════════════════════════════════════
    // 工具执行方法
    // ═══════════════════════════════════════════════════════════════

    private CheckDetail runTypescriptCheck(String filePath, String workspacePath) {
        try {
            ProcessResult result = executeProcess(workspacePath, DEFAULT_TIMEOUT_MS,
                    "npx", "tsc", "--noEmit", "--pretty", "false", filePath);

            if (result.exitCode() == 127) {
                return CheckDetail.skipped();
            }

            if (result.exitCode() == 0) {
                return new CheckDetail("pass", 0, 0, List.of());
            }

            // 解析错误
            List<com.aicodeassistant.model.dto.CheckIssue> issues = parseTypescriptErrors(result.output());
            int errorCount = issues.size();
            return new CheckDetail("fail", errorCount, 0, issues);

        } catch (Exception e) {
            log.error("TypeScript check failed for {}: {}", filePath, e.getMessage());
            return new CheckDetail("fail", 1, 0,
                    List.of(new com.aicodeassistant.model.dto.CheckIssue(0, 0, null, "error", e.getMessage(), null)));
        }
    }

    private CheckDetail runEslintCheck(String filePath, String workspacePath) {
        try {
            ProcessResult result = executeProcess(workspacePath, DEFAULT_TIMEOUT_MS,
                    "npx", "eslint", "--format", "json", filePath);

            if (result.exitCode() == 127) {
                return CheckDetail.skipped();
            }

            if (result.exitCode() == 0) {
                return new CheckDetail("pass", 0, 0, List.of());
            }

            // Parse JSON output for error/warning counts
            var issues = parseEslintOutput(result.output());
            int errorCount = (int) issues.stream()
                    .filter(i -> "error".equals(i.severity())).count();
            int warningCount = (int) issues.stream()
                    .filter(i -> "warning".equals(i.severity())).count();
            String status = errorCount > 0 ? "fail" : "pass";
            return new CheckDetail(status, errorCount, warningCount, issues);

        } catch (Exception e) {
            log.error("ESLint check failed for {}: {}", filePath, e.getMessage());
            return new CheckDetail("fail", 1, 0,
                    List.of(new com.aicodeassistant.model.dto.CheckIssue(0, 0, null, "error", e.getMessage(), null)));
        }
    }

    private TestCheckDetail runVitestCheck(String filePath, String workspacePath) {
        try {
            ProcessResult result = executeProcess(workspacePath, DEFAULT_TIMEOUT_MS,
                    "npx", "vitest", "run", "--reporter=json", filePath);

            if (result.exitCode() == 127) {
                return TestCheckDetail.skipped();
            }

            // No matching tests
            if (result.output().contains("No test files found")) {
                return new TestCheckDetail("no_tests", 0, 0, null, List.of());
            }

            if (result.exitCode() == 0) {
                return new TestCheckDetail("pass", 1, 0, null, List.of());
            }

            // Parse failures
            List<TestCheckDetail.TestFailure> failures = parseVitestFailures(result.output());
            return new TestCheckDetail("fail", 0, failures.size(), null, failures);

        } catch (Exception e) {
            log.error("Vitest check failed for {}: {}", filePath, e.getMessage());
            return new TestCheckDetail("fail", 0, 1, null,
                    List.of(new TestCheckDetail.TestFailure("unknown", e.getMessage())));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Heuristic（Python 调用）
    // ═══════════════════════════════════════════════════════════════

    private HeuristicAnalysis fetchHeuristic(List<String> filePaths, String workspacePath) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "filePaths", filePaths,
                    "workingDirectory", workspacePath
            );
            var result = pythonClient.callIfAvailable(
                    "CODE_INTEL", "/api/analysis/change-impact",
                    requestBody, HeuristicAnalysis.class);
            return result.orElse(HeuristicAnalysis.unavailable());
        } catch (Exception e) {
            log.warn("Heuristic fetch failed: {}", e.getMessage());
            return HeuristicAnalysis.unavailable();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Signal 计算
    // ═══════════════════════════════════════════════════════════════

    /**
     * Signal 计算规则引擎。
     */
    private String computeSignal(List<FileCheckResult> results, HeuristicAnalysis heuristic) {
        // blocked：任何 TS error / ESLint error / Vitest failure / heuristic.truncated
        for (FileCheckResult r : results) {
            if (r.typescript().errorCount() > 0) return "blocked";
            if (r.eslint().errorCount() > 0) return "blocked";
            if (r.vitest().failedCount() > 0) return "blocked";
        }
        if (heuristic.truncated()) return "blocked";

        // review_recommended：有风险
        boolean hasWarnings = results.stream().anyMatch(r -> r.eslint().warningCount() > 0);
        boolean noTests = results.stream().anyMatch(r -> "no_tests".equals(r.vitest().status()));
        if (heuristic.affectedApiCount() > 0) return "review_recommended";
        if (heuristic.indirectImpactCount() > 3) return "review_recommended";
        if (noTests) return "review_recommended";
        if (hasWarnings) return "review_recommended";

        // auto_approve：全通过+影响小
        boolean allPassed = results.stream().allMatch(r ->
            "pass".equals(r.typescript().status()) &&
            "pass".equals(r.eslint().status()) &&
            ("pass".equals(r.vitest().status()) || "no_tests".equals(r.vitest().status()))
        );
        if (allPassed && heuristic.indirectImpactCount() <= 2 && heuristic.affectedApiCount() == 0) {
            return "auto_approve";
        }

        return "manual_required";
    }

    private String computeSignalReason(List<FileCheckResult> results, HeuristicAnalysis heuristic, String signal) {
        return switch (signal) {
            case "blocked" -> {
                for (FileCheckResult r : results) {
                    if (r.typescript().errorCount() > 0) yield "TypeScript 编译错误";
                    if (r.eslint().errorCount() > 0) yield "ESLint 错误";
                    if (r.vitest().failedCount() > 0) yield "测试失败";
                }
                yield heuristic.truncated() ? "启发式分析不可用" : "验证失败";
            }
            case "review_recommended" -> {
                if (heuristic.affectedApiCount() > 0) yield "影响 API 接口";
                if (heuristic.indirectImpactCount() > 3) yield "间接影响范围大";
                if (results.stream().anyMatch(r -> "no_tests".equals(r.vitest().status())))
                    yield "缺少测试覆盖";
                yield "存在 lint 警告";
            }
            case "auto_approve" -> "全部验证通过，影响范围小";
            default -> "需要人工审查";
        };
    }

    private String computeOverallStatus(List<FileCheckResult> results) {
        boolean anyFail = results.stream().anyMatch(r ->
                "fail".equals(r.typescript().status()) ||
                "fail".equals(r.eslint().status()) ||
                "fail".equals(r.vitest().status()));
        if (anyFail) return "fail";

        boolean anyPartial = results.stream().anyMatch(r ->
                "skipped".equals(r.typescript().status()) ||
                "skipped".equals(r.eslint().status()) ||
                "skipped".equals(r.vitest().status()));
        if (anyPartial) return "partial";

        return "pass";
    }

    // ═══════════════════════════════════════════════════════════════
    // WebSocket 推送
    // ═══════════════════════════════════════════════════════════════

    private void pushVerifyProgress(String sessionId, String filePath,
                                     int completed, int total, FileCheckResult result) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("filePath", filePath);
            payload.put("completed", completed);
            payload.put("total", total);
            payload.put("result", result);
            webSocketController.pushToUser(sessionId, "verify_progress", payload);
        } catch (Exception e) {
            log.debug("Failed to push verify_progress: {}", e.getMessage());
        }
    }

    /**
     * 推送 verification_result 到前端。
     */
    public void pushVerificationResult(String sessionId, VerifyCheckResponse response) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("signal", response.signal());
            payload.put("signalReason", response.signalReason());
            payload.put("overallStatus", response.overallStatus());
            payload.put("duration", response.duration());
            payload.put("fileCount", response.results().size());
            payload.put("timestamp", response.timestamp());
            webSocketController.pushToUser(sessionId, "verification_result", payload);
            log.info("Pushed verification_result for session {} (signal={})", sessionId, response.signal());
        } catch (Exception e) {
            log.debug("Failed to push verification_result: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Legacy 兼容方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 旧版 runChecks — 向后兼容。
     */
    public RunChecksResponse runLegacyChecks(RunChecksRequest request, String workspacePath) {
        long startTime = System.currentTimeMillis();
        int timeout = Math.min(request.effectiveTimeout(), DEFAULT_TIMEOUT_MS);

        List<CompletableFuture<CheckResult>> futures = new ArrayList<>();

        for (String check : request.checks()) {
            futures.add(CompletableFuture.supplyAsync(() ->
                executeLegacyCheck(check, request.filePaths(), workspacePath, timeout)
            ));
        }

        List<CheckResult> results = new ArrayList<>();
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(timeout, TimeUnit.MILLISECONDS);
            for (CompletableFuture<CheckResult> f : futures) {
                results.add(f.get());
            }
        } catch (TimeoutException e) {
            log.warn("Legacy verification timed out after {}ms for operation {}", timeout, request.operationId());
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
            log.error("Legacy verification failed for operation {}", request.operationId(), e);
            results.add(new CheckResult("error", false,
                List.of(new CheckIssue("", 0, 0, e.getMessage(), null)),
                List.of(), System.currentTimeMillis() - startTime));
        }

        SignalResult signalResult = computeLegacySignal(results);
        return RunChecksResponse.create(request.operationId(), results, signalResult.signal(), signalResult.reason());
    }

    private CheckResult executeLegacyCheck(String check, List<String> filePaths, String workspacePath, int timeoutMs) {
        long start = System.currentTimeMillis();
        try {
            return switch (check) {
                case "typescript" -> runLegacyProcess("typescript", workspacePath, timeoutMs,
                        "npx", "tsc", "--noEmit", "--incremental");
                case "eslint" -> {
                    List<String> cmd = new ArrayList<>(List.of("npx", "eslint", "--format", "json"));
                    cmd.addAll(filePaths);
                    yield runLegacyProcess("eslint", workspacePath, timeoutMs, cmd.toArray(new String[0]));
                }
                case "test_match" -> runLegacyProcess("test_match", workspacePath, timeoutMs,
                        "npx", "vitest", "run", "--reporter=json", "--bail");
                case "build" -> runLegacyProcess("build", workspacePath, timeoutMs,
                        "npx", "vite", "build", "--mode", "production");
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

    private CheckResult runLegacyProcess(String checkName, String workDir, int timeoutMs, String... command) {
        long start = System.currentTimeMillis();
        try {
            ProcessResult pr = executeProcess(workDir, timeoutMs, command);
            long duration = System.currentTimeMillis() - start;

            if (pr.timedOut()) {
                return new CheckResult(checkName, false,
                    List.of(new CheckIssue("", 0, 0, "Process timed out after " + timeoutMs + "ms", null)),
                    List.of(), duration);
            }

            boolean passed = pr.exitCode() == 0;
            List<CheckIssue> errors = new ArrayList<>();
            if (!passed) {
                errors.add(new CheckIssue("", 0, 0, pr.output().trim(), null));
            }
            return new CheckResult(checkName, passed, errors, List.of(), duration);
        } catch (Exception e) {
            return new CheckResult(checkName, false,
                List.of(new CheckIssue("", 0, 0, "Failed to execute: " + e.getMessage(), null)),
                List.of(), System.currentTimeMillis() - start);
        }
    }

    /** Legacy Signal 计算。 */
    public record SignalResult(String signal, String reason) {}

    private SignalResult computeLegacySignal(List<CheckResult> results) {
        for (CheckResult r : results) {
            if (!r.passed()) {
                return new SignalResult("blocked", r.check() + " 检查失败");
            }
        }
        boolean hasWarnings = results.stream()
            .anyMatch(r -> r.warnings() != null && !r.warnings().isEmpty());
        if (hasWarnings) {
            return new SignalResult("review_recommended", "存在 lint 警告");
        }
        boolean hasTests = results.stream().anyMatch(r -> "test_match".equals(r.check()));
        if (!hasTests) {
            return new SignalResult("review_recommended", "无匹配的测试文件");
        }
        return new SignalResult("auto_approve", "全部验证通过");
    }

    // ═══════════════════════════════════════════════════════════════
    // 进程执行工具
    // ═══════════════════════════════════════════════════════════════

    private record ProcessResult(int exitCode, String output, boolean timedOut) {}

    private ProcessResult executeProcess(String workDir, int timeoutMs, String... command) {
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
            if (!completed) {
                process.destroyForcibly();
                return new ProcessResult(-1, output.toString(), true);
            }
            return new ProcessResult(process.exitValue(), output.toString(), false);
        } catch (Exception e) {
            return new ProcessResult(-1, e.getMessage(), false);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 输出解析
    // ═══════════════════════════════════════════════════════════════

    private List<com.aicodeassistant.model.dto.CheckIssue> parseTypescriptErrors(String output) {
        List<com.aicodeassistant.model.dto.CheckIssue> issues = new ArrayList<>();
        if (output == null || output.isBlank()) return issues;

        for (String line : output.split("\n")) {
            // Format: file(line,col): error TSxxxx: message
            if (line.contains("): error ") || line.contains("): warning ")) {
                String severity = line.contains("): error ") ? "error" : "warning";
                issues.add(new com.aicodeassistant.model.dto.CheckIssue(
                        0, 0, null, severity, line.trim(), null));
            }
        }
        if (issues.isEmpty() && !output.isBlank()) {
            issues.add(new com.aicodeassistant.model.dto.CheckIssue(
                    0, 0, null, "error", output.trim().substring(0, Math.min(output.trim().length(), 500)), null));
        }
        return issues;
    }

    private List<com.aicodeassistant.model.dto.CheckIssue> parseEslintOutput(String output) {
        List<com.aicodeassistant.model.dto.CheckIssue> issues = new ArrayList<>();
        if (output == null || output.isBlank()) return issues;

        // Simplified parsing — in production would use Jackson to parse JSON
        // For now, create a single error entry from the raw output
        if (output.contains("\"severity\":2") || output.contains("\"severity\": 2")) {
            issues.add(new com.aicodeassistant.model.dto.CheckIssue(
                    0, 0, null, "error", "ESLint errors detected", null));
        }
        if (output.contains("\"severity\":1") || output.contains("\"severity\": 1")) {
            issues.add(new com.aicodeassistant.model.dto.CheckIssue(
                    0, 0, null, "warning", "ESLint warnings detected", null));
        }
        if (issues.isEmpty() && !output.isBlank()) {
            issues.add(new com.aicodeassistant.model.dto.CheckIssue(
                    0, 0, null, "error", output.trim().substring(0, Math.min(output.trim().length(), 500)), null));
        }
        return issues;
    }

    private List<TestCheckDetail.TestFailure> parseVitestFailures(String output) {
        List<TestCheckDetail.TestFailure> failures = new ArrayList<>();
        if (output == null || output.isBlank()) return failures;
        failures.add(new TestCheckDetail.TestFailure("test", output.trim().substring(0, Math.min(output.trim().length(), 500))));
        return failures;
    }
}
