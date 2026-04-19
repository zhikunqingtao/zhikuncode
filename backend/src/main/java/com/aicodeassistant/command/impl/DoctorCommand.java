package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.llm.LlmProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * /doctor — 诊断工具，检查系统各组件健康状态。
 * <p>
 * 返回 JSX 结构化数据，由前端 DiagnosticPanel 渲染。
 *
 * @see <a href="SPEC §3.3.4a.7">/doctor 命令</a>
 */
@Component
public class DoctorCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(DoctorCommand.class);

    private final LlmProviderRegistry providerRegistry;

    public DoctorCommand(LlmProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    @Override public String getName() { return "doctor"; }
    @Override public String getDescription() { return "Run diagnostics on your setup"; }
    @Override public CommandType getType() { return CommandType.LOCAL_JSX; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        List<Map<String, Object>> checks = new ArrayList<>();

        // 1. Java 运行时
        String javaVersion = System.getProperty("java.version");
        checks.add(buildCheck("runtime", "Java Version", javaVersion,
            Runtime.version().feature() >= 21 ? "ok" : "warn",
            Runtime.version().feature() >= 21 ? null : "建议使用 Java 21+"));

        // 2. LLM Providers
        boolean hasProviders = providerRegistry.hasProviders();
        checks.add(buildCheck("llm", "LLM Providers",
            hasProviders ? "已注册" : "未注册",
            hasProviders ? "ok" : "error",
            hasProviders ? null : "请配置 LLM API Key"));

        // 3. Working Directory
        String workDir = context.workingDir();
        boolean validDir = workDir != null && !workDir.isBlank();
        checks.add(buildCheck("env", "Working Directory",
            validDir ? workDir : "未设置",
            validDir ? "ok" : "error", null));

        // 4. Authentication
        checks.add(buildCheck("auth", "Authentication",
            context.isAuthenticated() ? "已认证" : "未认证",
            context.isAuthenticated() ? "ok" : "warn",
            context.isAuthenticated() ? null : "部分功能可能受限"));

        // 5. Session
        boolean hasSession = context.sessionId() != null;
        checks.add(buildCheck("session", "Active Session",
            hasSession ? context.sessionId() : "无活跃会话",
            hasSession ? "ok" : "warn", null));

        // 6. Git
        boolean gitAvailable = checkGitAvailable();
        checks.add(buildCheck("tool", "Git",
            gitAvailable ? "可用" : "未找到",
            gitAvailable ? "ok" : "warn",
            gitAvailable ? null : "安装 Git 以启用版本控制功能"));

        // 7. JVM 内存
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        double memPct = (double) usedMb / maxMb * 100;
        boolean memOk = memPct < 85;
        checks.add(buildCheck("runtime", "JVM Memory",
            String.format("%dMB / %dMB (%.0f%%)", usedMb, maxMb, memPct),
            memOk ? "ok" : "warn",
            memOk ? null : "内存使用率较高，建议增加堆内存"));

        // 8. Python 服务
        checks.add(checkPythonService());

        // 9. 磁盘空间
        if (validDir) {
            java.io.File rootDir = new java.io.File(workDir);
            long freeGb = rootDir.getFreeSpace() / (1024 * 1024 * 1024);
            boolean diskOk = freeGb > 1;
            checks.add(buildCheck("env", "Disk Space",
                String.format("%d GB 可用", freeGb),
                diskOk ? "ok" : "warn",
                diskOk ? null : "磁盘空间不足，建议清理"));
        }

        // 汇总统计
        long okCount = checks.stream().filter(c -> "ok".equals(c.get("status"))).count();
        long warnCount = checks.stream().filter(c -> "warn".equals(c.get("status"))).count();
        long errCount = checks.stream().filter(c -> "error".equals(c.get("status"))).count();

        return CommandResult.jsx(Map.of(
            "action", "diagnosticReport",
            "checks", checks,
            "summary", Map.of(
                "ok", okCount,
                "warn", warnCount,
                "error", errCount,
                "total", checks.size()
            )
        ));
    }

    private Map<String, Object> buildCheck(String category, String name,
                                            String value, String status, String hint) {
        var map = new LinkedHashMap<String, Object>();
        map.put("category", category);
        map.put("name", name);
        map.put("value", value);
        map.put("status", status);
        if (hint != null) map.put("hint", hint);
        return map;
    }

    private boolean checkGitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true).start();
            return p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, Object> checkPythonService() {
        try {
            var client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(2)).build();
            var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:8000/api/health"))
                .timeout(java.time.Duration.ofSeconds(2))
                .GET().build();
            var response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() == 200;
            return buildCheck("service", "Python Service",
                ok ? "运行中" : "HTTP " + response.statusCode(),
                ok ? "ok" : "warn",
                ok ? null : "Python 服务未正常响应");
        } catch (Exception e) {
            return buildCheck("service", "Python Service",
                "未运行或不可达", "warn", "启动 python-service 以获得完整功能");
        }
    }
}
