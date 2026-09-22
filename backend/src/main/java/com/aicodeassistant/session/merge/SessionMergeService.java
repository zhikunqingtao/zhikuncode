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
            Request request, String packagePath, Map<String,Object> result, Object usage, String error,
            int protocolVersion, long runEpoch, boolean snapshotSealed, List<String> lockedSourceSessionIds,
            MergeHandoffData.Progress progress, String retryAt, String errorCode,
            boolean canResume, boolean canCancel, boolean targetAvailable) { }
    public record Resume(long expectedEpoch, String model) { }
    public static final class Conflict extends RuntimeException {
        public final String code; public final Operation operation;
        Conflict(String code,String message,Operation operation) { super(message); this.code=code; this.operation=operation; }
    }
    private final JdbcTemplate jdbc;
    private final SessionManager sessions;
    private final SessionExecutionGate gate;
    private final MergePackageService packages;
    private final MergeSummaryService summaries;
    private final ProjectWorkspaceService workspaces;
    private final BackgroundAgentTracker agents;
    private final SwarmService swarms;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final MergeProgressRepository progress;
    private final ExecutorService worker=Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String,AbortContext> writers=new ConcurrentHashMap<>();
    private volatile boolean closing;
    public record StatusChanged(String operationId) { }
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationEventPublisher events;

    public SessionMergeService(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc, SessionManager sessions,
            SessionExecutionGate gate, MergePackageService packages, MergeSummaryService summaries,
            ProjectWorkspaceService workspaces, PermissionModeManager permissions, BackgroundAgentTracker agents,
            SwarmService swarms, ObjectMapper json,
            @Qualifier("projectTransactionManager") PlatformTransactionManager transactions) {
        this.jdbc=jdbc; this.sessions=sessions; this.gate=gate; this.packages=packages; this.summaries=summaries;
        this.workspaces=workspaces; this.agents=agents; this.swarms=swarms; this.json=json;
        this.tx=new TransactionTemplate(transactions); this.progress=new MergeProgressRepository(jdbc,json,transactions);
    }
    @PostConstruct public synchronized void recover() {
        for(var row:progress.interrupted()) {
            var resumed=progress.resume(row.operationId(),row.runEpoch(),row.execution(),true);
            launch(resumed,new ArrayList<>());
        }
        cleanupFinished();
    }
    public synchronized Operation start(Request raw,String key) {
        if(closing) throw conflict("MERGE_SERVICE_SHUTDOWN","服务正在关闭",null);
        if(key==null || key.isBlank() || key.length()>128) throw bad("需要不超过 128 字符的幂等键");
        Request request=normalize(raw); String params=progress.encode(request);
        var previous=progress.byKey(key);
        if(previous.isPresent()) {
            if(!params.equals(previous.get().paramsJson())) throw conflict("MERGE_IDEMPOTENCY_CONFLICT","幂等键已用于不同参数",get(previous.get().operationId()));
            return get(previous.get().operationId());
        }
        var active=progress.active();
        if(active.isPresent()) throw conflict("MERGE_ACTIVE_EXISTS","已有未结束的合并，请恢复或取消",get(active.get().operationId()));
        if(!writers.isEmpty()) throw conflict("MERGE_CANCEL_CLEANUP","取消操作仍在停止写入及清理，请稍后重试",null);
        cleanupFinished(); // Reclaim deleted targets only within merge work, never from ordinary session deletion.
        String id=UUID.randomUUID().toString();
        List<SessionExecutionGate.Token> leases=lockSources(request,id); boolean launched=false;
        try {
            for(String source:request.sourceSessionIds()) {
                var info=sourceInfo(source);
                if(info.metadata().has("parent_session_id") || "subagent".equals(info.metadata().path("type").asText())) throw bad("只能选择普通会话");
            }
            var primary=sourceInfo(request.primarySessionId());
            String directory=workspaces.requireCurrentBinding(primary.workingDir()).toString();
            var selected=summaries.select(request.model()==null?primary.model():request.model());
            String title=request.title()==null?"合并 · "+Objects.toString(primary.title(),"会话"):request.title();
            progress.create(id,key,params,UUID.randomUUID().toString(),packages.snapshotPath(id).toString(),
                    new MergeHandoffData.Execution(selected.model(),directory,title,MergeHandoffData.PROCESSOR_VERSION,"handoff-v2-1"));
            launch(progress.find(id).orElseThrow(),leases); launched=true;
            notifyChanged(id); return get(id);
        } finally { if(!launched) leases.forEach(SessionExecutionGate.Token::close); }
    }
    private List<SessionExecutionGate.Token> lockSources(Request request,String operation) {
        var leases=new ArrayList<SessionExecutionGate.Token>();
        try {
            List<String> ids=packages.descendants(request.sourceSessionIds()).stream().sorted().toList();
            for(String id:ids) {
                var lease=gate.tryAcquireMerge(sessions.dataSourceIdentity(),id,operation);
                if(lease==null) throw conflict("MERGE_SOURCE_BUSY","来源会话或子任务仍在执行："+id,null);
                leases.add(lease);
            }
            if(!ids.equals(packages.descendants(request.sourceSessionIds()).stream().sorted().toList()))
                throw conflict("MERGE_SOURCE_BUSY","来源子任务刚发生变化，请重试",null);
            for(String id:ids) {
                if(!agents.getActiveAgentIds(id).isEmpty() || swarms.hasActiveSwarm(id)
                        || jdbc.queryForObject("SELECT COUNT(*) FROM run_envelopes WHERE session_id=? AND status NOT IN ('completed','failed','cancelled','interrupted')",Long.class,id)>0)
                    throw conflict("MERGE_SOURCE_BUSY","来源或子任务仍在执行、等待审批或取消中："+id,null);
            }
            return leases;
        } catch(RuntimeException e) { leases.forEach(SessionExecutionGate.Token::close); throw e; }
    }
    private void launch(MergeHandoffData.Ledger ledger,List<SessionExecutionGate.Token> leases) {
        AbortContext abort=new AbortContext();
        if(writers.putIfAbsent(ledger.operationId(),abort)!=null) throw conflict("MERGE_STALE_OPERATION","该合并仍在执行",get(ledger.operationId()));
        try { worker.execute(() -> execute(ledger,leases,abort)); }
        catch(RuntimeException e) { writers.remove(ledger.operationId()); progress.pause(ledger.operationId(),ledger.runEpoch(),"MERGE_START_FAILED","无法启动整理，进度已保留"); throw e; }
    }
    public synchronized Operation resume(String id,Resume request) {
        var ledger=progress.find(id).orElseThrow(() -> new ResourceNotFoundException("MERGE_OPERATION_NOT_FOUND","合并不存在"));
        if(closing || writers.containsKey(id) || !"paused".equals(ledger.status()) || request.expectedEpoch()!=ledger.runEpoch())
            throw conflict("MERGE_STALE_OPERATION","状态已变化，请刷新后重试",get(id));
        var execution=ledger.execution();
        var selected=summaries.select(request.model()==null || request.model().isBlank()?execution.resolvedModel():request.model());
        var next=new MergeHandoffData.Execution(selected.model(),execution.workingDirectory(),execution.targetTitle(),execution.processorVersion(),execution.promptVersion());
        var resumed=progress.resume(id,request.expectedEpoch(),next,false);
        launch(resumed,new ArrayList<>()); notifyChanged(id); return get(id);
    }
    public synchronized Operation cancel(String id) {
        var state=get(id);
        if("completed".equals(state.status())) throw conflict("MERGE_ALREADY_COMPLETED","合并已完成，不能取消",state);
        if("cancelled".equals(state.status())) return state;
        if(!progress.cancel(id)) throw conflict("MERGE_STALE_OPERATION","状态已变化",get(id));
        AbortContext abort=writers.get(id);
        if(abort!=null) abort.abort(AbortReason.USER_INTERRUPT); else cleanupFinished();
        notifyChanged(id); return get(id);
    }
    public Optional<Operation> active() { return progress.active().map(row -> get(row.operationId())); }
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

    private void execute(MergeHandoffData.Ledger initial,List<SessionExecutionGate.Token> initialLeases,AbortContext abort) {
        String id=initial.operationId(); long epoch=initial.runEpoch(); Path path=Path.of(initial.packagePath());
        List<SessionExecutionGate.Token> leases=initialLeases;
        Runnable check=() -> {
            if(closing || abort.isAborted()) throw new IllegalStateException("MERGE_INTERRUPTED");
            progress.assertCurrent(id,epoch);
        };
        boolean committed=false;
        try {
            check.run(); var ledger=initial; var request=progress.decode(ledger.paramsJson(),Request.class);
            MergePackageService.Snapshot snapshot;
            if(ledger.snapshotHash()==null) {
                if(java.nio.file.Files.exists(path.resolve("snapshot/seal.json"))) {
                    snapshot=packages.validateSnapshot(path,null,check);
                    if(snapshot.version()!=ledger.snapshotVersion()) throw new java.io.IOException("MERGE_SNAPSHOT_VERSION_MISMATCH");
                } else {
                    if(leases.isEmpty()) leases=lockSources(request,id);
                    int version=progress.nextSnapshot(id,epoch);
                    snapshot=packages.seal(path,request.sourceSessionIds(),version,check);
                }
                progress.sealed(id,epoch,snapshot.version(),snapshot.hash(),Map.of("copiedCount",snapshot.copiedCount(),"warningCount",snapshot.warningCount()));
            } else snapshot=packages.validateSnapshot(path,ledger.snapshotHash(),check);
            leases.forEach(SessionExecutionGate.Token::close); leases=List.of(); notifyChanged(id);
            if(snapshot.blockedReason()!=null) {
                if(snapshot.blockedReason().startsWith("RECORD_REQUIRES_HANDLING")) packages.recoverBlockedProjections(path,check);
                else throw new java.io.IOException(snapshot.blockedReason());
            }
            ledger=progress.find(id).orElseThrow();
            var selected=summaries.select(ledger.execution().resolvedModel());
            var prepared=summaries.prepare(ledger,progress,packages,selected,abort,check);
            check.run(); workspaces.requireCurrentBinding(ledger.execution().workingDirectory());
            packages.validateSnapshot(path,ledger.snapshotHash(),check);
            HandoffReadService.verifyPrepared(path,ledger.snapshotHash(),prepared.hash(),json);
            progress.stage(id,epoch,"publishing");
            var ready=ledger;
            tx.executeWithoutResult(status -> {
                check.run();
                sessions.createSessionRecord(ready.targetSessionId(),selected.model(),ready.execution().workingDirectory(),ready.execution().targetTitle(),PermissionMode.AUTO_APPROVE);
                jdbc.update("UPDATE sessions SET metadata_json=? WHERE id=?",
                        progress.encode(Map.of("sessionMergeOperationId",id)),ready.targetSessionId());
                sessions.addMessageWithId(UUID.randomUUID().toString(),ready.targetSessionId(),"user",List.of(new ContentBlock.TextBlock(prepared.body())),
                        null,0,0,Map.of("sessionMergeOperationId",id));
                progress.complete(id,epoch,prepared.hash(),Map.of("copiedCount",snapshot.copiedCount(),"warningCount",snapshot.warningCount(),
                        "model",selected.model(),"title",ready.execution().targetTitle(),"indexPath",path.resolve("handoff/handoff.md").toString()));
            });
            committed=true;
        } catch(Throwable e) {
            log.warn("Merge paused; snapshot and committed units retained: id={}, code={}, type={}",id,safeCode(e),e.getClass().getSimpleName());
            try {
                // A driver may report failure after COMMIT; durable completion is authoritative.
                committed=progress.find(id).map(row -> "completed".equals(row.status())).orElse(false)
                        && jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE id=?",Integer.class,initial.targetSessionId())==1;
                if(!committed) {
                    String code=closing?"SERVICE_SHUTDOWN":safeCode(e);
                    progress.pause(id,epoch,code,"合并已暂停，尚未发布目标；已保存的原件与进度保留。"+explain(code));
                }
            } catch(Exception failure) { log.error("Unable to persist merge pause: {}",id,failure); }
        } finally {
            leases.forEach(SessionExecutionGate.Token::close);
            // Do not release the in-process cleanup guard before the old writer has stopped touching its package.
            try { cleanupFinished(id); } finally { writers.remove(id,abort); notifyChanged(id); }
        }
        if(committed) try { sessions.notifySessionCreated(initial.targetSessionId()); }
        catch(Exception e) { log.warn("Merge creation notification failed: {}",id,e); }
    }
    private String explain(String code) {
        return switch(code) {
            case "MERGE_PROVIDER_AUTH" -> "请修正模型凭据或访问权限后恢复。";
            case "MERGE_PROVIDER_QUOTA" -> "模型服务额度不足，请补充额度或选择其他模型后恢复。";
            case "MERGE_PROVIDER_UNAVAILABLE", "MERGE_PROVIDER_ERROR", "MERGE_CALL_TIMEOUT", "MERGE_RESPONSE_INCOMPLETE" -> "模型调用暂未成功，请稍后恢复；也可选择其他模型。";
            case "MERGE_MODEL_UNAVAILABLE", "MERGE_MODEL_BUDGET_TOO_SMALL" -> "所选模型不可用或容量不足，请选择其他模型后恢复。";
            case "RECORD_REQUIRES_HANDLING", "MERGE_MIN_UNIT_FAILED" -> "原件解析或模型整理仍未通过，未跳过该部分；请检查日志及原件后再恢复。";
            case "MERGE_DISK_SPACE_LOW", "MERGE_COPY_INCOMPLETE" -> "磁盘、复制配额或文件一致性检查未通过；未复制部分不算已保存，请排查后恢复。";
            case "MERGE_SOURCE_BUSY" -> "来源仍有执行占用，等待任务退出后恢复。";
            default -> "原因："+code+"；请排查后恢复，或取消本次合并。";
        };
    }
    private String safeCode(Throwable e) {
        if(e instanceof Conflict conflict) return conflict.code;
        if(e instanceof com.aicodeassistant.llm.LlmApiException api) {
            if(api.getHttpStatus()==401 || api.getHttpStatus()==403) return "MERGE_PROVIDER_AUTH";
            if(api.getHttpStatus()==402 || Objects.toString(api.getErrorType(),"").contains("quota")) return "MERGE_PROVIDER_QUOTA";
            if(api.getHttpStatus()==404) return "MERGE_MODEL_UNAVAILABLE";
            return "MERGE_PROVIDER_UNAVAILABLE";
        }
        String message=Objects.toString(e.getMessage(),"").split(":",2)[0];
        return message.matches("[A-Z][A-Z0-9_]{2,100}")?message:"MERGE_PREPARATION_FAILED";
    }
    @SuppressWarnings("unchecked") public Operation get(String id) {
        var r=progress.find(id).orElseThrow(() -> new ResourceNotFoundException("MERGE_OPERATION_NOT_FOUND","合并操作不存在"));
        Request request=progress.decode(r.paramsJson(),Request.class);
        List<String> locked=request.sourceSessionIds().stream().filter(source -> id.equals(gate.mergeOperationId(sessions.dataSourceIdentity(),source))).toList();
        boolean available="completed".equals(r.status()) && jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE id=?",Integer.class,r.targetSessionId())==1;
        return new Operation(id,r.targetSessionId(),r.status(),r.stage(),request,r.packagePath(),progress.decode(r.resultJson(),Map.class),
                progress.decode(r.usageJson(),Object.class),r.error(),r.protocolVersion(),r.runEpoch(),r.snapshotHash()!=null,locked,
                progress.progress(id,r.stage()),r.retryAt(),r.errorCode(),"paused".equals(r.status()) && !writers.containsKey(id),
                r.protocolVersion()==2 && Set.of("preparing","paused","failed").contains(r.status()),available);
    }
    private void cleanupFinished() { cleanupFinished(null); }
    private void cleanupFinished(String stoppedWriter) {
        for(var row:progress.cleanupCandidates()) try {
            if(writers.containsKey(row.operationId()) && !row.operationId().equals(stoppedWriter)) continue;
            packages.deleteUnreferenced(Path.of(row.packagePath())); }
        catch(Exception e) { log.warn("Merge cleanup deferred: {}",row.operationId(),e); }
    }
    private void notifyChanged(String id) {
        try { if(events!=null) events.publishEvent(new StatusChanged(id)); }
        catch(Exception e) { log.warn("Merge notification failed: {}",id,e); }
    }
    @PreDestroy public synchronized void shutdown() {
        closing=true;
        for(var entry:writers.entrySet()) {
            progress.find(entry.getKey()).ifPresent(row -> progress.pause(row.operationId(),row.runEpoch(),"SERVICE_SHUTDOWN","服务关闭，进度已保留，请恢复合并"));
            entry.getValue().abort(AbortReason.SYSTEM_SHUTDOWN);
        }
        worker.shutdown();
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private static Conflict conflict(String code,String message,Operation operation) { return new Conflict(code,message,operation); }
}
