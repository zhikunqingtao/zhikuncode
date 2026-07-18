package com.aicodeassistant.authorization;

import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.bash.BashSecurityAnalyzer;
import com.aicodeassistant.tool.bash.ShellStateManager;
import com.aicodeassistant.tool.bash.ast.ParseForSecurityResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aicodeassistant.security.SensitiveDataFilter;
import com.aicodeassistant.security.PathSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;

/** 显式、封闭的分析器注册表；未知远程或动态工具只能获得 ONCE 授权。 */
@Component
public final class OperationAnalyzerRegistry {
    private static final Logger log = LoggerFactory.getLogger(OperationAnalyzerRegistry.class);
    private static final Set<String> FILE_READ = Set.of("Read", "Glob", "Grep", "LSP", "Snip");
    private static final Set<String> FILE_WRITE = Set.of("Write", "Edit", "NotebookEdit");
    private static final Set<String> NETWORK = Set.of("WebFetch", "WebSearch", "WebBrowser");
    private static final Set<String> CONTROL = Set.of("Config", "CronCreate", "CronDelete", "Worktree",
            "Agent", "TaskCreate", "TaskUpdate", "TaskStop", "SendMessage", "Git", "Skill",
            "REPL", "Memory", "Monitor", "TerminalCapture", "Visualization", "ReadMcpResource");
    private static final Set<String> VERIFY_CONTROL = Set.of("VerifyPlanExecution", "VerifyJourney");
    private static final Set<String> SAFE_INTERNAL = Set.of("TodoWrite", "TaskList", "TaskGet", "TaskOutput",
            "AskUserQuestion", "Brief", "Sleep", "CtxInspect", "ToolSearch", "SyntheticOutput",
            "EnterPlanMode", "ExitPlanMode", "CronList", "ListMcpResources", "CodeIntel");

    private final ObjectMapper mapper;
    private final BashSecurityAnalyzer bashSecurity;
    private final SensitiveDataFilter sensitiveDataFilter;
    private final PathSecurityService pathSecurity;
    private final ShellStateManager shellState;
    private final OperationAnalyzer bash = new BashAnalyzer();
    private final OperationAnalyzer file = new FileAnalyzer();
    private final OperationAnalyzer network = new NetworkAnalyzer();
    private final OperationAnalyzer generic = new GenericAnalyzer();

    @Autowired
    public OperationAnalyzerRegistry(ObjectMapper mapper, BashSecurityAnalyzer bashSecurity,
                                     SensitiveDataFilter sensitiveDataFilter,
                                     PathSecurityService pathSecurity,
                                     ShellStateManager shellState) {
        this.mapper = mapper.copy(); this.bashSecurity = bashSecurity;
        this.sensitiveDataFilter = sensitiveDataFilter;
        this.pathSecurity = pathSecurity;
        this.shellState = shellState;
    }

    OperationAnalyzerRegistry(ObjectMapper mapper, BashSecurityAnalyzer bashSecurity,
                              SensitiveDataFilter sensitiveDataFilter,
                              PathSecurityService pathSecurity) {
        this(mapper, bashSecurity, sensitiveDataFilter, pathSecurity, new ShellStateManager());
    }

    public OperationAnalyzer analyzerFor(Tool tool) {
        if (tool.isMcp() || tool.getName().startsWith("mcp__")) return generic;
        if ("Bash".equals(tool.getName())) return bash;
        // Bash 语法无法证明 PowerShell 的语义，因此 PowerShell 保持精确 ONCE 授权。
        if ("PowerShell".equals(tool.getName())) return generic;
        if (FILE_READ.contains(tool.getName()) || FILE_WRITE.contains(tool.getName())) return file;
        if (NETWORK.contains(tool.getName())) return network;
        if (CONTROL.contains(tool.getName()) || VERIFY_CONTROL.contains(tool.getName())
                || SAFE_INTERNAL.contains(tool.getName())) return generic;
        // 可信进程内动态工具若未显式登记，仍只能获得精确 ONCE 授权。
        return generic;
    }

    public boolean isExplicitCoreTool(String name) {
        return "Bash".equals(name) || "PowerShell".equals(name) || FILE_READ.contains(name)
                || FILE_WRITE.contains(name) || NETWORK.contains(name) || CONTROL.contains(name)
                || VERIFY_CONTROL.contains(name) || SAFE_INTERNAL.contains(name);
    }

    private OperationDescriptor descriptor(String analyzer, Tool tool, FrozenToolInput frozen,
            String action, List<EffectClass> effects, List<ResourceRef> resources,
            List<String> environment, List<String> endpoints, RiskClass risk, String summary) {
        return descriptor(analyzer, tool, frozen.inputHash(), action, effects, resources, environment,
                endpoints, risk, summary, Map.of("inputHash", frozen.inputHash()));
    }

    /**
     * 根据分析器拥有的语义输入事实构建稳定授权身份。
     * 完整规范输入哈希仍保留在描述符中，用于执行完整性和交互关联；纯展示字段或运行参数
     * 不应导致语义相同的授权无法复用。
     */
    private OperationDescriptor descriptor(String analyzer, Tool tool, FrozenToolInput frozen,
            String action, List<EffectClass> effects, List<ResourceRef> resources,
            List<String> environment, List<String> endpoints, RiskClass risk, String summary,
            Map<String, Object> authorizationInput) {
        return descriptor(analyzer, tool, frozen.inputHash(), action, effects, resources, environment,
                endpoints, risk, summary, authorizationInput);
    }

    private OperationDescriptor descriptor(String analyzer, Tool tool, String inputHash,
            String action, List<EffectClass> effects, List<ResourceRef> resources,
            List<String> environment, List<String> endpoints, RiskClass risk, String summary,
            Map<String, Object> authorizationInput) {
        // 哈希与描述符共享同一份规范化事实，避免 List 顺序在 record 构造后变化导致最终复检误拒绝。
        List<EffectClass> canonicalEffects = AuthorizationFactCanonicalizer.effects(effects);
        List<ResourceRef> canonicalResources = AuthorizationFactCanonicalizer.resources(resources);
        List<String> canonicalEnvironment = AuthorizationFactCanonicalizer.strings(environment);
        List<String> canonicalEndpoints = AuthorizationFactCanonicalizer.strings(endpoints);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("schema", 1); facts.put("tool", tool.getName()); facts.put("action", action);
        facts.put("authorizationInput", authorizationInput); facts.put("analyzer", analyzer);
        facts.put("effects", canonicalEffects); facts.put("resources", canonicalResources);
        facts.put("environment", canonicalEnvironment); facts.put("endpoints", canonicalEndpoints);
        facts.put("risk", risk);
        return new OperationDescriptor(1, tool.getName(), action, inputHash, analyzer,
                canonicalEffects, canonicalResources, canonicalEnvironment, canonicalEndpoints,
                risk, OperationHashing.hash(mapper, facts), summary);
    }

    private final class BashAnalyzer implements OperationAnalyzer {
        @Override public String id() { return "bash-v2"; }
        @Override public OperationDescriptor analyze(Tool tool, FrozenToolInput frozen, ToolInput input,
                ToolUseContext context, AuthorizationSubject subject) {
            String command = input.getString("command", "");
            Path cwd = shellWorkingDirectory(context, subject);
            ParseForSecurityResult parsed = bashSecurity.parseForSecurity(command, cwd, subject.authorizationRoot());
            if (parsed instanceof ParseForSecurityResult.TooComplex denied
                    && "command-blacklist-deny".equals(denied.nodeType())) {
                throw new AuthorizationException("COMMAND_ABSOLUTELY_DENIED", denied.reason());
            }
            var env = bashSecurity.analyzeEnvironmentReferences(command);
            List<String> inherited = env.inheritedReferences().stream().sorted().toList();
            boolean simple = parsed instanceof ParseForSecurityResult.Simple
                    && !env.requiresConservativeAsk() && env.sensitiveInheritedReferences().isEmpty()
                    && inherited.stream().allMatch(bashSecurity::isAllowedInheritedEnvironmentReference);
            RiskClass risk = simple ? (tool.isReadOnly(input) ? RiskClass.GUARDED : RiskClass.HIGH) : RiskClass.HIGH;
            List<EffectClass> effects = tool.isReadOnly(input)
                    ? List.of(EffectClass.PROCESS, EffectClass.READ_RESOURCE)
                    : List.of(EffectClass.PROCESS, EffectClass.WRITE_RESOURCE);
            Map<String, Object> authorizationInput = new LinkedHashMap<>();
            authorizationInput.put("command", command);
            authorizationInput.put("isBackground", input.getBoolean("is_background", false));
            authorizationInput.put("dynamicEnvironmentHash",
                    dynamicEnvironmentHash(inherited));
            return descriptor(id(), tool, frozen, "execute", effects, List.of(cwdResource(cwd, subject)), inherited,
                    List.of(), risk, sensitiveDataFilter.filter(redactCommand(command)), authorizationInput);
        }
        @Override public void recheck(Tool tool, OperationDescriptor descriptor, ToolInput input,
                ToolUseContext context, AuthorizationSubject subject) {
            String command = input.getString("command", "");
            Path cwd = shellWorkingDirectory(context, subject);
            ParseForSecurityResult parsed = bashSecurity.parseForSecurity(command, cwd, subject.authorizationRoot());
            if (parsed instanceof ParseForSecurityResult.TooComplex denied
                    && "command-blacklist-deny".equals(denied.nodeType())) {
                throw new AuthorizationException("COMMAND_ABSOLUTELY_DENIED", denied.reason());
            }
            var env = bashSecurity.analyzeEnvironmentReferences(command);
            List<String> inherited = env.inheritedReferences().stream().sorted().toList();
            boolean simple = parsed instanceof ParseForSecurityResult.Simple
                    && !env.requiresConservativeAsk() && env.sensitiveInheritedReferences().isEmpty()
                    && inherited.stream().allMatch(bashSecurity::isAllowedInheritedEnvironmentReference);
            RiskClass currentRisk = simple ? (tool.isReadOnly(input) ? RiskClass.GUARDED : RiskClass.HIGH)
                    : RiskClass.HIGH;
            Map<String, Object> authorizationInput = new LinkedHashMap<>();
            authorizationInput.put("command", command);
            authorizationInput.put("isBackground", input.getBoolean("is_background", false));
            authorizationInput.put("dynamicEnvironmentHash",
                    dynamicEnvironmentHash(inherited));
            OperationDescriptor current = descriptor(id(), tool, descriptor.inputHash(), "execute",
                    descriptor.effects(), List.of(cwdResource(cwd, subject)), inherited, List.of(),
                    currentRisk, descriptor.redactedSummary(), authorizationInput);
            List<ResourceRef> currentResources = List.of(cwdResource(cwd, subject));
            boolean riskChanged = currentRisk != descriptor.risk();
            boolean resourcesChanged = !currentResources.equals(descriptor.resources());
            boolean environmentChanged = !inherited.equals(descriptor.inheritedEnvironmentNames());
            boolean operationChanged = !current.operationHash().equals(descriptor.operationHash());
            if (riskChanged || resourcesChanged || environmentChanged || operationChanged) {
                log.info("Bash authorization facts changed before execution: riskChanged={}, "
                                + "resourcesChanged={}, environmentChanged={}, operationHashChanged={}",
                        riskChanged, resourcesChanged, environmentChanged, operationChanged);
                log.debug("Bash authorization hash mismatch: approvedHash={}, currentHash={}",
                        descriptor.operationHash(), current.operationHash());
                throw new AuthorizationException("AUTHORIZATION_FINAL_RECHECK_DENIED",
                        "Shell security facts changed before execution");
            }
        }
    }

    private Path shellWorkingDirectory(ToolUseContext context, AuthorizationSubject subject) {
        String configured = context.workingDirectory() == null
                ? subject.authorizationRoot().toString() : context.workingDirectory();
        String resolved = context.sessionId() == null ? configured
                : shellState.resolveWorkingDirectory(context.sessionId(), configured);
        try {
            Path candidate = Path.of(resolved);
            if (!candidate.isAbsolute()) candidate = subject.authorizationRoot().resolve(candidate);
            return candidate.normalize().toRealPath();
        }
        catch (Exception invalid) {
            throw new AuthorizationException("BASH_WORKING_DIRECTORY_INVALID",
                    "Shell working directory cannot be resolved", invalid);
        }
    }

    /**
     * 将可复用 Bash 授权绑定到会改变 Shell 语义的环境。
     * 继承变量由现有 Bash 解析器识别；此处只计算其有效值和 PATH 的指纹，不重复解析 Shell。
     */
    private String dynamicEnvironmentHash(List<String> inherited) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.putAll(shellState.authorizationEnvironmentFacts(inherited));
        return OperationHashing.hash(mapper, facts);
    }

    private final class FileAnalyzer implements OperationAnalyzer {
        @Override public String id() { return "file-v1"; }
        @Override public OperationDescriptor analyze(Tool tool, FrozenToolInput frozen, ToolInput input,
                ToolUseContext context, AuthorizationSubject subject) {
            boolean write = FILE_WRITE.contains(tool.getName());
            String raw = switch (tool.getName()) {
                case "Glob", "Grep" -> input.getString("path", context.workingDirectory());
                case "LSP" -> input.getString("file_path", null);
                default -> tool.getPath(input) != null ? tool.getPath(input)
                        : first(input, "file_path", "path", "notebook_path");
            };
            List<ResourceRef> resources = raw == null || raw.isBlank() ? List.of()
                    : List.of(resource(raw, context, subject));
            boolean protectedResource = false;
            if (!resources.isEmpty()) {
                ResourceRef resource = resources.getFirst();
                Path absolute = resource.outsideWorkspace()
                        ? Path.of(resource.value()).toAbsolutePath().normalize()
                        : subject.authorizationRoot().resolve(resource.value()).normalize();
                PathSecurityService.PathCheckResult pathCheck = write
                        ? pathSecurity.checkWritePermission(absolute.toString(), subject.authorizationRoot().toString())
                        : pathSecurity.checkReadPermission(absolute.toString(), subject.authorizationRoot().toString());
                if (!pathCheck.isAllowed()) {
                    throw new AuthorizationException("PROTECTED_PATH_DENIED", pathCheck.message());
                }
                protectedResource = pathCheck.needsConfirmation();
            }
            RiskClass risk = hasUnsafeRelativeSegments(raw) || protectedResource
                    || resources.stream().anyMatch(ResourceRef::outsideWorkspace)
                    ? RiskClass.HIGH : (write ? RiskClass.GUARDED : RiskClass.SAFE);
            TypedFileOperation operation = "LSP".equals(tool.getName()) && resources.isEmpty()
                    ? TypedFileOperation.LIST_DIRECTORY : fileOperation(tool.getName());
            if (resources.isEmpty() && operation == TypedFileOperation.LIST_DIRECTORY) {
                resources = List.of(new ResourceRef("path", ".", false));
            }
            return descriptor(id(), tool, frozen, operation.name(),
                    List.of(write ? EffectClass.WRITE_RESOURCE : EffectClass.READ_RESOURCE), resources,
                    List.of(), List.of(), risk, tool.getName() + " " + (raw == null ? "" : redactPath(raw)));
        }
        @Override public void recheck(Tool tool, OperationDescriptor descriptor, ToolInput input,
                ToolUseContext context, AuthorizationSubject subject) {
            boolean write = descriptor.effects().contains(EffectClass.WRITE_RESOURCE);
            for (ResourceRef ref : descriptor.resources()) {
                Path path = ref.outsideWorkspace() ? Path.of(ref.value()).toAbsolutePath().normalize()
                        : subject.authorizationRoot().resolve(ref.value()).normalize();
                PathSecurityService.PathCheckResult pathCheck = write
                        ? pathSecurity.checkWritePermission(path.toString(), subject.authorizationRoot().toString())
                        : pathSecurity.checkReadPermission(path.toString(), subject.authorizationRoot().toString());
                if (!pathCheck.isAllowed()
                        || (pathCheck.needsConfirmation() && descriptor.risk() != RiskClass.HIGH)) {
                    throw new AuthorizationException("PROTECTED_PATH_CHANGED",
                            "Resource protection policy changed before execution");
                }
                Path cursor = path;
                while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) cursor = cursor.getParent();
                if (cursor == null || Files.isSymbolicLink(cursor)) {
                    throw new AuthorizationException("WORKSPACE_PATH_SYMLINK", "Resource ancestry is unsafe");
                }
                try {
                    if (!ref.outsideWorkspace() && !cursor.toRealPath().startsWith(subject.authorizationRoot()))
                        throw new AuthorizationException("WORKSPACE_PATH_ESCAPE", "Resource ancestry escapes workspace");
                } catch (AuthorizationException denied) { throw denied; }
                catch (Exception failure) { throw new AuthorizationException("WORKSPACE_PATH_INVALID", "Resource cannot be verified", failure); }
            }
        }
    }

    private final class NetworkAnalyzer implements OperationAnalyzer {
        @Override public String id() { return "network-v1"; }
        @Override public OperationDescriptor analyze(Tool tool, FrozenToolInput frozen, ToolInput input,
                ToolUseContext context, AuthorizationSubject subject) {
            String url = first(input, "url", "uri", "endpoint");
            List<String> endpoints = url == null ? List.of() : List.of(redactEndpoint(url));
            return descriptor(id(), tool, frozen, "network", List.of(EffectClass.NETWORK), List.of(),
                    List.of(), endpoints, RiskClass.HIGH, tool.getName() + " remote request");
        }
        @Override public void recheck(Tool tool, OperationDescriptor d, ToolInput i, ToolUseContext c, AuthorizationSubject s) { }
    }

    private final class GenericAnalyzer implements OperationAnalyzer {
        @Override public String id() { return "static-or-remote-v1"; }
        @Override public OperationDescriptor analyze(Tool tool, FrozenToolInput frozen, ToolInput input,
                ToolUseContext context, AuthorizationSubject subject) {
            boolean safe = SAFE_INTERNAL.contains(tool.getName()) && !tool.isMcp();
            EffectClass effect = safe ? EffectClass.SAFE_INTERNAL
                    : CONTROL.contains(tool.getName()) || VERIFY_CONTROL.contains(tool.getName())
                    ? EffectClass.CONTROL_PLANE : EffectClass.UNKNOWN;
            return descriptor(id(), tool, frozen, safe ? "internal" : "invoke", List.of(effect), List.of(),
                    List.of(), List.of(), safe ? RiskClass.SAFE : RiskClass.HIGH,
                    safe ? tool.getName() : tool.getName() + " exact invocation");
        }
        @Override public void recheck(Tool tool, OperationDescriptor d, ToolInput i, ToolUseContext c, AuthorizationSubject s) { }
    }

    private ResourceRef resource(String raw, ToolUseContext context, AuthorizationSubject subject) {
        Path base = subject.authorizationRoot();
        if (context.workingDirectory() != null) {
            Path configured = Path.of(context.workingDirectory());
            base = (configured.isAbsolute() ? configured : subject.authorizationRoot().resolve(configured)).normalize();
        }
        Path target;
        try { target = Path.of(raw); }
        catch (Exception invalid) { throw new AuthorizationException("WORKSPACE_PATH_INVALID", "Invalid resource path"); }
        target = (target.isAbsolute() ? target : base.resolve(target)).normalize();
        boolean outside = !target.startsWith(subject.authorizationRoot());
        String value = outside ? target.toString()
                : subject.authorizationRoot().relativize(target).toString().replace('\\', '/');
        return new ResourceRef("path", value.isEmpty() ? "." : value, outside);
    }
    private static ResourceRef cwdResource(Path cwd, AuthorizationSubject subject) {
        Path normalized = cwd.toAbsolutePath().normalize();
        boolean outside = !normalized.startsWith(subject.authorizationRoot());
        String value = outside ? normalized.toString()
                : subject.authorizationRoot().relativize(normalized).toString().replace('\\', '/');
        return new ResourceRef("cwd", value.isEmpty() ? "." : value, outside);
    }

    private static String first(ToolInput input, String... names) {
        for (String name : names) if (input.has(name)) return input.getString(name, null);
        return null;
    }
    private static TypedFileOperation fileOperation(String name) {
        return switch (name) {
            case "Glob", "Grep" -> TypedFileOperation.LIST_DIRECTORY;
            case "Write" -> TypedFileOperation.REPLACE_FILE;
            case "Edit", "NotebookEdit" -> TypedFileOperation.PATCH_FILE;
            default -> TypedFileOperation.READ_FILE;
        };
    }
    private static String redactCommand(String command) {
        String compact = command.replaceAll("(?i)(token|password|secret|api[_-]?key)=\\S+", "$1=<redacted>");
        return compact.length() > 240 ? compact.substring(0, 240) + "…" : compact;
    }
    private static boolean hasUnsafeRelativeSegments(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            Path path = Path.of(raw);
            if (path.isAbsolute()) return false;
            for (Path part : path) {
                if (".".equals(part.toString()) || "..".equals(part.toString())) return true;
            }
            return false;
        } catch (Exception invalid) {
            return true;
        }
    }
    private static String redactPath(String path) { return Path.of(path).getFileName() == null ? "<path>" : "…/" + Path.of(path).getFileName(); }
    private static String redactEndpoint(String value) {
        try { URI uri = URI.create(value); return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort()); }
        catch (Exception ignored) { return "<remote-endpoint>"; }
    }

}
