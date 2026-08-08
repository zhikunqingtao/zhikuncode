package com.aicodeassistant.authorization;

import com.aicodeassistant.interaction.DurableInteractionService;
import com.aicodeassistant.interaction.InteractionRequest;
import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.model.PermissionScope;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.run.RunControlService;
import com.aicodeassistant.security.SystemScratchpadPathPolicy;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolUseContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** ToolExecutionPipeline 使用的唯一策略、授权记录和交互裁决权威。 */
@Service
public final class AuthorizationService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthorizationService.class);
    private static final Path PRIVATE_TMP_ROOT = Path.of("/private/tmp")
            .toAbsolutePath().normalize();
    private final AuthorizationSubjectResolver subjects;
    private final OperationAnalyzerRegistry analyzers;
    private final PermissionGrantRepository grants;
    private final DurableInteractionService interactions;
    private final PermissionModeManager modes;
    private final RunControlService runs;
    private final ObjectMapper json;
    private final ProjectWorkspaceService projectWorkspaces;
    private final SystemScratchpadPathPolicy systemScratchpadPaths;

    @Autowired
    public AuthorizationService(AuthorizationSubjectResolver subjects, OperationAnalyzerRegistry analyzers,
            PermissionGrantRepository grants, DurableInteractionService interactions,
            PermissionModeManager modes, RunControlService runs, ObjectMapper json,
            ProjectWorkspaceService projectWorkspaces,
            SystemScratchpadPathPolicy systemScratchpadPaths) {
        this.subjects = subjects; this.analyzers = analyzers; this.grants = grants;
        this.interactions = interactions; this.modes = modes; this.runs = runs; this.json = json;
        this.projectWorkspaces = projectWorkspaces;
        this.systemScratchpadPaths = systemScratchpadPaths;
    }

    /** 保留给不经过 Spring 的既有嵌入式调用与单元测试。 */
    public AuthorizationService(AuthorizationSubjectResolver subjects, OperationAnalyzerRegistry analyzers,
            PermissionGrantRepository grants, DurableInteractionService interactions,
            PermissionModeManager modes, RunControlService runs, ObjectMapper json,
            ProjectWorkspaceService projectWorkspaces) {
        this(subjects, analyzers, grants, interactions, modes, runs, json,
                projectWorkspaces, SystemScratchpadPathPolicy.defaultPolicy());
    }

    public AuthorizedOperation authorize(Tool tool, FrozenToolInput frozen, ToolInput executionInput,
                                         ToolUseContext context) {
        return authorizePrepared(tool, frozen, executionInput, context,
                prepare(tool, frozen, executionInput, context));
    }

    public PreparedOperation prepare(Tool tool, FrozenToolInput frozen, ToolInput executionInput,
                                     ToolUseContext context) {
        String executionAttemptId = java.util.UUID.randomUUID().toString();
        AuthorizationSubject subject = subjects.resolve(context.currentRunId());
        OperationAnalyzer analyzer = analyzers.analyzerFor(tool);
        OperationDescriptor operation;
        try {
            operation = analyzer.analyze(tool, frozen, executionInput, context, subject);
            invariant(operation);
        } catch (AuthorizationException denied) {
            recordAnalysisFailure(context, subject, tool.getName(), frozen.inputHash(),
                    executionAttemptId, denied.code());
            throw denied;
        }
        return new PreparedOperation(subject, operation, executionAttemptId);
    }

    public AuthorizedOperation authorizePrepared(Tool tool, FrozenToolInput frozen, ToolInput executionInput,
                                                  ToolUseContext context, PreparedOperation prepared) {
        AuthorizationSubject subject = prepared.subject();
        OperationDescriptor operation = prepared.descriptor();
        String executionAttemptId = prepared.executionAttemptId();
        ToolInput boundExecutionInput = analyzers.bindExecutionInput(
                tool, operation, executionInput, subject);
        if (boundExecutionInput != null) {
            executionInput = boundExecutionInput;
        }

        if (operation.effects().equals(List.of(EffectClass.SAFE_INTERNAL))) {
            return new AuthorizedOperation(subject, operation, executionInput,
                    AuthorizationDiagnostic.Source.POLICY, "BUILTIN_SAFE", null, null,
                    null, executionAttemptId);
        }
        PermissionMode mode = modes.getMode(subject.rootSessionId());
        // AUTO_APPROVE 只替代人工授权。分析、不变量和 Security Hook 已在调用方完成，
        // 工具仍必须经过产物声明、最终动态复检和唯一执行网关。
        if (mode == PermissionMode.AUTO_APPROVE) {
            return new AuthorizedOperation(subject, operation, executionInput,
                    AuthorizationDiagnostic.Source.MODE, "AUTO_APPROVE", null, null,
                    null, executionAttemptId);
        }
        // HIGH 风险操作每次必须弹窗确认，不可被记住，跳过所有 Grant 匹配
        if (operation.risk() == RiskClass.HIGH) {
            if (mode == PermissionMode.PLAN) {
                recordDenial(context, subject, operation, executionAttemptId,
                        AuthorizationDiagnostic.Source.POLICY,
                        AuthorizationDiagnostic.EvaluationStage.INITIAL,
                        "PLAN_MODE_EFFECT_DENIED");
                throw new AuthorizationException("PLAN_MODE_EFFECT_DENIED",
                        "Plan mode does not permit this operation");
            }
            if (mode == PermissionMode.DONT_ASK) {
                recordDenial(context, subject, operation, executionAttemptId,
                        AuthorizationDiagnostic.Source.POLICY,
                        AuthorizationDiagnostic.EvaluationStage.INITIAL,
                        "INTERACTION_DISABLED");
                throw new AuthorizationException("PERMISSION_INTERACTION_REQUIRED",
                        "The operation needs explicit permission, but this session is non-interactive");
            }
            return interact(tool, frozen, executionInput, context, subject, operation, executionAttemptId);
        }
        PermissionGrantRepository.Match match = grants.findMatch(subject, operation);
        if (match != null) {
            return new AuthorizedOperation(subject, operation, executionInput,
                    AuthorizationDiagnostic.Source.GRANT, "GRANT_MATCH", match.grantId(), match.scope(),
                    null, executionAttemptId);
        }

        boolean scratchpadRead = operation.effects().equals(
                List.of(EffectClass.READ_RESOURCE));
        if (scratchpadRead
                && isTrustedSystemScratchpadFileOperation(
                        subject, operation)) {
            return new AuthorizedOperation(subject, operation, executionInput,
                    AuthorizationDiagnostic.Source.POLICY,
                    "SYSTEM_SCRATCHPAD_SCOPE", null, null,
                    null, executionAttemptId);
        }
        boolean privateTmpRead = operation.effects().equals(
                List.of(EffectClass.READ_RESOURCE));
        if (privateTmpRead
                && isTrustedPrivateTmpFileOperation(
                        subject, operation)) {
            return new AuthorizedOperation(subject, operation, executionInput,
                    AuthorizationDiagnostic.Source.POLICY,
                    "PRIVATE_TMP_FILE_SCOPE", null, null,
                    null, executionAttemptId);
        }
        // SAFE 读操作在所有模式下自动放行，不弹窗（读操作已统一为 SAFE 级别）
        boolean safeRead = "file-v1".equals(operation.analyzerId())
                && operation.risk() == RiskClass.SAFE
                && operation.effects().equals(List.of(EffectClass.READ_RESOURCE));
        if (safeRead) {
            return new AuthorizedOperation(subject, operation, executionInput,
                    AuthorizationDiagnostic.Source.MODE, "SAFE_READ_AUTO", null, null,
                    null, executionAttemptId);
        }
        if (mode == PermissionMode.PLAN) {
            recordDenial(context, subject, operation, executionAttemptId,
                    AuthorizationDiagnostic.Source.POLICY, AuthorizationDiagnostic.EvaluationStage.INITIAL,
                    "PLAN_MODE_EFFECT_DENIED");
            throw new AuthorizationException("PLAN_MODE_EFFECT_DENIED",
                    "Plan mode does not permit this operation");
        }
        if (!scratchpadRead
                && isTrustedSystemScratchpadFileOperation(
                        subject, operation)) {
            return new AuthorizedOperation(subject, operation, executionInput,
                    AuthorizationDiagnostic.Source.POLICY,
                    "SYSTEM_SCRATCHPAD_SCOPE", null, null,
                    null, executionAttemptId);
        }
        if (!privateTmpRead
                && isTrustedPrivateTmpFileOperation(
                        subject, operation)) {
            return new AuthorizedOperation(subject, operation, executionInput,
                    AuthorizationDiagnostic.Source.POLICY,
                    "PRIVATE_TMP_FILE_SCOPE", null, null,
                    null, executionAttemptId);
        }
        // A persisted Project selection is an existing, bounded file-scope
        // authorization. DONT_ASK disables new interactions; it does not revoke
        // an already selected Project (just as it does not bypass an existing
        // matching grant above).
        if (isTrustedProjectFileWrite(subject, operation)) {
            return new AuthorizedOperation(subject, operation, executionInput,
                    AuthorizationDiagnostic.Source.POLICY,
                    "PROJECT_FILE_SCOPE", null, null,
                    null, executionAttemptId);
        }
        if (mode == PermissionMode.DONT_ASK) {
            recordDenial(context, subject, operation, executionAttemptId,
                    AuthorizationDiagnostic.Source.POLICY, AuthorizationDiagnostic.EvaluationStage.INITIAL,
                    "INTERACTION_DISABLED");
            throw new AuthorizationException("PERMISSION_INTERACTION_REQUIRED",
                    "The operation needs explicit permission, but this session is non-interactive");
        }
        if (mode == PermissionMode.ACCEPT_EDITS && "file-v1".equals(operation.analyzerId())
                && operation.risk() != RiskClass.HIGH
                && operation.resources().stream().noneMatch(ResourceRef::outsideWorkspace)
                && operation.effects().equals(List.of(EffectClass.WRITE_RESOURCE))) {
            return new AuthorizedOperation(subject, operation, executionInput,
                    AuthorizationDiagnostic.Source.MODE, "ACCEPT_EDITS", null, null,
                    null, executionAttemptId);
        }
        return interact(tool, frozen, executionInput, context, subject, operation, executionAttemptId);
    }

    private boolean isTrustedProjectFileWrite(
            AuthorizationSubject subject,
            OperationDescriptor operation) {
        return "file-v1".equals(operation.analyzerId())
                && operation.risk() == RiskClass.GUARDED
                && operation.effects().equals(
                        List.of(EffectClass.WRITE_RESOURCE))
                && !operation.resources().isEmpty()
                && operation.resources().stream()
                        .noneMatch(ResourceRef::outsideWorkspace)
                && projectWorkspaces.isTrustedFileScope(
                        subject.authorizationRoot());
    }

    private boolean isTrustedSystemScratchpadFileOperation(
            AuthorizationSubject subject,
            OperationDescriptor operation) {
        return isTrustedOrdinaryFileOperation(
                subject, operation, systemScratchpadPaths::contains);
    }

    private boolean isTrustedPrivateTmpFileOperation(
            AuthorizationSubject subject,
            OperationDescriptor operation) {
        return isTrustedOrdinaryFileOperation(
                subject, operation,
                target -> target.startsWith(PRIVATE_TMP_ROOT));
    }

    private boolean isTrustedOrdinaryFileOperation(
            AuthorizationSubject subject,
            OperationDescriptor operation,
            Predicate<Path> containsTarget) {
        if (!"file-v1".equals(operation.analyzerId())
                || operation.risk() == RiskClass.HIGH
                || !isOrdinaryFileOperation(operation)
                || operation.resources().isEmpty()) {
            return false;
        }
        for (ResourceRef resource : operation.resources()) {
            if (!"path".equals(resource.kind())
                    || resource.value() == null
                    || resource.value().isBlank()) {
                return false;
            }
            try {
                Path value = Path.of(resource.value());
                Path target = (value.isAbsolute()
                        ? value
                        : subject.authorizationRoot().resolve(value))
                        .toAbsolutePath().normalize();
                if (!containsTarget.test(target)) {
                    return false;
                }
            } catch (RuntimeException invalidPath) {
                return false;
            }
        }
        return projectWorkspaces.isTrustedFileScope(
                subject.authorizationRoot());
    }

    private static boolean isOrdinaryFileOperation(
            OperationDescriptor operation) {
        if (operation.toolName() == null
                || operation.action() == null) {
            return false;
        }
        return switch (operation.toolName()) {
            case "Read" -> operation.action().equals(
                            TypedFileOperation.READ_FILE.name())
                    && operation.effects().equals(
                            List.of(EffectClass.READ_RESOURCE));
            case "Write" -> operation.action().equals(
                            TypedFileOperation.REPLACE_FILE.name())
                    && operation.effects().equals(
                            List.of(EffectClass.WRITE_RESOURCE));
            case "Edit", "NotebookEdit" -> operation.action().equals(
                            TypedFileOperation.PATCH_FILE.name())
                    && operation.effects().equals(
                            List.of(EffectClass.WRITE_RESOURCE));
            default -> false;
        };
    }

    public void finalDynamicRecheck(Tool tool, AuthorizedOperation authorized, ToolUseContext context) {
        invariant(authorized.descriptor());
        analyzers.analyzerFor(tool).recheck(tool, authorized.descriptor(), authorized.executionInput(), context,
                authorized.subject());
    }

    /** 必须在调用方持有的项目库有界事务内执行。 */
    public void finalGrantRecheckInCurrentTransaction(AuthorizedOperation authorized, ToolUseContext context) {
        if (authorized.grantId() != null) {
            PermissionGrantRepository.Match current = grants.findMatch(authorized.subject(), authorized.descriptor());
            if (current == null || !authorized.grantId().equals(current.grantId())) {
                throw new AuthorizationException("AUTHORIZATION_FINAL_RECHECK_DENIED",
                        "The matching permission grant is no longer valid");
            }
        } else if (authorized.source() == AuthorizationDiagnostic.Source.USER_ONCE) {
            String toolUseId = context.toolUseId() == null
                    ? authorized.descriptor().toolName() : context.toolUseId();
            try {
                interactions.requireAnsweredOnce(authorized.interactionId(), authorized.subject(),
                        authorized.descriptor(), toolUseId);
            } catch (IllegalStateException stale) {
                throw new AuthorizationException("AUTHORIZATION_FINAL_RECHECK_DENIED",
                        "The one-time permission decision is no longer valid", stale);
            }
        } else if (authorized.source()
                == AuthorizationDiagnostic.Source.POLICY
                && "PROJECT_FILE_SCOPE".equals(
                        authorized.reasonCode())
                && !isTrustedProjectFileWrite(
                        authorized.subject(),
                        authorized.descriptor())) {
            throw new AuthorizationException(
                    "AUTHORIZATION_FINAL_RECHECK_DENIED",
                    "The Project file authorization is no longer valid");
        } else if (authorized.source()
                == AuthorizationDiagnostic.Source.POLICY
                && "SYSTEM_SCRATCHPAD_SCOPE".equals(
                        authorized.reasonCode())
                && !isTrustedSystemScratchpadFileOperation(
                        authorized.subject(),
                        authorized.descriptor())) {
            throw new AuthorizationException(
                    "AUTHORIZATION_FINAL_RECHECK_DENIED",
                    "The system scratchpad authorization is no longer valid");
        } else if (authorized.source()
                == AuthorizationDiagnostic.Source.POLICY
                && "PRIVATE_TMP_FILE_SCOPE".equals(
                        authorized.reasonCode())
                && !isTrustedPrivateTmpFileOperation(
                        authorized.subject(),
                        authorized.descriptor())) {
            throw new AuthorizationException(
                    "AUTHORIZATION_FINAL_RECHECK_DENIED",
                    "The private temp file authorization is no longer valid");
        }
    }

    public void recordFinalDenial(AuthorizedOperation authorized, ToolUseContext context, String reasonCode) {
        try {
            recordDenial(context, authorized.subject(), authorized.descriptor(),
                    authorized.executionAttemptId(), AuthorizationDiagnostic.Source.INVARIANT,
                    AuthorizationDiagnostic.EvaluationStage.FINAL_RECHECK, reasonCode);
        } catch (RuntimeException diagnosticFailure) {
            log.error("Failed to persist final authorization denial: runId={}, toolUseId={}",
                    context.currentRunId(), context.toolUseId(), diagnosticFailure);
        }
    }

    private AuthorizedOperation interact(Tool tool, FrozenToolInput frozen, ToolInput executionInput, ToolUseContext context,
            AuthorizationSubject subject, OperationDescriptor operation, String executionAttemptId) {
        String toolUseId = context.toolUseId() == null ? tool.getName() : context.toolUseId();
        String correlation = "permission-v3:" + toolUseId + ":" + operation.operationHash();
        InteractionRequest request = interactions.findByCorrelationKey(context.currentRunId(), correlation);
        if (request == null) {
            List<PermissionScope> scopes = scopes(operation);
            Map<String, Object> prompt = new LinkedHashMap<>();
            prompt.put("toolName", tool.getName()); prompt.put("toolUseId", toolUseId);
            prompt.put("reason", reason(operation));
            prompt.put("riskLevel", operation.risk().name().toLowerCase());
            prompt.put("inputSummary", operation.redactedSummary());
            prompt.put("operationHash", operation.operationHash());
            List<Map<String, String>> decisionOptions = options(scopes);
            prompt.put("options", decisionOptions);
            String rememberScopeDescription = rememberScopeDescription(operation);
            if (!scopes.isEmpty() && rememberScopeDescription != null) {
                prompt.put("rememberScopeDescription", rememberScopeDescription);
            }
            AuthorizationInteractionContext authorizationContext = new AuthorizationInteractionContext(3,
                    toolUseId, executionAttemptId, frozen.inputHash(), operation.operationHash(),
                    AuthorizationInteractionContext.AuthorizationSubjectData.from(subject), operation,
                    decisionOptions.stream().map(option -> new AuthorizationInteractionContext.DecisionOption(
                            option.get("optionId"), option.get("decision"), option.get("scope"))).toList());
            request = interactions.createAuthorization(correlation, subject.rootSessionId(), context.currentRunId(),
                    prompt, List.of("allow", "deny"), scopes.stream().map(s -> s.name().toLowerCase()).toList(),
                    subject.rootRunId().equals(subject.currentRunId()) ? "direct" : "descendant", authorizationContext);
        }
        InteractionRequest.Status status = request.status() == InteractionRequest.Status.PENDING
                ? interactions.awaitTerminal(request.interactionId()).join() : request.status();
        request = interactions.findById(request.interactionId());
        if (status != InteractionRequest.Status.ANSWERED) {
            String code = switch (status) {
                case DENIED -> "PERMISSION_USER_DENIED";
                case EXPIRED -> "INTERACTION_EXPIRED";
                case CANCELLED -> "INTERACTION_CANCELLED";
                case UNDELIVERABLE -> "PERMISSION_UNDELIVERABLE";
                default -> "PERMISSION_NOT_GRANTED";
            };
            recordDenial(context, subject, operation, executionAttemptId,
                    AuthorizationDiagnostic.Source.POLICY, AuthorizationDiagnostic.EvaluationStage.INTERACTION,
                    code);
            throw new AuthorizationException(code, "Permission interaction ended with " + status);
        }
        Map<String, Object> response = response(request);
        if (!operation.operationHash().equals(response.get("operationHash")))
            throw new AuthorizationException("PERMISSION_PROTOCOL_MISMATCH", "Permission response does not match this operation");
        if (operation.risk() == RiskClass.HIGH && Boolean.TRUE.equals(response.get("remember"))) {
            throw new AuthorizationException("PERMISSION_HIGH_RISK_NOT_REMEMBERABLE",
                    "HIGH risk operations cannot be remembered");
        }
        if (Boolean.TRUE.equals(response.get("remember"))) {
            PermissionGrantRepository.Match remembered = grants.findMatch(subject, operation);
            if (remembered == null) {
                throw new AuthorizationException("PERMISSION_GRANT_NOT_COMMITTED",
                        "The remembered permission decision has no matching grant");
            }
            return new AuthorizedOperation(subject, operation, executionInput,
                    AuthorizationDiagnostic.Source.GRANT, "USER_REMEMBERED_GRANT",
                    remembered.grantId(), remembered.scope(), request.interactionId(), executionAttemptId);
        }
        return new AuthorizedOperation(subject, operation, executionInput,
                AuthorizationDiagnostic.Source.USER_ONCE, "USER_APPROVED_ONCE", null, null,
                request.interactionId(), executionAttemptId);
    }

    private void invariant(OperationDescriptor operation) {
        if (operation.resources().stream().anyMatch(resource -> resource.value().contains("\u0000")))
            throw new AuthorizationException("AUTHORIZATION_INVARIANT_DENIED", "Resource contains invalid characters");
        if (operation.inheritedEnvironmentNames().stream().anyMatch(this::sensitiveName))
            throw new AuthorizationException("SENSITIVE_ENVIRONMENT_ACCESS", "Sensitive environment access is not reusable");
    }

    private List<PermissionScope> scopes(OperationDescriptor op) {
        return grants.supportedScopes(op);
    }
    private String rememberScopeDescription(OperationDescriptor operation) {
        return switch (operation.analyzerId()) {
            case "network-v1" -> "Saved permission applies to " + operation.toolName()
                    + " only; URL and input values may change. Other network tools remain separate. "
                    + "Run/session limits follow the selected option, and saved grants expire within 12 hours.";
            case "mcp-v1" -> "Saved permission applies to MCP tool " + operation.toolName()
                    + " only; input values may change. Other MCP tools remain separate. "
                    + "Run/session limits follow the selected option, and saved grants expire within 12 hours.";
            default -> null;
        };
    }
    private List<Map<String, String>> options(List<PermissionScope> scopes) {
        List<Map<String, String>> options = new ArrayList<>();
        options.add(Map.of("optionId", "allow_once", "decision", "allow", "scope", "once"));
        for (PermissionScope scope : scopes) options.add(Map.of("optionId", "allow_" + scope.name().toLowerCase(),
                "decision", "allow", "scope", scope.name().toLowerCase()));
        options.add(Map.of("optionId", "deny", "decision", "deny", "scope", "once"));
        return List.copyOf(options);
    }
    private Map<String, Object> response(InteractionRequest request) {
        try { return json.readValue(request.responseJson(), new TypeReference<>() { }); }
        catch (Exception invalid) { throw new AuthorizationException("INTERACTION_PROTOCOL_INVALID", "Invalid permission response", invalid); }
    }
    private String reason(OperationDescriptor operation) {
        return switch (operation.risk()) {
            case SAFE -> "Read access requires confirmation";
            case GUARDED -> "This operation can modify or inspect workspace resources";
            case HIGH -> "This operation has external, control-plane, or unbounded effects";
        };
    }
    private boolean sensitiveName(String value) {
        String upper = value.toUpperCase();
        return upper.contains("TOKEN") || upper.contains("SECRET") || upper.contains("PASSWORD")
                || upper.contains("API_KEY") || upper.contains("PRIVATE_KEY") || upper.contains("CREDENTIAL");
    }
    private void recordDenial(ToolUseContext context, AuthorizationSubject subject,
            OperationDescriptor operation, String executionAttemptId,
            AuthorizationDiagnostic.Source source, AuthorizationDiagnostic.EvaluationStage stage,
            String reasonCode) {
        if (context.currentRunId() == null) return;
        runs.appendEventBounded(context.currentRunId(), "authorization_denied", context.toolUseId(),
                AuthorizationDiagnostic.payload(subject, operation, context, executionAttemptId,
                        AuthorizationDiagnostic.Outcome.DENY, stage, source, reasonCode,
                        null, null, null));
    }

    private void recordAnalysisFailure(ToolUseContext context, AuthorizationSubject subject,
            String toolName, String inputHash, String executionAttemptId, String reasonCode) {
        if (context.currentRunId() == null) return;
        runs.appendEventBounded(context.currentRunId(), "authorization_denied", context.toolUseId(),
                AuthorizationDiagnostic.analysisFailure(subject, toolName, inputHash, context,
                        executionAttemptId, reasonCode));
    }
}
