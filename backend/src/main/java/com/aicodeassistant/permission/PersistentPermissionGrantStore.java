package com.aicodeassistant.permission;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.run.RunControlService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.DependsOn;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@DependsOn("migrationRunner")
public class PersistentPermissionGrantStore {
    private final JdbcTemplate jdbc; private final SqliteConfig sqlite; private final Path dbPath;
    private final TransactionTemplate transaction;
    private final RunControlService runs;
    public PersistentPermissionGrantStore(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc,
            SqliteConfig sqlite, DatabaseResolver resolver,
            @Qualifier("projectTransactionManager") PlatformTransactionManager txManager,
            RunControlService runs) {
        this.jdbc=jdbc; this.sqlite=sqlite;
        this.dbPath=resolver.getProjectDbPath(Path.of(System.getProperty("user.dir")));
        this.transaction = new TransactionTemplate(txManager);
        this.runs = runs;
    }
    public boolean matchesSession(String runId, String sessionId, PermissionGrantKeyFactory.GrantKey key) {
        return match(runId, "scope='SESSION' AND session_id=?", sessionId, key, key.hash());
    }
    public boolean matchesWorkspace(String runId, String workspaceHash, PermissionGrantKeyFactory.GrantKey key) {
        return key.workspaceAllowed() && match(runId, "scope='WORKSPACE' AND workspace_hash=?",
                workspaceHash, key, key.hashForScope("WORKSPACE"));
    }
    public boolean matchesChildExact(String parentSessionId, String childRunId,
                                     String childSessionId, String agentType,
                                     PermissionGrantKeyFactory.GrantKey key) {
        if (!"low".equalsIgnoreCase(key.riskClass()) || parentSessionId == null
                || childRunId == null || childSessionId == null || agentType == null) return false;
        String parentRunId = parentRunId(childRunId);
        if (parentRunId == null) return false;
        return write(() -> {
        List<Map<String, Object>> grants = jdbc.queryForList("""
                SELECT g.grant_id, g.created_by_interaction_id FROM permission_grants g
                JOIN run_envelopes r ON r.id=g.parent_run_id
                WHERE g.grant_kind='CHILD_EXACT' AND g.scope='SESSION' AND g.session_id=?
                  AND g.parent_run_id=? AND g.child_session_id=? AND g.agent_type=?
                  AND g.tool_name=? AND g.action=? AND g.risk_class=?
                  AND g.grant_key_hash=? AND g.canonical_cwd=?
                  AND g.reason_code IN ('READ_ONLY_INSPECTION','LOCAL_LOG_READ')
                  AND g.revoked_at IS NULL AND r.status IN ('running','waiting_interaction')
                  AND (g.expires_at IS NULL OR g.expires_at>?) LIMIT 1
                """, parentSessionId, parentRunId, childSessionId, agentType,
                key.toolName(), key.action(), key.riskClass(), key.hash(), key.canonicalCwd(), Instant.now().toString());
        if (grants.isEmpty()) return false;
        Map<String, Object> matched = grants.getFirst();
        String grantId = String.valueOf(matched.get("grant_id"));
        jdbc.update(
                "UPDATE permission_grants SET last_matched_at=? WHERE grant_id=?",
                Instant.now().toString(), grantId);
        appendChildEvent(parentRunId, "permission_grant_matched", grantId,
                nullableString(matched.get("created_by_interaction_id")), parentSessionId,
                childSessionId, agentType, key);
        return true;
        });
    }
    private boolean match(String runId, String scopeClause, String scopeValue,
                          PermissionGrantKeyFactory.GrantKey key, String grantHash) {
        return write(() -> {
        List<String> ids=jdbc.queryForList("SELECT grant_id FROM permission_grants WHERE "+scopeClause+" AND grant_kind='STANDARD' AND tool_name=? AND action=? AND risk_class=? AND grant_key_hash=? AND canonical_cwd=? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at>?) LIMIT 1",
                String.class, scopeValue,key.toolName(),key.action(),key.riskClass(),grantHash,key.canonicalCwd(),Instant.now().toString());
        if (ids.isEmpty()) return false;
        jdbc.update("UPDATE permission_grants SET last_matched_at=? WHERE grant_id=?",Instant.now().toString(),ids.getFirst());
        appendEvent(runId,"permission_grant_matched",ids.getFirst(),key,grantHash);
        return true;
        });
    }
    public void saveStandard(String scope, String sessionId, String workspaceHash,
            PermissionGrantKeyFactory.GrantKey key, String interactionId) {
        if ("WORKSPACE".equals(scope) && !key.workspaceAllowed()) throw new IllegalArgumentException("WORKSPACE_SCOPE_UNSUPPORTED");
        Instant now=Instant.now(); String expires="WORKSPACE".equals(scope)?now.plus(30,ChronoUnit.DAYS).toString():null;
        write(()->{
            String grantId=UUID.randomUUID().toString();
            int inserted=jdbc.update("""
                INSERT INTO permission_grants(grant_id,grant_kind,scope,session_id,workspace_hash,tool_name,action,risk_class,grant_key_hash,canonical_cwd,created_by_interaction_id,created_at,expires_at)
                VALUES(?,'STANDARD',?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT DO NOTHING
                """,grantId,scope,sessionId,workspaceHash,key.toolName(),key.action(),key.riskClass(),
                    key.hashForScope(scope),key.canonicalCwd(),interactionId,now.toString(),expires);
            if(inserted==1)appendEvent(runIdForInteraction(interactionId),"permission_grant_created",grantId,key,key.hashForScope(scope));
            return inserted;
        });
    }
    public void saveChildExact(String parentSessionId, String childRunId, String childSessionId,
                               String agentType, PermissionGrantKeyFactory.GrantKey key,
                               String interactionId) {
        if (!"low".equalsIgnoreCase(key.riskClass())) return;
        String parentRunId = parentRunId(childRunId);
        if (parentRunId == null) return;
        Instant now = Instant.now();
        write(() -> {
            String grantId=UUID.randomUUID().toString();
            int inserted=jdbc.update("""
                INSERT INTO permission_grants(grant_id,grant_kind,scope,session_id,workspace_hash,
                  tool_name,action,risk_class,grant_key_hash,canonical_cwd,parent_session_id,parent_run_id,
                  child_session_id,agent_type,reason_code,created_by_interaction_id,created_at,expires_at)
                VALUES(?,'CHILD_EXACT','SESSION',?,NULL,?,?,?,?,?,?,?,?,?,'READ_ONLY_INSPECTION',?,?,?)
                ON CONFLICT DO NOTHING
                """, grantId, parentSessionId, key.toolName(), key.action(),
                key.riskClass(), key.hash(), key.canonicalCwd(), parentSessionId, parentRunId,
                childSessionId, agentType, interactionId, now.toString(), now.plus(30, ChronoUnit.MINUTES).toString());
            if (inserted == 1) {
                appendChildEvent(parentRunId, "permission_grant_created", grantId,
                        interactionId, parentSessionId, childSessionId, agentType, key);
            }
            return inserted;
        });
    }
    private String parentRunId(String childRunId) {
        List<String> ids = jdbc.queryForList("SELECT parent_run_id FROM run_envelopes WHERE id=? AND parent_run_id IS NOT NULL",
                String.class, childRunId);
        return ids.isEmpty() ? null : ids.getFirst();
    }
    public int revokeForSession(String grantId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return 0;
        return write(() -> {
            String runId = runIdForGrant(grantId);
            int updated = jdbc.update("""
                    UPDATE permission_grants SET revoked_at=?
                    WHERE grant_id=? AND revoked_at IS NULL
                      AND (scope='WORKSPACE' OR session_id=? OR parent_session_id=?)
                    """, Instant.now().toString(), grantId, sessionId, sessionId);
            if (updated == 1 && runId != null) {
                runs.appendEventInCurrentWrite(runId, "permission_grant_revoked", null,
                        Map.of("grantId", grantId, "revokedBySessionId", sessionId));
            }
            return updated;
        });
    }

    public List<GrantView> listActiveForSession(String sessionId, int requestedLimit) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        return listActiveWhere("(scope='WORKSPACE' OR session_id=? OR parent_session_id=?)",
                List.of(sessionId, sessionId), requestedLimit);
    }

    private List<GrantView> listActiveWhere(String accessClause, List<Object> accessArguments,
                                            int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        String sql = """
                SELECT grant_id, grant_kind, scope, session_id, workspace_hash,
                       tool_name, action, risk_class, canonical_cwd, parent_run_id,
                       child_session_id, agent_type, reason_code, created_at,
                       expires_at, last_matched_at
                FROM permission_grants
                WHERE revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?)
                  AND %s
                ORDER BY created_at DESC
                LIMIT ?
                """.formatted(accessClause);
        List<Object> arguments = new java.util.ArrayList<>();
        arguments.add(Instant.now().toString());
        arguments.addAll(accessArguments);
        arguments.add(limit);
        return jdbc.query(sql, (rs, rowNum) -> new GrantView(
                        rs.getString("grant_id"),
                        rs.getString("grant_kind"),
                        rs.getString("scope"),
                        rs.getString("session_id"),
                        rs.getString("workspace_hash"),
                        rs.getString("tool_name"),
                        rs.getString("action"),
                        rs.getString("risk_class"),
                        rs.getString("canonical_cwd"),
                        rs.getString("parent_run_id"),
                        rs.getString("child_session_id"),
                        rs.getString("agent_type"),
                        rs.getString("reason_code"),
                        rs.getString("created_at"),
                        rs.getString("expires_at"),
                        rs.getString("last_matched_at")),
                arguments.toArray());
    }

    public record GrantView(
            String grantId,
            String grantKind,
            String scope,
            String sessionId,
            String workspaceHash,
            String toolName,
            String action,
            String riskClass,
            String canonicalCwd,
            String parentRunId,
            String childSessionId,
            String agentType,
            String reasonCode,
            String createdAt,
            String expiresAt,
            String lastMatchedAt
    ) {}
    private String runIdForInteraction(String interactionId) {
        List<String> ids=jdbc.queryForList("SELECT run_id FROM interaction_requests WHERE interaction_id=?",String.class,interactionId);
        return ids.isEmpty()?null:ids.getFirst();
    }
    private String runIdForGrant(String grantId) {
        List<String> ids=jdbc.queryForList("""
                SELECT COALESCE(g.parent_run_id,i.run_id) FROM permission_grants g
                LEFT JOIN interaction_requests i ON i.interaction_id=g.created_by_interaction_id
                WHERE g.grant_id=?
                """,String.class,grantId);
        return ids.isEmpty()?null:ids.getFirst();
    }
    private void appendEvent(String runId,String type,String grantId,
                             PermissionGrantKeyFactory.GrantKey key,String matchedHash) {
        if(runId!=null)runs.appendEventInCurrentWrite(runId,type,null,Map.of(
                "grantId",grantId,"toolName",key.toolName(),"action",key.action(),
                "riskClass",key.riskClass(),"grantKeyHash",matchedHash));
    }

    private void appendChildEvent(String runId, String type, String grantId,
                                  String sourceInteractionId, String parentSessionId,
                                  String childSessionId, String agentType,
                                  PermissionGrantKeyFactory.GrantKey key) {
        if (runId == null) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("grantId", grantId);
        data.put("sourceInteractionId", sourceInteractionId);
        data.put("parentSessionId", parentSessionId);
        data.put("childSessionId", childSessionId);
        data.put("agentType", agentType);
        data.put("toolName", key.toolName());
        data.put("action", key.action());
        data.put("riskClass", key.riskClass());
        data.put("grantKeyHash", key.hash());
        runs.appendEventInCurrentWrite(runId, type, null, data);
    }

    private static String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
    private <T> T write(java.util.function.Supplier<T> operation) {
        return sqlite.executeWrite(dbPath, () -> transaction.execute(status -> operation.get()));
    }
}
