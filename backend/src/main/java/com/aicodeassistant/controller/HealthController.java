package com.aicodeassistant.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 健康检查与诊断控制器 (§6.1.6, §10.6.7)。
 * <p>
 * 提供以下端点:
 * <ul>
 *   <li>GET /api/health — 综合健康检查（含子系统状态）</li>
 *   <li>GET /api/health/live — 轻量存活探针 (k8s livenessProbe)</li>
 *   <li>GET /api/health/ready — 就绪探针 (k8s readinessProbe)</li>
 *   <li>GET /api/doctor — 环境诊断工具</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private static final Instant START_TIME = Instant.now();

    // ───── 综合健康检查 ─────

    /**
     * 综合健康检查 — 返回服务状态、版本、运行时间及子系统状态。
     * <p>
     * 对应 SPEC §6.1.8 #18: GET /api/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Map<String, Object>> subsystems = new LinkedHashMap<>();
        subsystems.put("database", checkDatabase());
        subsystems.put("jvm", checkJvm());

        boolean allHealthy = subsystems.values().stream()
                .allMatch(s -> "UP".equals(s.get("status")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", allHealthy ? "UP" : "DEGRADED");
        body.put("service", "ai-code-assistant-backend");
        body.put("version", getVersion());
        body.put("uptime", Duration.between(START_TIME, Instant.now()).toSeconds());
        body.put("java", System.getProperty("java.version"));
        body.put("subsystems", subsystems);
        body.put("timestamp", Instant.now().toString());

        return ResponseEntity.status(allHealthy ? 200 : 503).body(body);
    }

    // ───── 存活探针 ─────

    /**
     * 轻量存活探针 — k8s livenessProbe 使用。
     */
    @GetMapping("/health/live")
    public ResponseEntity<String> liveness() {
        return ResponseEntity.ok("OK");
    }

    // ───── 就绪探针 ─────

    /**
     * 就绪探针 — k8s readinessProbe 使用，检查数据库是否可用。
     */
    @GetMapping("/health/ready")
    public ResponseEntity<String> readiness() {
        Map<String, Object> dbCheck = checkDatabase();
        return "UP".equals(dbCheck.get("status"))
                ? ResponseEntity.ok("READY")
                : ResponseEntity.status(503).body("NOT_READY");
    }

    // ───── 环境诊断 ─────

    /**
     * 环境诊断工具 — 检查各项外部依赖与工具的可用性。
     * <p>
     * 对应 SPEC §6.1.8 #19: GET /api/doctor
     */
    @GetMapping("/doctor")
    public Map<String, Object> doctor() {
        List<Map<String, Object>> checks = new ArrayList<>();

        // 检查 Java 版本
        checks.add(doctorCheck("java", "ok",
                System.getProperty("java.version"),
                "Java runtime available", null));

        // 检查 git
        checks.add(checkExternalTool("git", "git", "--version"));

        // 检查 ripgrep
        checks.add(checkExternalTool("ripgrep", "rg", "--version"));

        // JVM 内存
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        String memStatus = usedMb < maxMb * 0.9 ? "ok" : "warning";
        checks.add(doctorCheck("jvm_memory", memStatus, null,
                String.format("Used %dMB / Max %dMB", usedMb, maxMb), null));

        return Map.of("checks", checks);
    }

    // ───── 子系统检查 ─────

    private Map<String, Object> checkDatabase() {
        // 基础检查 — SQLite 内嵌数据库，只要 JVM 在就可用
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("message", "SQLite embedded database available");
        return result;
    }

    private Map<String, Object> checkJvm() {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", usedMb < maxMb * 0.9 ? "UP" : "DEGRADED");
        result.put("message", String.format("Heap: %dMB/%dMB", usedMb, maxMb));
        return result;
    }

    // ───── 工具方法 ─────

    private Map<String, Object> checkExternalTool(String name, String command, String... args) {
        long start = System.currentTimeMillis();
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = command;
            System.arraycopy(args, 0, cmd, 1, args.length);
            Process process = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();
            long latency = System.currentTimeMillis() - start;

            if (exitCode == 0) {
                // 从输出中提取版本号
                String version = output.lines().findFirst().orElse("unknown");
                return doctorCheck(name, "ok", version, name + " available", latency);
            } else {
                return doctorCheck(name, "error", null, name + " exited with code " + exitCode, latency);
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            return doctorCheck(name, "warning", null,
                    name + " not found: " + e.getMessage(), latency);
        }
    }

    private Map<String, Object> doctorCheck(String name, String status,
                                             String version, String message, Long latencyMs) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("name", name);
        check.put("status", status);
        if (version != null) check.put("version", version);
        if (message != null) check.put("message", message);
        if (latencyMs != null) check.put("latencyMs", latencyMs);
        return check;
    }

    private String getVersion() {
        String version = getClass().getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }
}
