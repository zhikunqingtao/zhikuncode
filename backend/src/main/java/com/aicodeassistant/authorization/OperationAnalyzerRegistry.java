package com.aicodeassistant.authorization;

import com.aicodeassistant.artifact.publication.ArtifactPublicationPolicy;
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
    private final OperationAnalyzer artifactPublish = new ArtifactPublishAnalyzer();
    private final OperationAnalyzer mcp = new GenericAnalyzer("mcp-v1");
    private final OperationAnalyzer generic = new GenericAnalyzer("static-or-remote-v1");
    private ArtifactPublicationPolicy artifactPublicationPolicy;

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

    @Autowired(required = false)
    void setArtifactPublicationPolicy(ArtifactPublicationPolicy artifactPublicationPolicy) {
        this.artifactPublicationPolicy = artifactPublicationPolicy;
    }

    public OperationAnalyzer analyzerFor(Tool tool) {
        if (tool.isMcp()) return mcp;
        // 名称看似 MCP 但没有适配器身份的动态工具仍按未知工具处理，不能继承 MCP 持久授权。
        if (tool.getName().startsWith("mcp__")) return generic;
        if ("Bash".equals(tool.getName())) return bash;
        // Bash 语法无法证明 PowerShell 的语义，因此 PowerShell 保持精确 ONCE 授权。
        if ("PowerShell".equals(tool.getName())) return generic;
        if ("PublishArtifact".equals(tool.getName())) return artifactPublish;
        if (FILE_READ.contains(tool.getName()) || FILE_WRITE.contains(tool.getName())) return file;
        if (NETWORK.contains(tool.getName())) return network;
        if (CONTROL.contains(tool.getName()) || VERIFY_CONTROL.contains(tool.getName())
                || SAFE_INTERNAL.contains(tool.getName())) return generic;
        // 可信进程内动态工具若未显式登记，仍只能获得精确 ONCE 授权。
        return generic;
    }

    public boolean isExplicitCoreTool(String name) {
        return "Bash".equals(name) || "PowerShell".equals(name) || "PublishArtifact".equals(name)
                || FILE_READ.contains(name)
                || FILE_WRITE.contains(name) || NETWORK.contains(name) || CONTROL.contains(name)
                || VERIFY_CONTROL.contains(name) || SAFE_INTERNAL.contains(name);
    }

    /**
     * Replaces only the file resource field with the canonical target that was
     * analyzed and approved. Non-resource arguments remain byte-for-byte
     * equivalent to the frozen input, while a later symlink-alias rebound can no
     * longer redirect Tool.call() to a different file.
     */
    public ToolInput bindExecutionInput(
            Tool tool, OperationDescriptor descriptor,
            ToolInput input, AuthorizationSubject subject) {
        if (!"file-v1".equals(descriptor.analyzerId())) return input;
        ResourceRef resource = descriptor.resources().stream()
                .filter(candidate -> "path".equals(candidate.kind()))
                .findFirst().orElse(null);
        if (resource == null) return input;
        Path canonical = resource.outsideWorkspace()
                ? Path.of(resource.value())
                : subject.authorizationRoot().resolve(resource.value());
        String path = canonical.toAbsolutePath().normalize().toString();
        Map<String, Object> bound = new LinkedHashMap<>(input.getRawData());
        switch (tool.getName()) {
            case "Glob", "Grep" -> bound.put("path", path);
            case "LSP" -> {
                boolean replaced = false;
                if (bound.containsKey("filePath")) {
                    bound.put("filePath", path);
                    replaced = true;
                }
                if (bound.containsKey("file_path")) {
                    bound.put("file_path", path);
                    replaced = true;
                }
                if (!replaced) return input;
            }
            case "NotebookEdit" -> bound.put("notebook_path", path);
            default -> {
                if (!bound.containsKey("file_path")) return input;
                bound.put("file_path", path);
            }
        }
        return ToolInput.from(bound);
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
            if (parsed instanceof ParseForSecurityResult.TooComplex complex) {
                log.debug("Shell parse too complex for command, defaulting to GUARDED: {}", complex.reason());
            }
            var env = bashSecurity.analyzeEnvironmentReferences(command);
            List<String> inherited = env.inheritedReferences().stream().sorted().toList();
            RiskClass risk;
            if (tool.isDestructive(input)) {
                risk = RiskClass.HIGH;
            } else if (tool.isReadOnly(input)) {
                risk = RiskClass.SAFE;
            } else {
                risk = RiskClass.GUARDED;
            }
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
            if (parsed instanceof ParseForSecurityResult.TooComplex complex) {
                log.debug("Shell parse too complex for command, defaulting to GUARDED: {}", complex.reason());
            }
            var env = bashSecurity.analyzeEnvironmentReferences(command);
            List<String> inherited = env.inheritedReferences().stream().sorted().toList();
            RiskClass currentRisk;
            if (tool.isDestructive(input)) {
                currentRisk = RiskClass.HIGH;
            } else if (tool.isReadOnly(input)) {
                currentRisk = RiskClass.SAFE;
            } else {
                currentRisk = RiskClass.GUARDED;
            }
            Map<String, Object> authorizationInput = new LinkedHashMap<>();
            authorizationInput.put("command", command);
            authorizationInput.put("isBackground", input.getBoolean("is_background", false));
            authorizationInput.put("dynamicEnvironmentHash",
                    dynamicEnvironmentHash(inherited));
            OperationDescriptor current = descriptor(id(), tool, descriptor.inputHash(), "execute",
                    descriptor.effects(), List.of(cwdResource(cwd, subject)), inherited, List.of(),
                    currentRisk, descriptor.redactedSummary(), authorizationInput);
            List<ResourceRef> currentResources = List.of(cwdResource(cwd, subject));
            boolean riskChanged = current.risk() != descriptor.risk();
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
            String raw = rawPath(tool, input, context);
            FilePathFacts pathFacts = raw == null || raw.isBlank()
                    ? null : inspect(tool, write, raw, context, subject);
            List<ResourceRef> resources = pathFacts == null
                    ? List.of() : List.of(pathFacts.resource());
            RiskClass risk = fileRisk(write, pathFacts);
            TypedFileOperation operation = "LSP".equals(tool.getName()) && resources.isEmpty()
                    ? TypedFileOperation.LIST_DIRECTORY : fileOperation(tool.getName());
            if (resources.isEmpty() && operation == TypedFileOperation.LIST_DIRECTORY) {
                resources = List.of(new ResourceRef("path", ".", false));
            }
            return descriptor(id(), tool, frozen, operation.name(),
                    List.of(write ? EffectClass.WRITE_RESOURCE : EffectClass.READ_RESOURCE), resources,
                    List.of(), List.of(), risk,
                    fileSummary(tool, pathFacts),
                    Map.of());
        }
        @Override public void recheck(Tool tool, OperationDescriptor descriptor, ToolInput input,
                ToolUseContext context, AuthorizationSubject subject) {
            boolean write = FILE_WRITE.contains(tool.getName());
            String raw = rawPath(tool, input, context);
            FilePathFacts current = raw == null || raw.isBlank()
                    ? null : inspect(tool, write, raw, context, subject);
            List<ResourceRef> resources = current == null
                    ? List.of() : List.of(current.resource());
            TypedFileOperation operation = "LSP".equals(tool.getName())
                    && resources.isEmpty()
                    ? TypedFileOperation.LIST_DIRECTORY
                    : fileOperation(tool.getName());
            if (resources.isEmpty()
                    && operation == TypedFileOperation.LIST_DIRECTORY) {
                resources = List.of(new ResourceRef("path", ".", false));
            }
            if (!descriptor.resources().equals(
                    AuthorizationFactCanonicalizer.resources(resources))
                    || riskIncreased(fileRisk(write, current),
                            descriptor.risk())) {
                throw new AuthorizationException(
                        "AUTHORIZATION_FINAL_RECHECK_DENIED",
                        "File target or security facts changed before execution");
            }
        }

        private FilePathFacts inspect(
                Tool tool, boolean write, String raw,
                ToolUseContext context,
                AuthorizationSubject subject) {
            Path canonical;
            Path lexical;
            try {
                Path base = filePathBase(context, subject);
                canonical = pathSecurity.resolvePath(
                        raw, base.toString());
                Path requested = Path.of(raw);
                lexical = (requested.isAbsolute()
                        ? requested : base.resolve(requested))
                        .toAbsolutePath().normalize();
            } catch (IllegalArgumentException unsafePath) {
                throw new AuthorizationException(
                        "PROTECTED_PATH_DENIED",
                        unsafePath.getMessage(), unsafePath);
            }
            PathSecurityService.PathCheckResult pathCheck = pathCheck(
                    tool, write, lexical, subject);
            if (!pathCheck.isAllowed()) {
                throw new AuthorizationException(
                        "PROTECTED_PATH_DENIED", pathCheck.message());
            }
            return new FilePathFacts(
                    canonicalResource(canonical, subject),
                    pathCheck.needsConfirmation());
        }

        private PathSecurityService.PathCheckResult pathCheck(
                Tool tool, boolean write, Path absolute,
                AuthorizationSubject subject) {
            String workspace = subject.authorizationRoot().toString();
            if (write) {
                return pathSecurity.checkAuthorizedWritePermission(
                        absolute.toString(), workspace);
            }
            if ("Glob".equals(tool.getName())
                    || "Grep".equals(tool.getName())) {
                return pathSecurity
                        .checkAuthorizedRecursiveReadRootPermission(
                        absolute.toString(), workspace);
            }
            return pathSecurity.checkAuthorizedReadPermission(
                    absolute.toString(), workspace);
        }

        private String rawPath(
                Tool tool, ToolInput input,
                ToolUseContext context) {
            return switch (tool.getName()) {
                case "Glob", "Grep" -> input.getString(
                        "path", context.workingDirectory());
                case "LSP" -> first(input, "filePath", "file_path");
                default -> tool.getPath(input) != null
                        ? tool.getPath(input)
                        : first(input, "file_path", "path",
                                "notebook_path");
            };
        }

        private RiskClass fileRisk(
                boolean write, FilePathFacts facts) {
            if (facts != null && facts.sensitive()) {
                return RiskClass.HIGH;
            }
            if (write || (facts != null
                    && facts.resource().outsideWorkspace())) {
                return RiskClass.GUARDED;
            }
            return RiskClass.SAFE;
        }

        private String fileSummary(
                Tool tool, FilePathFacts facts) {
            if (facts == null) return tool.getName();
            ResourceRef resource = facts.resource();
            if (!resource.outsideWorkspace()) {
                return tool.getName() + " inside Project: "
                        + resource.value();
            }
            String value = resource.value();
            try {
                String configuredHome = System.getProperty("user.home");
                if (configuredHome == null || configuredHome.isBlank()) {
                    return tool.getName() + " outside Project: " + value;
                }
                Path home = Path.of(configuredHome)
                        .toAbsolutePath().normalize();
                Path target = Path.of(value).toAbsolutePath().normalize();
                if (target.startsWith(home)) {
                    value = "~/" + home.relativize(target)
                            .toString().replace('\\', '/');
                }
            } catch (RuntimeException ignored) {
                // Keep the already canonical absolute resource value.
            }
            return tool.getName() + " outside Project: " + value;
        }

        private boolean riskIncreased(
                RiskClass current, RiskClass authorized) {
            return riskRank(current) > riskRank(authorized);
        }

        private int riskRank(RiskClass risk) {
            return switch (risk) {
                case SAFE -> 0;
                case GUARDED -> 1;
                case HIGH -> 2;
            };
        }
    }

    private final class NetworkAnalyzer implements OperationAnalyzer {
        @Override public String id() { return "network-v1"; }
        @Override public OperationDescriptor analyze(Tool tool, FrozenToolInput frozen, ToolInput input,
                ToolUseContext context, AuthorizationSubject subject) {
            String url = first(input, "url", "uri", "endpoint");
            List<String> endpoints = url == null ? List.of() : List.of(redactEndpoint(url));
            return descriptor(id(), tool, frozen, "network", List.of(EffectClass.NETWORK), List.of(),
                    List.of(), endpoints, RiskClass.GUARDED, tool.getName() + " remote request");
        }
        @Override public void recheck(Tool tool, OperationDescriptor d, ToolInput i, ToolUseContext c, AuthorizationSubject s) { }
    }

    private final class ArtifactPublishAnalyzer implements OperationAnalyzer {
        @Override public String id() { return "artifact-publish-v1"; }

        @Override
        public OperationDescriptor analyze(Tool tool, FrozenToolInput frozen, ToolInput input,
                                           ToolUseContext context, AuthorizationSubject subject) {
            ArtifactPublicationPolicy.Snapshot snapshot = publicationSnapshot(input, context);
            return publicationDescriptor(tool, frozen.inputHash(), snapshot);
        }

        @Override
        public void recheck(Tool tool, OperationDescriptor approved, ToolInput input,
                            ToolUseContext context, AuthorizationSubject subject) {
            ArtifactPublicationPolicy.Snapshot current = publicationSnapshot(input, context);
            OperationDescriptor recomputed = publicationDescriptor(tool, approved.inputHash(), current);
            if (!approved.operationHash().equals(recomputed.operationHash())
                    || !approved.resources().equals(recomputed.resources())
                    || approved.risk() != RiskClass.HIGH) {
                throw new AuthorizationException("AUTHORIZATION_FINAL_RECHECK_DENIED",
                        "Artifact or OSS publication facts changed before execution");
            }
        }

        private ArtifactPublicationPolicy.Snapshot publicationSnapshot(ToolInput input,
                                                                        ToolUseContext context) {
            if (artifactPublicationPolicy == null) {
                throw new AuthorizationException("ARTIFACT_PUBLISH_ANALYZER_UNAVAILABLE",
                        "Artifact publication policy is unavailable");
            }
            try {
                return artifactPublicationPolicy.inspect(input.getString("file_path", ""),
                        context.currentRunId(), context.workingDirectory());
            } catch (ArtifactPublicationPolicy.ArtifactPublicationException denied) {
                throw new AuthorizationException(denied.code(),
                        "Artifact publication policy denied the request", denied);
            } catch (com.aicodeassistant.config.oss.OssPublishProperties.OssConfigurationException denied) {
                throw new AuthorizationException(denied.getMessage(),
                        "OSS publication is unavailable on this deployment", denied);
            } catch (RuntimeException denied) {
                throw new AuthorizationException("ARTIFACT_PUBLISH_POLICY_DENIED",
                        "Artifact publication policy denied the request", denied);
            }
        }

        private OperationDescriptor publicationDescriptor(Tool tool, String inputHash,
                                                           ArtifactPublicationPolicy.Snapshot snapshot) {
            String summary = "PERMANENT PUBLIC OSS upload\n"
                    + "File: " + snapshot.relativePath() + "\n"
                    + "Size: " + snapshot.size() + " bytes\n"
                    + "SHA-256: " + snapshot.sha256() + "\n"
                    + "Bucket: " + snapshot.bucket() + "\n"
                    + "Public URL: " + snapshot.publicUrl();
            return descriptor(id(), tool, inputHash, "publish-public-artifact",
                    List.of(EffectClass.READ_RESOURCE, EffectClass.NETWORK, EffectClass.WRITE_RESOURCE),
                    List.of(new ResourceRef("path", snapshot.relativePath(), false)),
                    List.of(), List.of(redactEndpoint(snapshot.endpoint())), RiskClass.HIGH,
                    summary, snapshot.authorizationFacts());
        }
    }

    private final class GenericAnalyzer implements OperationAnalyzer {
        private final String analyzerId;

        private GenericAnalyzer(String analyzerId) {
            this.analyzerId = analyzerId;
        }

        @Override public String id() { return analyzerId; }
        @Override public OperationDescriptor analyze(Tool tool, FrozenToolInput frozen, ToolInput input,
                ToolUseContext context, AuthorizationSubject subject) {
            boolean safe = SAFE_INTERNAL.contains(tool.getName()) && !tool.isMcp();
            EffectClass effect = safe ? EffectClass.SAFE_INTERNAL
                    : CONTROL.contains(tool.getName()) || VERIFY_CONTROL.contains(tool.getName())
                    ? EffectClass.CONTROL_PLANE : EffectClass.UNKNOWN;
            return descriptor(id(), tool, frozen, safe ? "internal" : "invoke", List.of(effect), List.of(),
                    List.of(), List.of(), safe ? RiskClass.SAFE : RiskClass.GUARDED,
                    safe ? tool.getName() : tool.getName() + " exact invocation");
        }
        @Override public void recheck(Tool tool, OperationDescriptor d, ToolInput i, ToolUseContext c, AuthorizationSubject s) { }
    }

    private Path filePathBase(
            ToolUseContext context,
            AuthorizationSubject subject) {
        Path base = subject.authorizationRoot();
        try {
            if (context.workingDirectory() != null) {
                Path configured = Path.of(context.workingDirectory());
                base = (configured.isAbsolute()
                        ? configured
                        : subject.authorizationRoot().resolve(configured))
                        .normalize();
            }
            return base.toAbsolutePath().normalize();
        } catch (RuntimeException invalid) {
            throw new AuthorizationException(
                    "WORKSPACE_PATH_INVALID",
                    "Invalid file working directory", invalid);
        }
    }

    private ResourceRef canonicalResource(
            Path target, AuthorizationSubject subject) {
        boolean outside = !target.startsWith(subject.authorizationRoot());
        String value = outside ? target.toString()
                : subject.authorizationRoot().relativize(target).toString().replace('\\', '/');
        return new ResourceRef("path", value.isEmpty() ? "." : value, outside);
    }

    private record FilePathFacts(
            ResourceRef resource, boolean sensitive) { }
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
    private static String redactEndpoint(String value) {
        try { URI uri = URI.create(value); return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort()); }
        catch (Exception ignored) { return "<remote-endpoint>"; }
    }

}
