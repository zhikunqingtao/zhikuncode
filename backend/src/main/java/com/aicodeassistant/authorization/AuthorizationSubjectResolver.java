package com.aicodeassistant.authorization;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 从不可变的持久化 Run 父链解析根授权主体。 */
@Component
public final class AuthorizationSubjectResolver {
    private static final int MAX_DEPTH = 32;
    private final JdbcTemplate jdbc;
    private final WorkspaceIdentityService workspaces;
    private final Cache<String, Root> roots = Caffeine.newBuilder()
            .maximumSize(4096).expireAfterAccess(Duration.ofMinutes(30)).build();

    @Autowired
    public AuthorizationSubjectResolver(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc,
                                        WorkspaceIdentityService workspaces) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
    }

    AuthorizationSubjectResolver(JdbcTemplate jdbc) {
        this(jdbc, new WorkspaceIdentityService());
    }

    public AuthorizationSubject resolve(String currentRunId) {
        if (currentRunId == null || currentRunId.isBlank()) {
            throw new AuthorizationException("AUTHORIZATION_ANCESTRY_INVALID", "Tool execution requires a persisted Run");
        }
        Root root = roots.get(currentRunId, this::loadRoot);
        if (root == null) {
            throw new AuthorizationException("AUTHORIZATION_ANCESTRY_INVALID", "Run ancestry cannot be resolved");
        }
        return new AuthorizationSubject(root.sessionId, root.runId, currentRunId,
                root.workspaceKey, root.authorizationRoot);
    }

    public void invalidateAll() { roots.invalidateAll(); }

    private Root loadRoot(String runId) {
        String current = runId;
        Set<String> seen = new HashSet<>();
        for (int depth = 0; depth <= MAX_DEPTH; depth++) {
            if (!seen.add(current)) throw invalid("Run ancestry contains a cycle");
            // 子代理使用合成会话 ID，且不会创建 Session 记录。只有根 Run 的会话才是授权身份；
            // 若遍历每个子节点时都连接 sessions 表，会在抵达根节点前错误拒绝真实子代理父链。
            List<Row> rows = jdbc.query("""
                    SELECT id,session_id,parent_run_id FROM run_envelopes WHERE id=?
                    """, (rs, row) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3)), current);
            if (rows.size() != 1) throw invalid("Run ancestry contains a missing parent");
            Row row = rows.getFirst();
            if (row.parentRunId == null) {
                List<String> workingDirectories = jdbc.query(
                        "SELECT working_dir FROM sessions WHERE id=?",
                        (rs, index) -> rs.getString(1), row.sessionId);
                if (workingDirectories.size() != 1) {
                    throw invalid("Root session is missing or ambiguous");
                }
                WorkspaceIdentityService.Identity workspace =
                        workspaces.resolve(Path.of(workingDirectories.getFirst()));
                return new Root(row.id, row.sessionId, workspace.authorizationRoot(), workspace.workspaceKey());
            }
            current = row.parentRunId;
        }
        throw invalid("Run ancestry exceeds " + MAX_DEPTH + " levels");
    }

    private static AuthorizationException invalid(String message) {
        return new AuthorizationException("AUTHORIZATION_ANCESTRY_INVALID", message);
    }

    private record Row(String id, String sessionId, String parentRunId) { }
    private record Root(String runId, String sessionId, Path authorizationRoot, String workspaceKey) { }
}
