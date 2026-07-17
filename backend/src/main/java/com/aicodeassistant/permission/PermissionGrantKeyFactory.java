package com.aicodeassistant.permission;

import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/** Fail-closed stable keys for the first grant whitelist. */
@Component
public class PermissionGrantKeyFactory {
    public Optional<GrantKey> create(Tool tool, ToolInput input, String cwd, String riskClass) {
        if (tool == null || cwd == null) return Optional.empty();
        String toolName = tool.getName();
        try {
            Path canonicalCwd = Path.of(cwd).toRealPath();
            if ("Bash".equals(toolName) || "BashTool".equals(toolName)) {
                String command = input.getOptionalString("command").orElse(null);
                if (command == null || command.isBlank() || "high".equalsIgnoreCase(riskClass)) return Optional.empty();
                // Grant Key 基于 工具+动作+风险级别+工作目录，不含具体命令
                // 这样同目录下同风险级别的命令共享一个 Grant
                String descriptor = toolName + '\n' + "execute" + '\n' + riskClass + '\n' + canonicalCwd;
                return Optional.of(new GrantKey(toolName, "execute", riskClass,
                        sha256(descriptor.getBytes(StandardCharsets.UTF_8)), null,
                        canonicalCwd.toString(), false));
            }
            if (toolName.matches("File(Read|Write|Edit)(Tool)?")) {
                String raw = tool.getPath(input);
                if (raw == null) return Optional.empty();
                Path target = Path.of(raw);
                if (!target.isAbsolute()) target = canonicalCwd.resolve(target);
                Path canonical;
                if (Files.exists(target)) canonical = target.toRealPath();
                else {
                    Path parent = target.getParent();
                    if (parent == null || !Files.exists(parent)) return Optional.empty();
                    canonical = parent.toRealPath().resolve(target.getFileName()).normalize();
                }
                if (!canonical.startsWith(canonicalCwd)) return Optional.empty();
                String action = tool.isReadOnly(input) ? "read" : "write";
                String relative = canonicalCwd.relativize(canonical).toString();
                String extension = extensionOf(canonical.getFileName().toString());
                String exactDescriptor = toolName + '\n' + action + '\n' + relative;
                String workspaceDescriptor = toolName + '\n' + action + '\n'
                        + canonicalCwd + '\n' + extension;
                return Optional.of(new GrantKey(toolName, action, riskClass,
                        sha256(exactDescriptor.getBytes(StandardCharsets.UTF_8)),
                        sha256(workspaceDescriptor.getBytes(StandardCharsets.UTF_8)),
                        canonicalCwd.toString(), true));
            }
            // 网络工具（WebBrowser, WebFetch, WebSearch 等）：基于 action 类型生成 Grant Key
            if (toolName.matches("Web(Browser|Fetch|Search)(Tool)?")) {
                String action = input.getOptionalString("action").orElse("");
                if (action.isBlank()) {
                    action = "default";
                }
                String descriptor = toolName + '\n' + action + '\n' + riskClass;
                return Optional.of(new GrantKey(toolName, action, riskClass,
                        sha256(descriptor.getBytes(StandardCharsets.UTF_8)), null,
                        canonicalCwd.toString(), false));
            }
            return Optional.empty();
        } catch (Exception e) { return Optional.empty(); }
    }
    public String workspaceHash(String canonicalCwd) { return sha256(canonicalCwd.getBytes(StandardCharsets.UTF_8)); }
    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot).toLowerCase(java.util.Locale.ROOT);
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    public record GrantKey(String toolName, String action, String riskClass,
                           String hash, String workspaceGrantHash,
                           String canonicalCwd, boolean workspaceAllowed) {
        public String hashForScope(String scope) {
            if ("WORKSPACE".equals(scope)) {
                if (!workspaceAllowed || workspaceGrantHash == null) {
                    throw new IllegalArgumentException("WORKSPACE_SCOPE_UNSUPPORTED");
                }
                return workspaceGrantHash;
            }
            return hash;
        }
    }
}
