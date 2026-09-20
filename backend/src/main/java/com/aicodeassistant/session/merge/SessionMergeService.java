package com.aicodeassistant.session.merge;

import com.aicodeassistant.coordinator.SwarmService;
import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.engine.AbortReason;
import com.aicodeassistant.exception.ResourceNotFoundException;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.session.*;
import com.aicodeassistant.tool.agent.BackgroundAgentTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
@DependsOn("migrationRunner")
public class SessionMergeService {
    private static final Logger log = LoggerFactory.getLogger(SessionMergeService.class);
    public record Request(List<String> sourceSessionIds, String primarySessionId, String title, String model) { }
    public record Operation(String operationId, String targetSessionId, String status, String stage,
            Request request, String packagePath, Map<String, Object> result, Object usage, String error) { }
    private final JdbcTemplate jdbc;
    private final SessionManager sessions;
    private final SessionExecutionGate gate;
    private final MergePackageService packages;
    private final MergeSummaryService summaries;
    private final ProjectWorkspaceService workspaces;
    private final PermissionModeManager permissions;
    private final BackgroundAgentTracker agents;
    private final SwarmService swarms;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "session-merge-deadline"); t.setDaemon(true); return t;
    });
    private final Semaphore generation = new Semaphore(1);
    private final Set<AbortContext> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closing;
    public record StatusChanged(String operationId) { }
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationEventPublisher events;

    public SessionMergeService(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc, SessionManager sessions,
            SessionExecutionGate gate, MergePackageService packages, MergeSummaryService summaries,
            ProjectWorkspaceService workspaces, PermissionModeManager permissions, BackgroundAgentTracker agents,
            SwarmService swarms, ObjectMapper json,
            @Qualifier("projectTransactionManager") PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.sessions = sessions; this.gate = gate; this.packages = packages; this.summaries = summaries;
        this.workspaces = workspaces; this.permissions = permissions; this.agents = agents; this.swarms = swarms;
        this.json = json; this.tx = new TransactionTemplate(transactions);
    }

    @PostConstruct
    public void recover() {
        // A committed target and completed ledger are one transaction. Never clean completed packages.
        jdbc.update("UPDATE session_merges SET status='failed', stage='interrupted', error='合并因进程中断而失败', updated_at=? WHERE status='preparing'",
                Instant.now().toString());
        for (var row : jdbc.queryForList("SELECT package_path FROM session_merges WHERE status='failed' AND target_session_id NOT IN (SELECT id FROM sessions)"))
            cleanup(Path.of(row.get("package_path").toString()));
    }

    public synchronized Operation start(Request raw, String key) {
        if (closing) throw conflict("服务正在关闭");
        if (key == null || key.isBlank() || key.length() > 128) throw bad("需要不超过 128 字符的幂等键");
        Request request = normalize(raw);
        String params = encode(request);
        var previous = jdbc.queryForList("SELECT operation_id,params_json FROM session_merges WHERE idempotency_key=?", key);
        if (!previous.isEmpty()) {
            if (!params.equals(previous.getFirst().get("params_json"))) throw conflict("幂等键已用于不同的合并参数");
            return get(previous.getFirst().get("operation_id").toString());
        }
        if (!generation.tryAcquire()) throw conflict("已有会话正在合并，请稍后重试");
        var leases = new ArrayList<SessionExecutionGate.Token>();
        String operation = UUID.randomUUID().toString();
        String target = UUID.randomUUID().toString();
        boolean submitted = false;
        try {
            Path path = packages.packagePath(target, operation);
            var ids = packages.descendants(request.sourceSessionIds());
            for (String id : ids.stream().sorted().toList()) {
                var lease = gate.tryAcquireMerge(sessions.dataSourceIdentity(), id, operation);
                if (lease == null) throw conflict("来源会话或关联子会话（ID：" + id + "）仍有执行占用，暂不能合并。"
                        + "可能有尚未退出的后台任务或开发服务；开发服务可能持续运行，不会因等待合并而自动停止。"
                        + "请回到对应来源会话查看任务；若由 Bash 后台启动，可在启动结果中查看 PID 和停止说明，"
                        + "确认服务可以停止后再操作，待进程退出后重试合并。");
                leases.add(lease);
            }
            for (String id : ids) {
                if (!agents.getActiveAgentIds(id).isEmpty() || swarms.hasActiveSwarm(id)
                        || jdbc.queryForObject("SELECT COUNT(*) FROM run_envelopes WHERE session_id=? AND status NOT IN ('completed','failed','cancelled','interrupted')", Long.class, id) > 0)
                    throw conflict("来源会话或关联子会话（ID：" + id + "）仍在执行、等待审批或取消中，请回到对应来源会话处理，待任务完全结束后重试合并");
            }
            for (String id : request.sourceSessionIds()) {
                var data = sourceInfo(id);
                if (data.metadata().has("parent_session_id") || "subagent".equals(data.metadata().path("type").asText()))
                    throw bad("只能选择普通会话");
            }
            var primary = sourceInfo(request.primarySessionId());
            String directory = workspaces.requireCurrentBinding(primary.workingDir()).toString();
            MergeSummaryService.Selection selection;
            try { selection = summaries.select(request.model() == null ? primary.model() : request.model()); }
            catch (IllegalArgumentException e) { throw bad(e.getMessage()); }
            String title = request.title() == null ? "合并 · " + Objects.toString(primary.title(), "会话") : request.title();
            String now = Instant.now().toString();
            jdbc.update("INSERT INTO session_merges(operation_id,idempotency_key,params_json,target_session_id,status,stage,package_path,created_at,updated_at) VALUES(?,?,?,?,'preparing','snapshot',?,?,?)",
                    operation, key, params, target, path.toString(), now, now);
            worker.execute(() -> execute(operation, target, path, request, selection, directory, title, leases));
            submitted = true;
            notifyChanged(operation);
            return get(operation);
        } catch (RuntimeException failure) {
            if (!submitted) jdbc.update("UPDATE session_merges SET status='failed',stage='failed',error='无法启动合并',updated_at=? WHERE operation_id=? AND status='preparing'",
                    Instant.now().toString(), operation);
            throw failure;
        } finally {
            if (!submitted) { leases.forEach(SessionExecutionGate.Token::close); generation.release(); }
        }
    }

    private MergePackageService.SessionInfo sourceInfo(String id) {
        try { return packages.sessionInfo(id); }
        catch (IllegalArgumentException invalid) { throw bad(invalid.getMessage()); }
    }

    private Request normalize(Request raw) {
        if (raw == null || raw.sourceSessionIds() == null || raw.sourceSessionIds().size() < 2 || raw.sourceSessionIds().size() > 5
                || raw.sourceSessionIds().stream().anyMatch(id -> id == null || !id.matches("[A-Za-z0-9_-]{1,128}"))
                || new HashSet<>(raw.sourceSessionIds()).size() != raw.sourceSessionIds().size() || !raw.sourceSessionIds().contains(raw.primarySessionId()))
            throw bad("请选择 2～5 个不同的来源会话，以及其中的主会话");
        String title = raw.title() == null || raw.title().isBlank() ? null : raw.title().strip();
        if (title != null && title.length() > 200) throw bad("标题不能超过 200 字符");
        return new Request(raw.sourceSessionIds().stream().sorted().toList(), raw.primarySessionId(), title,
                raw.model() == null || raw.model().isBlank() ? null : raw.model().strip());
    }

    private void execute(String operation, String target, Path path, Request request, MergeSummaryService.Selection selection,
            String directory, String title, List<SessionExecutionGate.Token> leases) {
        AbortContext abort = new AbortContext(); active.add(abort);
        ScheduledFuture<?> timer = null;
        boolean committed = false;
        boolean commitAttempted = false;
        var usage = new ArrayList<MergeSummaryService.CallUsage>();
        Runnable check = () -> {
            if (closing || abort.isAborted()) throw new IllegalStateException("MERGE_INTERRUPTED_OR_TIMED_OUT");
        };
        try {
            check.run();
            timer = deadlines.schedule(() -> abort.abort(AbortReason.TIMEOUT), 10, TimeUnit.MINUTES);
            var bundle = packages.build(path, request.sourceSessionIds(), check);
            jdbc.update("UPDATE session_merges SET result_json=? WHERE operation_id=? AND status='preparing'",
                    encode(Map.of("copiedCount", bundle.copiedCount(), "warningCount", bundle.warningCount(),
                            "warnings", bundle.assets().stream().filter(a -> Set.of("missing", "ownership_unknown", "copy_failed").contains(a.status())).toList())), operation);
            stage(operation, "summarizing");
            String body = summaries.summarize(bundle, request.sourceSessionIds(), selection, operation, abort, check, call -> {
                usage.add(call);
                jdbc.update("UPDATE session_merges SET usage_json=?,updated_at=? WHERE operation_id=? AND status='preparing'",
                        encode(usage), Instant.now().toString(), operation);
            });
            check.run();
            // Revalidate the primary project binding in case it was revoked while generating.
            workspaces.requireCurrentBinding(directory);
            stage(operation, "committing");
            String result = encode(Map.of("copiedCount", bundle.copiedCount(), "warningCount", bundle.warningCount(),
                    "warnings", bundle.assets().stream().filter(a -> Set.of("missing", "ownership_unknown", "copy_failed").contains(a.status())).toList(),
                    "indexPath", path.resolve("index.md").toString(), "model", selection.model(), "title", title));
            commitAttempted = true;
            tx.executeWithoutResult(status -> {
                check.run();
                sessions.createSessionRecord(target, selection.model(), directory, title, PermissionMode.AUTO_APPROVE);
                sessions.addMessageWithId(UUID.randomUUID().toString(), target, "user", List.of(new ContentBlock.TextBlock(body)),
                        null, 0, 0, Map.of("sessionMergeOperationId", operation));
                if (jdbc.update("UPDATE session_merges SET status='completed',stage='completed',result_json=?,usage_json=?,updated_at=? WHERE operation_id=? AND status='preparing'",
                        result, encode(usage), Instant.now().toString(), operation) != 1) throw new IllegalStateException("MERGE_STATE_CONFLICT");
            });
            committed = true;
        } catch (Throwable failure) {
            log.warn("Merge preparation failed: {}", operation, failure);
            // Before the target transaction starts, no database read is needed to prove ownership.
            if (!commitAttempted) cleanup(path);
            // Re-read durable state after an uncertain commit. Failure to read means do not clean.
            try {
                committed = "completed".equals(get(operation).status());
                if (!committed) {
                    if (commitAttempted) {
                        if (jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE id=?", Long.class, target) != 0)
                            throw new IllegalStateException("MERGE_COMMIT_STATE_UNCERTAIN");
                        cleanup(path);
                    }
                    // Reclaim space before trying to persist failure; a failed status write must not skip cleanup.
                    jdbc.update("UPDATE session_merges SET status='failed',stage='failed',error=?,usage_json=?,updated_at=? WHERE operation_id=? AND status='preparing'",
                            abort.isAborted() ? "合并超时或中断，未创建目标会话" : "合并失败，未创建目标会话：" + safeError(failure),
                            encode(usage), Instant.now().toString(), operation);
                }
            } catch (Exception uncertain) { log.error("Unable to determine or persist merge failure state: {}", operation, uncertain); }
        } finally {
            if (timer != null) timer.cancel(false);
            active.remove(abort);
            leases.forEach(SessionExecutionGate.Token::close); generation.release();
            notifyChanged(operation);
        }
        // E is already durable. Slow hooks must not retain A/B or the merge generation slot.
        if (committed) {
            try { sessions.notifySessionCreated(target); }
            catch (Exception notification) { log.warn("Merge creation hook failed: {}", operation, notification); }
        }
    }
    private String safeError(Throwable error) {
        // Provider errors may contain endpoint details. Expose a bounded diagnostic category only.
        String message = Objects.toString(error.getMessage(), "");
        return switch (message.split(":", 2)[0]) {
            case "SUMMARY_CALL_BUDGET_EXCEEDED" -> "历史过长，超出本次摘要调用上限（16 次）";
            case "SUMMARY_INCOMPLETE" -> "模型未完整生成摘要，请稍后重新合并或选择其他模型";
            case "SUMMARY_EXCEEDS_BUDGET", "HANDOFF_EXCEEDS_BUDGET" -> "生成的摘要超过交接消息容量，请选择其他模型后重试";
            case "HANDOFF_BUDGET_TOO_SMALL", "SUMMARY_INPUT_BUDGET_TOO_SMALL" -> "所选模型上下文容量不足";
            case "SOURCE_RECORD_TOO_LARGE_OR_NULL" -> "来源存在空记录或超过 16 MiB 的单条记录，已停止合并以保护正常会话";
            case "MERGE_DISK_SPACE_LOW" -> "磁盘可用空间不足以安全保存交接资料，未创建目标会话";
            case "SOURCE_MESSAGE_UNREADABLE", "CHECKPOINT_UNREADABLE", "CHECKPOINT_MESSAGE_UNREADABLE" -> "来源中有无法读取的持久化消息，未跳过这些消息";
            default -> "资料读取、生成或保存失败，请检查服务日志";
        };
    }
    private void stage(String id, String stage) {
        jdbc.update("UPDATE session_merges SET stage=?,updated_at=? WHERE operation_id=? AND status='preparing'", stage, Instant.now().toString(), id);
    }
    @SuppressWarnings("unchecked")
    public Operation get(String operation) {
        var rows = jdbc.queryForList("SELECT * FROM session_merges WHERE operation_id=?", operation);
        if (rows.isEmpty()) throw new ResourceNotFoundException("MERGE_OPERATION_NOT_FOUND", "合并操作不存在");
        var row = rows.getFirst();
        try {
            return new Operation(operation, row.get("target_session_id").toString(), row.get("status").toString(), row.get("stage").toString(),
                    json.readValue(row.get("params_json").toString(), Request.class), row.get("package_path").toString(),
                    json.readValue(row.get("result_json").toString(), Map.class), json.readTree(row.get("usage_json").toString()), (String) row.get("error"));
        } catch (java.io.IOException e) { throw new IllegalStateException("MERGE_RECORD_UNREADABLE", e); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private void cleanup(Path path) {
        try { packages.deleteUnreferenced(path); } catch (Exception e) { log.warn("Unreferenced merge package cleanup deferred: {}", path, e); }
    }
    private void notifyChanged(String operation) {
        try { if (events != null) events.publishEvent(new StatusChanged(operation)); }
        catch (Exception e) { log.warn("Merge list notification failed: {}", operation, e); }
    }
    @PreDestroy public synchronized void shutdown() {
        closing = true; active.forEach(a -> a.abort(AbortReason.SYSTEM_SHUTDOWN));
        worker.shutdown(); deadlines.shutdown();
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
