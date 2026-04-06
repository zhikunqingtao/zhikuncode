package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.llm.LlmProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * /doctor — 诊断工具，检查系统各组件健康状态。
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
        StringBuilder sb = new StringBuilder();
        sb.append("Diagnostics Report:\n\n");

        // Java version
        String javaVersion = System.getProperty("java.version");
        sb.append(check("Java Version", javaVersion != null, javaVersion)).append("\n");

        // LLM Providers
        boolean hasProviders = providerRegistry.hasProviders();
        sb.append(check("LLM Providers", hasProviders,
                hasProviders ? "registered" : "none registered")).append("\n");

        // Working directory
        String workDir = context.workingDir();
        boolean validDir = workDir != null && !workDir.isBlank();
        sb.append(check("Working Directory", validDir, workDir)).append("\n");

        // Authentication
        sb.append(check("Authentication", context.isAuthenticated(),
                context.isAuthenticated() ? "authenticated" : "not authenticated")).append("\n");

        // Session
        boolean hasSession = context.sessionId() != null;
        sb.append(check("Active Session", hasSession,
                hasSession ? context.sessionId() : "none")).append("\n");

        // Git
        boolean gitAvailable = checkGitAvailable();
        sb.append(check("Git", gitAvailable,
                gitAvailable ? "available" : "not found")).append("\n");

        return CommandResult.text(sb.toString());
    }

    private String check(String name, boolean ok, String detail) {
        String status = ok ? "✓" : "✗";
        return String.format("  %s %-20s %s", status, name, detail != null ? detail : "");
    }

    private boolean checkGitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
