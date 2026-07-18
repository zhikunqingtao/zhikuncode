package com.aicodeassistant.authorization;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.model.PermissionScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** V019 授权记录的唯一权威；读取不改变状态，所有写入均有等待上限。 */
@Repository
public class PermissionGrantRepository {
    private static final Logger log = LoggerFactory.getLogger(PermissionGrantRepository.class);

    public record Match(String grantId, GrantKind kind, PermissionScope scope) { }
    public record GrantView(String grantId, String kind, String scope, String toolName, String action,
                            String summary, String createdAt, String expiresAt) { }

    private final JdbcTemplate jdbc;
    private final SqliteConfig sqlite;
    private final Path dbPath;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final Clock clock;
    private final WorkspaceIdentityService workspaces;

    @Autowired
    public PermissionGrantRepository(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc,
            SqliteConfig sqlite, DatabaseResolver resolver,
            @Qualifier("projectTransactionManager") PlatformTransactionManager txManager,
            ObjectMapper json, WorkspaceIdentityService workspaces) {
        this(jdbc, sqlite, resolver, txManager, json, Clock.systemUTC(), workspaces);
    }

    PermissionGrantRepository(JdbcTemplate jdbc, SqliteConfig sqlite, DatabaseResolver resolver,
            PlatformTransactionManager txManager, ObjectMapper json, Clock clock,
            WorkspaceIdentityService workspaces) {
        this.jdbc = jdbc;
        this.sqlite = sqlite;
        this.json = json;
        this.clock = clock;
        this.workspaces = workspaces;
        this.dbPath = resolver.getProjectDbPath(Path.of(System.getProperty("user.dir")));
        this.transaction = new TransactionTemplate(txManager);
    }

    PermissionGrantRepository(JdbcTemplate jdbc, SqliteConfig sqlite, DatabaseResolver resolver,
            PlatformTransactionManager txManager, ObjectMapper json, Clock clock) {
        this(jdbc, sqlite, resolver, txManager, json, clock, new WorkspaceIdentityService());
    }

    public Match findMatch(AuthorizationSubject subject, OperationDescriptor operation) {
        String now = clock.instant().toString();
        List<Map<String, Object>> candidates = jdbc.queryForList("""
                SELECT * FROM permission_grants
                WHERE tool_name=? AND action=? AND analyzer_id=? AND authorization_schema_version=?
                  AND revoked_at IS NULL AND expires_at>?
                  AND ((scope='RUN' AND root_run_id=?
                        AND (delegation_policy='ROOT_AND_DESCENDANTS' OR actor_run_id=?))
                    OR (scope='SESSION' AND root_session_id=? AND delegation_policy='ROOT_AND_DESCENDANTS')
                    OR (scope='WORKSPACE' AND workspace_key=? AND delegation_policy='ROOT_AND_DESCENDANTS'))
                ORDER BY CASE scope WHEN 'RUN' THEN 1 WHEN 'SESSION' THEN 2 ELSE 3 END,
                         created_at DESC
                """, operation.toolName(), operation.action(), operation.analyzerId(),
                operation.authorizationSchemaVersion(), now, subject.rootRunId(),
                subject.currentRunId(), subject.rootSessionId(), subject.workspaceKey());
        for (Map<String, Object> row : candidates) {
            if ("RUN".equals(String.valueOf(row.get("scope"))) && rootRunIsTerminal(subject.rootRunId())) continue;
            GrantKind kind = GrantKind.valueOf(String.valueOf(row.get("grant_kind")));
            GrantConstraint constraint = decodeConstraint(String.valueOf(row.get("grant_id")), kind,
                    String.valueOf(row.get("constraints_json")));
            if (constraint != null && PermissionGrantMatcher.matches(constraint, operation)) {
                log.debug("Permission grant matched: grantId={}, kind={}, scope={}, tool={}, analyzer={}",
                        row.get("grant_id"), kind, row.get("scope"), operation.toolName(),
                        operation.analyzerId());
                return new Match(String.valueOf(row.get("grant_id")), kind,
                        PermissionScope.valueOf(String.valueOf(row.get("scope"))));
            }
        }
        return null;
    }

    public String create(AuthorizationSubject subject, OperationDescriptor operation,
            PermissionScope requestedScope, String interactionId) {
        return boundedWrite(() -> transaction.execute(status ->
                createInCurrentTransaction(subject, operation, requestedScope, interactionId)));
    }

    /** 仅供 DurableInteractionService 的外层事务调用。 */
    public String createInCurrentTransaction(AuthorizationSubject subject, OperationDescriptor operation,
            PermissionScope requestedScope, String interactionId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Grant creation requires the interaction transaction");
        }
        GrantPlan plan = plan(operation, requestedScope);
        if (plan == null) return null;
        Instant now = clock.instant();
        // SQLite 部分索引不能包含动态时间条件，因此插入前先注销已过期记录。
        jdbc.update("UPDATE permission_grants SET revoked_at=?,version=version+1 " +
                        "WHERE revoked_at IS NULL AND expires_at<=?", now.toString(), now.toString());

        String constraintsJson = writeConstraint(plan.constraint);
        String capabilityHash = plan.kind == GrantKind.EXACT_GUARDED ? null
                : hash(plan.kind.name() + "\0" + constraintsJson + "\0" + operation.authorizationSchemaVersion());
        Identity identity = identity(subject, plan.scope, plan.delegation);
        String id = UUID.randomUUID().toString();
        Instant expires = switch (plan.scope) {
            case RUN, SESSION -> now.plus(12, ChronoUnit.HOURS);
            case WORKSPACE -> now.plus(30, ChronoUnit.DAYS);
            case ONCE -> throw new IllegalStateException("ONCE is not persisted");
        };
        int inserted = jdbc.update("""
                INSERT INTO permission_grants(grant_id,grant_kind,scope,delegation_policy,
                  root_session_id,root_run_id,actor_run_id,workspace_key,
                  authorization_schema_version,analyzer_id,tool_name,action,effects_json,
                  operation_hash,capability_hash,constraints_json,risk_class,
                  created_by_interaction_id,created_at,expires_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """, id, plan.kind.name(), plan.scope.name(), plan.delegation.name(),
                identity.rootSessionId, identity.rootRunId, identity.actorRunId, identity.workspaceKey,
                operation.authorizationSchemaVersion(), operation.analyzerId(), operation.toolName(),
                operation.action(), writeJson(operation.effects()),
                plan.kind == GrantKind.EXACT_GUARDED ? operation.operationHash() : null,
                capabilityHash, constraintsJson, operation.risk().name(), interactionId,
                now.toString(), expires.toString());
        if (inserted == 1) {
            log.info("Permission grant created: grantId={}, kind={}, scope={}, tool={}, analyzer={}",
                    id, plan.kind, plan.scope, operation.toolName(), operation.analyzerId());
            return id;
        }
        List<String> existing = exactIdentity(plan, identity, operation, capabilityHash);
        if (existing.size() != 1) {
            log.error("Permission grant identity conflict: kind={}, scope={}, tool={}, matches={}",
                    plan.kind, plan.scope, operation.toolName(), existing.size());
            throw new IllegalStateException("GRANT_CREATE_IDENTITY_CONFLICT");
        }
        log.debug("Reused concurrent permission grant: grantId={}, kind={}, scope={}, tool={}",
                existing.getFirst(), plan.kind, plan.scope, operation.toolName());
        return existing.getFirst();
    }

    public boolean revoke(String grantId) {
        boolean revoked = boundedWrite(() -> transaction.execute(status -> jdbc.update(
                "UPDATE permission_grants SET revoked_at=?,version=version+1 WHERE grant_id=? AND revoked_at IS NULL",
                clock.instant().toString(), grantId) == 1));
        if (revoked) log.info("Permission grant revoked: grantId={}", grantId);
        return revoked;
    }

    public List<GrantView> listActiveForSession(String rootSessionId, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        String workspace = workspaceForSession(rootSessionId);
        return jdbc.query("""
                SELECT grant_id,grant_kind,scope,tool_name,action,constraints_json,created_at,expires_at
                FROM permission_grants WHERE revoked_at IS NULL AND expires_at>?
                  AND (root_session_id=? OR workspace_key=?)
                ORDER BY created_at DESC LIMIT ?
                """, (rs, row) -> new GrantView(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)),
                clock.instant().toString(), rootSessionId, workspace, limit);
    }

    public int revokeForSession(String grantId, String rootSessionId) {
        String workspace = workspaceForSession(rootSessionId);
        return boundedWrite(() -> transaction.execute(status -> jdbc.update("""
                UPDATE permission_grants SET revoked_at=?,version=version+1
                WHERE grant_id=? AND revoked_at IS NULL AND (root_session_id=? OR workspace_key=?)
                """, clock.instant().toString(), grantId, rootSessionId, workspace)));
    }

    /** 返回服务端依据当前操作分析结果允许展示的“记住授权”范围。 */
    public List<PermissionScope> supportedScopes(OperationDescriptor operation) {
        if (operation.risk() == RiskClass.HIGH) return List.of();
        if ("bash-v2".equals(operation.analyzerId())) {
            return List.of(PermissionScope.RUN, PermissionScope.SESSION);
        }
        if (!"file-v1".equals(operation.analyzerId())) return List.of();
        return capabilityConstraint(operation) == null
                ? List.of(PermissionScope.RUN, PermissionScope.SESSION)
                : List.of(PermissionScope.RUN, PermissionScope.SESSION, PermissionScope.WORKSPACE);
    }

    public void revokeRunScoped(String rootRunId) {
        boundedWrite(() -> transaction.execute(status -> jdbc.update("""
                UPDATE permission_grants SET revoked_at=?,version=version+1
                WHERE scope='RUN' AND root_run_id=? AND revoked_at IS NULL
                """, clock.instant().toString(), rootRunId)));
    }

    private GrantPlan plan(OperationDescriptor operation, PermissionScope requested) {
        if (requested == null || requested == PermissionScope.ONCE || operation.risk() == RiskClass.HIGH) return null;
        if ("bash-v2".equals(operation.analyzerId())) {
            if (requested == PermissionScope.WORKSPACE || operation.risk() != RiskClass.GUARDED) return null;
            return new GrantPlan(GrantKind.EXACT_GUARDED, requested,
                    requested == PermissionScope.RUN ? DelegationPolicy.DIRECT_ONLY : DelegationPolicy.ROOT_AND_DESCENDANTS,
                    new GrantConstraint.Exact(operation.operationHash()));
        }
        if ("file-v1".equals(operation.analyzerId())) {
            if (requested == PermissionScope.RUN) {
                return new GrantPlan(GrantKind.EXACT_GUARDED, requested, DelegationPolicy.DIRECT_ONLY,
                        new GrantConstraint.Exact(operation.operationHash()));
            }
            GrantConstraint constraint = capabilityConstraint(operation);
            if (constraint == null) {
                if (requested != PermissionScope.SESSION) return null;
                return new GrantPlan(GrantKind.EXACT_GUARDED, requested,
                        DelegationPolicy.ROOT_AND_DESCENDANTS,
                        new GrantConstraint.Exact(operation.operationHash()));
            }
            boolean write = constraint instanceof GrantConstraint.WorkspaceEdit;
            return new GrantPlan(write ? GrantKind.EDIT_CAPABILITY : GrantKind.READ_CAPABILITY,
                    requested, DelegationPolicy.ROOT_AND_DESCENDANTS, constraint);
        }
        return null;
    }

    private GrantConstraint capabilityConstraint(OperationDescriptor operation) {
        if (operation.resources().isEmpty()
                || operation.resources().stream().anyMatch(ResourceRef::outsideWorkspace)) return null;
        TypedFileOperation fileOperation;
        try { fileOperation = TypedFileOperation.valueOf(operation.action()); }
        catch (Exception invalid) { return null; }
        List<String> directories = new java.util.ArrayList<>();
        for (ResourceRef resource : operation.resources()) {
            String directory = directoryOf(resource.value(), fileOperation);
            if (directory == null) return null;
            directories.add(directory);
        }
        List<String> normalized = directories.stream().distinct().sorted().toList();
        boolean write = operation.effects().contains(EffectClass.WRITE_RESOURCE);
        try {
            return write
                    ? new GrantConstraint.WorkspaceEdit(normalized, List.of(fileOperation))
                    : new GrantConstraint.WorkspaceRead(normalized, List.of(fileOperation));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private Identity identity(AuthorizationSubject subject, PermissionScope scope, DelegationPolicy delegation) {
        return switch (scope) {
            case RUN -> new Identity(null, subject.rootRunId(),
                    delegation == DelegationPolicy.DIRECT_ONLY ? subject.currentRunId() : null, null);
            case SESSION -> new Identity(subject.rootSessionId(), null, null, null);
            case WORKSPACE -> new Identity(null, null, null, subject.workspaceKey());
            case ONCE -> throw new IllegalArgumentException("ONCE cannot be persisted");
        };
    }

    private boolean rootRunIsTerminal(String runId) {
        List<String> status = jdbc.query("SELECT status FROM run_envelopes WHERE id=?",
                (rs, row) -> rs.getString(1), runId);
        return status.isEmpty() || switch (status.getFirst()) {
            case "completed", "failed", "cancelled", "aborted" -> true;
            default -> false;
        };
    }

    private String workspaceForSession(String sessionId) {
        List<String> roots = jdbc.query("SELECT working_dir FROM sessions WHERE id=?",
                (rs, row) -> rs.getString(1), sessionId);
        if (roots.isEmpty()) return "";
        try { return workspaces.resolve(Path.of(roots.getFirst())).workspaceKey(); }
        catch (AuthorizationException invalid) {
            log.warn("Unable to resolve workspace for permission grant lookup: sessionId={}, code={}",
                    sessionId, invalid.code());
            return "";
        }
    }

    private List<String> exactIdentity(GrantPlan plan, Identity identity,
                                       OperationDescriptor operation, String capabilityHash) {
        return jdbc.queryForList("""
                SELECT grant_id FROM permission_grants
                WHERE grant_kind=? AND scope=? AND delegation_policy=?
                  AND COALESCE(root_session_id,'')=COALESCE(?, '')
                  AND COALESCE(root_run_id,'')=COALESCE(?, '')
                  AND COALESCE(actor_run_id,'')=COALESCE(?, '')
                  AND COALESCE(workspace_key,'')=COALESCE(?, '')
                  AND authorization_schema_version=? AND analyzer_id=? AND tool_name=? AND action=?
                  AND COALESCE(operation_hash,'')=COALESCE(?, '')
                  AND COALESCE(capability_hash,'')=COALESCE(?, '') AND revoked_at IS NULL
                """, String.class, plan.kind.name(), plan.scope.name(), plan.delegation.name(),
                identity.rootSessionId, identity.rootRunId, identity.actorRunId, identity.workspaceKey,
                operation.authorizationSchemaVersion(), operation.analyzerId(), operation.toolName(),
                operation.action(), plan.kind == GrantKind.EXACT_GUARDED ? operation.operationHash() : null,
                capabilityHash);
    }

    private String writeConstraint(GrantConstraint constraint) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        switch (constraint) {
            case GrantConstraint.Exact exact -> {
                encoded.put("type", "EXACT"); encoded.put("operationHash", exact.operationHash());
            }
            case GrantConstraint.WorkspaceRead read -> {
                encoded.put("type", "WORKSPACE_READ");
                encoded.put("relativeDirectoryPrefixes", read.relativeDirectoryPrefixes());
                encoded.put("allowedOperations", read.allowedOperations());
            }
            case GrantConstraint.WorkspaceEdit edit -> {
                encoded.put("type", "WORKSPACE_EDIT");
                encoded.put("relativeDirectoryPrefixes", edit.relativeDirectoryPrefixes());
                encoded.put("allowedOperations", edit.allowedOperations());
            }
        }
        return writeJson(encoded);
    }

    private GrantConstraint decodeConstraint(String grantId, GrantKind kind, String encoded) {
        try {
            Map<String, Object> value = json.readValue(encoded, new TypeReference<>() { });
            if (kind == GrantKind.EXACT_GUARDED) {
                return new GrantConstraint.Exact(String.valueOf(value.get("operationHash")));
            }
            List<String> prefixes = ((List<?>) value.getOrDefault("relativeDirectoryPrefixes", List.of()))
                    .stream().map(String::valueOf).toList();
            List<TypedFileOperation> operations = ((List<?>) value.getOrDefault("allowedOperations", List.of()))
                    .stream().map(String::valueOf).map(TypedFileOperation::valueOf).toList();
            return kind == GrantKind.READ_CAPABILITY
                    ? new GrantConstraint.WorkspaceRead(prefixes, operations)
                    : new GrantConstraint.WorkspaceEdit(prefixes, operations);
        } catch (Exception invalid) {
            // 持久化约束损坏时必须忽略该授权，绝不能隐式放行。
            log.warn("Ignoring invalid permission grant constraint: grantId={}, kind={}",
                    grantId, kind, invalid);
            return null;
        }
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalArgumentException("GRANT_CONSTRAINT_INVALID", failure); }
    }
    private <T> T boundedWrite(java.util.function.Supplier<T> body) {
        return sqlite.executeWriteBounded(dbPath, Duration.ofSeconds(5), body);
    }
    private static String directoryOf(String path, TypedFileOperation operation) {
        String normalized = PermissionGrantMatcher.normalizeRelativePath(path);
        if (".".equals(normalized)) {
            return operation == TypedFileOperation.LIST_DIRECTORY ? "." : null;
        }
        // 目录浏览授权以用户实际看到的目录为边界，不能取父目录，否则会把 src/main 静默扩大到 src。
        if (operation == TypedFileOperation.LIST_DIRECTORY) return normalized;
        int slash = normalized.lastIndexOf('/');
        // 根目录文件属于工作区根目录能力；这是单文件授权扩展到同目录的预期语义。
        return slash < 0 ? "." : normalized.substring(0, slash);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private record Identity(String rootSessionId, String rootRunId, String actorRunId, String workspaceKey) { }
    private record GrantPlan(GrantKind kind, PermissionScope scope, DelegationPolicy delegation,
                             GrantConstraint constraint) { }
}
