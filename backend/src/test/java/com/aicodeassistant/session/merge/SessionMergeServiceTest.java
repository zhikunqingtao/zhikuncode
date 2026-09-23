package com.aicodeassistant.session.merge;

import com.aicodeassistant.controller.*;
import com.aicodeassistant.engine.*;
import com.aicodeassistant.llm.*;
import com.aicodeassistant.model.*;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.tool.ToolInput;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class SessionMergeServiceTest {
    @TempDir Path root;
    MergeFixture f; MergeSummaryService summary; LlmProvider provider; SessionMergeService service;
    ProjectWorkspaceService workspaces; MergeProgressRepository repo;
    @BeforeEach void setup() {
        f=new MergeFixture(root); provider=mock(LlmProvider.class);
        var registry=mock(LlmProviderRegistry.class); var models=mock(ModelRegistry.class);
        when(registry.resolveModelAlias(anyString())).thenAnswer(i -> i.getArgument(0));
        when(registry.getProvider(anyString())).thenReturn(provider);
        when(models.findExplicitCapabilities(anyString(),eq(provider))).thenReturn(Optional.of(MergeSummaryServiceTest.CAPS));
        var counter=mock(TokenCounter.class);
        when(counter.estimateTokensForModel(anyString(),anyString())).thenAnswer(i -> Math.max(1,((String)i.getArgument(0)).length()/2));
        summary=new MergeSummaryService(registry,models,counter);
        stub((input,callback) -> respond(callback,answer("改动已记录；需检查当前代码")));
        workspaces=mock(ProjectWorkspaceService.class); when(workspaces.requireCurrentBinding(root.toString())).thenReturn(root);
        repo=new MergeProgressRepository(f.jdbc,f.json,new DataSourceTransactionManager(f.ds));
        service=create(); f.message("A","服务端 API 改动 SERVER_FACT"); f.message("B","前端类型调用 CLIENT_FACT");
    }
    SessionMergeService create() {
        return new SessionMergeService(f.jdbc,f.sessions,f.gate,f.packages,summary,workspaces,mock(PermissionModeManager.class),f.agents,f.swarms,f.json,new DataSourceTransactionManager(f.ds));
    }
    interface Handler { void handle(String input,StreamChatCallback callback) throws Exception; }
    void stub(Handler handler) {
        doAnswer(call -> {
            assertThat((List<?>)call.getArgument(3)).isEmpty();
            List<Map<String,Object>> input=call.getArgument(1);
            handler.handle(input.getFirst().get("content").toString(),call.getArgument(7)); return null;
        }).when(provider).streamChat(anyString(),anyList(),anyString(),anyList(),anyInt(),any(),any(),any());
    }
    static String answer(String content) { return "{\"schemaVersion\":2,\"items\":[{\"section\":\"changes\",\"content\":\""+content+"\",\"status\":\"unverified\",\"evidence\":[\"i1\"]}]}"; }
    static void respond(StreamChatCallback cb,String answer) {
        cb.onEvent(new LlmStreamEvent.TextDelta(answer)); cb.onEvent(new LlmStreamEvent.MessageDelta(new Usage(40,13370,0,0),"end_turn")); cb.onComplete();
    }
    SessionMergeService.Request request() { return new SessionMergeService.Request(List.of("A","B"),"A","E","test-model"); }
    SessionMergeService.Operation await(String id) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline) {
            var op=service.get(id);
            if(!"preparing".equals(op.status()) && (op.canResume() || !"paused".equals(op.status()))) return op;
            Thread.sleep(20);
        }
        throw new AssertionError("merge still running: "+service.get(id));
    }
    @AfterEach void shutdown() { service.shutdown(); }
    @Test void modelConfigurationRemovedAfterAdmissionHasAnActionablePauseCode() throws Exception {
        var models=(ModelRegistry)org.springframework.test.util.ReflectionTestUtils.getField(summary,"models");
        when(models.findExplicitCapabilities(anyString(),eq(provider)))
                .thenReturn(Optional.of(MergeSummaryServiceTest.CAPS)).thenReturn(Optional.empty());
        var paused=await(service.start(request(),"model-removed").operationId());
        assertThat(paused.errorCode()).isEqualTo("MERGE_MODEL_UNAVAILABLE");
        assertThat(paused.error()).contains("选择其他模型");
        assertThat(paused.targetAvailable()).isFalse();
        verifyNoInteractions(provider);
    }

    @Test void cancelLosingToPublicationReturnsAlreadyCompletedAndPreservesTarget() {
        String id=UUID.randomUUID().toString();
        repo.create(id,id,repo.encode(request()),"target",f.packages.snapshotPath(id).toString(),
                new MergeHandoffData.Execution("test-model",root.toString(),"E",MergeHandoffData.PROCESSOR_VERSION,"handoff-v2-1"));
        var racing=spy(repo);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"progress",racing);
        doAnswer(call -> {
            int version=repo.nextSnapshot(id,1);
            repo.sealed(id,1,version,"synthetic-hash",Map.of());
            repo.stage(id,1,"publishing");
            f.sessions.createSessionRecord("target","test-model",root.toString(),"E");
            repo.complete(id,1,"synthetic-ready",Map.of());
            return false;
        }).when(racing).cancel(id);
        assertThatThrownBy(() -> service.cancel(id)).isInstanceOfSatisfying(SessionMergeService.Conflict.class,
                error -> assertThat(error.code).isEqualTo("MERGE_ALREADY_COMPLETED"));
        assertThat(service.get(id).targetAvailable()).isTrue();
    }

    @Test void publishesBoundedWarningDetailsAndRetainsTheCompleteGapLedger() throws Exception {
        for(int i=0;i<55;i++) f.message("A","https://example.test/external-"+i);
        var done=await(service.start(request(),"warnings").operationId());
        assertThat(done.status()).isEqualTo("completed");
        assertThat(((Number)done.result().get("warningCount")).intValue()).isEqualTo(55);
        assertThat((List<?>)done.result().get("warnings")).hasSize(50);
        assertThat(Files.readAllLines(Path.of(done.packagePath()).resolve("snapshot/gaps.jsonl"))).hasSize(55);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={2,3,4,5})
    void publishesTwoThroughFiveSourcesAtomicallyWithIndependentHistory(int count) throws Exception {
        for(String id:List.of("D","E")) f.sessions.createSessionRecord(id,"test-model",root.toString(),id);
        List<String> ids=List.of("A","B","C","D","E").subList(0,count);
        var before=f.jdbc.queryForList("SELECT * FROM messages ORDER BY id");
        var req=new SessionMergeService.Request(ids,"A","target","test-model");
        var operation=await(service.start(req,"key").operationId());
        assertThat(operation.status()).isEqualTo("completed"); assertThat(operation.targetAvailable()).isTrue();
        assertThat(operation.lockedSourceSessionIds()).isEmpty(); assertThat(operation.protocolVersion()).isEqualTo(2);
        assertThat(f.sessions.loadSession(operation.targetSessionId()).orElseThrow().config())
                .containsEntry("sessionMergeOperationId",operation.operationId());
        assertThat(f.jdbc.queryForList("SELECT * FROM messages WHERE session_id<>? ORDER BY id",operation.targetSessionId())).isEqualTo(before);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE session_id=?",Integer.class,operation.targetSessionId())).isEqualTo(1);
        var reversed=new ArrayList<>(ids); Collections.reverse(reversed);
        assertThat(service.start(new SessionMergeService.Request(reversed,"A","target","test-model"),"key").targetSessionId()).isEqualTo(operation.targetSessionId());
        assertThatThrownBy(() -> service.cancel(operation.operationId())).isInstanceOf(SessionMergeService.Conflict.class);
    }
    @Test void damagedCheckpointPausesWithoutPublishingAndCannotResumePastTheGap() throws Exception {
        f.jdbc.update("INSERT INTO agent_checkpoints(id,run_id,session_id,agent_id,seq,messages_json,created_at) VALUES('cp','run','A','agent',0,?,'now')","not-json");
        var paused=await(service.start(request(),"corrupt-checkpoint").operationId());
        assertThat(paused.status()).isEqualTo("paused");
        assertThat(paused.errorCode()).isEqualTo("RECORD_REQUIRES_HANDLING");
        assertThat(paused.targetAvailable()).isFalse();
        Path packagePath=Path.of(paused.packagePath());
        assertThat(Files.exists(packagePath.resolve("snapshot/seal.json"))).isTrue();
        assertThat(Files.exists(packagePath.resolve("work/recovered/seal.json"))).isFalse();
        var retried=await(service.resume(paused.operationId(),new SessionMergeService.Resume(paused.runEpoch(),null)).operationId());
        assertThat(retried.status()).isEqualTo("paused");
        assertThat(retried.targetAvailable()).isFalse();
        verifyNoInteractions(provider);
    }

    @Test void sealedSnapshotReleasesSourcesBeforeFirstProviderCallAndExcludesLaterMessages() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        stub((input,cb) -> { entered.countDown(); assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); respond(cb,answer("资料")); });
        var operation=service.start(request(),"release");
        try {
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(service.get(operation.operationId()).snapshotSealed()).isTrue();
            assertThat(f.gate.isBusy(f.ds,"A")).isFalse();
            f.message("A","AFTER_CUTOFF");
            assertThat(service.get(operation.operationId()).targetAvailable()).isFalse();
        } finally { release.countDown(); }
        var done=await(operation.operationId()); assertThat(done.status()).isEqualTo("completed");
        var reads=new HandoffReadService(repo,f.json);
        assertThat(reads.read(done.targetSessionId(),ToolInput.from(Map.of("action","search","query","SERVER_FACT")))).contains("SERVER_FACT");
        assertThat(reads.read(done.targetSessionId(),ToolInput.from(Map.of("action","search","query","AFTER_CUTOFF")))).contains("\"entries\":[]");
    }
    @Test void failureRetainsUnitsAndResumeDoesNotRecallCompletedUnits() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        stub((input,cb) -> { if(calls.incrementAndGet()==2) throw new LlmApiException("denied",false,401); respond(cb,answer("saved")); });
        var paused=await(service.start(request(),"resume").operationId());
        assertThat(paused.status()).isEqualTo("paused"); assertThat(paused.canResume()).isTrue();
        assertThat(paused.progress().completedUnits()).isEqualTo(1); assertThat(paused.targetAvailable()).isFalse();
        assertThat(Files.exists(Path.of(paused.packagePath()).resolve("snapshot/seal.json"))).isTrue();
        var old=repo.units(paused.operationId(),"extracting").getFirst();
        assertThatThrownBy(() -> service.start(request(),"second")).isInstanceOf(SessionMergeService.Conflict.class);
        assertThatThrownBy(() -> service.resume(paused.operationId(),new SessionMergeService.Resume(paused.runEpoch()-1,null))).isInstanceOf(SessionMergeService.Conflict.class);
        var done=await(service.resume(paused.operationId(),new SessionMergeService.Resume(paused.runEpoch(),"another-model")).operationId());
        assertThat(done.status()).isEqualTo("completed"); assertThat(done.runEpoch()).isEqualTo(paused.runEpoch()+1);
        assertThat(repo.unit(done.operationId(),old.unitId()).orElseThrow().attemptCount()).isEqualTo(1);
    }
    @Test void cancelFencesLateResponseAndDefersCleanupUntilWriterStops() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        stub((input,cb) -> { entered.countDown(); release.await(10,TimeUnit.SECONDS); respond(cb,answer("late")); });
        var op=service.start(request(),"cancel");
        try {
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(service.cancel(op.operationId()).status()).isEqualTo("cancelled");
            assertThat(service.cancel(op.operationId()).status()).isEqualTo("cancelled");
            assertThatThrownBy(() -> service.start(request(),"new")).isInstanceOf(SessionMergeService.Conflict.class);
            assertThat(Files.exists(Path.of(op.packagePath()))).isTrue();
        } finally { release.countDown(); }
        for(int n=0;n<200 && Files.exists(Path.of(op.packagePath()));n++) Thread.sleep(20);
        assertThat(Files.exists(Path.of(op.packagePath()))).isFalse();
        assertThat(service.get(op.operationId()).status()).isEqualTo("cancelled");
        assertThat(service.get(op.operationId()).targetAvailable()).isFalse();
    }
    @Test void idleRootAndDescendantsAreBothRequired() {
        try(var lease=f.gate.tryAcquire(f.ds,"B")) { assertThatThrownBy(() -> service.start(request(),"busy")).hasMessageContaining("来源"); }
        f.sessions.registerSubAgentSession("child",root.toString(),"A");
        try(var lease=f.gate.tryAcquire(f.ds,"child")) { assertThatThrownBy(() -> service.start(request(),"busy-child")).hasMessageContaining("来源"); }
        assertThat(f.gate.isBusy(f.ds,"A")).isFalse(); assertThat(repo.active()).isEmpty();
    }
    @Test void failedPublicationRollsBackTargetAndPreservesPreparedFiles() throws Exception {
        when(workspaces.requireCurrentBinding(root.toString())).thenReturn(root).thenThrow(new IllegalStateException("BINDING_REVOKED"));
        var op=await(service.start(request(),"binding").operationId());
        assertThat(op.status()).isEqualTo("paused"); assertThat(op.targetAvailable()).isFalse();
        assertThat(Files.exists(Path.of(op.packagePath()).resolve("handoff/ready.json"))).isTrue();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE session_id=?",Integer.class,op.targetSessionId())).isZero();
    }
    @Test void activeApiResumeConflictAndDeletedTargetRemainAuthoritative() throws Exception {
        var mvc=standaloneSetup(new SessionMergeController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/session-merges/active")).andExpect(status().isNoContent());
        stub((input,cb) -> { throw new LlmApiException("denied",false,401); });
        var op=await(service.start(request(),"http").operationId());
        mvc.perform(get("/api/session-merges/active")).andExpect(jsonPath("$.status").value("paused"));
        mvc.perform(post("/api/session-merges/"+op.operationId()+"/resume").contentType("application/json").content("{\"expectedEpoch\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MERGE_STALE_OPERATION"));
        mvc.perform(get("/api/session-merges/missing")).andExpect(status().isNotFound());
        service.cancel(op.operationId()); mvc.perform(get("/api/session-merges/active")).andExpect(status().isNoContent());
    }
    @Test void uncertainCommitKeepsTheDurableTargetAndItsPackage() throws Exception {
        service.shutdown();
        var once=new java.util.concurrent.atomic.AtomicBoolean();
        var transactions=new DataSourceTransactionManager(f.ds) {
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
                super.doCommit(status);
                if(f.jdbc.queryForObject("SELECT COUNT(*) FROM session_merges WHERE status='completed'",Integer.class)>0 && once.compareAndSet(false,true))
                    throw new org.springframework.transaction.TransactionSystemException("commit acknowledgement lost");
            }
        };
        service=new SessionMergeService(f.jdbc,f.sessions,f.gate,f.packages,summary,workspaces,mock(PermissionModeManager.class),f.agents,f.swarms,f.json,transactions);
        var op=await(service.start(request(),"uncertain").operationId());
        assertThat(op.status()).isEqualTo("completed"); assertThat(op.targetAvailable()).isTrue();
        assertThat(Files.exists(Path.of(op.packagePath()).resolve("handoff/ready.json"))).isTrue();
    }

    @Test void sealedRenameBeforeLedgerCommitRecoversWithoutSources() throws Exception {
        String id=UUID.randomUUID().toString(); Path path=f.packages.snapshotPath(id);
        repo.create(id,id,f.json.writeValueAsString(request()),"target-recovered",path.toString(),
                new MergeHandoffData.Execution("test-model",root.toString(),"Recovered",MergeHandoffData.PROCESSOR_VERSION,"handoff-v2-1"));
        int version=repo.nextSnapshot(id,1); f.packages.seal(path,List.of("A","B"),version,()->{});
        f.jdbc.update("DELETE FROM messages WHERE session_id IN ('A','B')"); f.jdbc.update("DELETE FROM sessions WHERE id IN ('A','B')");
        service.recover();
        assertThat(await(id).status()).isEqualTo("completed");
    }
    @Test void targetDeletionRevokesBindingImmediatelyAndCleanupStaysInsideMergeLifecycle() throws Exception {
        var op=await(service.start(request(),"deletion").operationId());
        f.jdbc.update("DELETE FROM messages WHERE session_id=?",op.targetSessionId());
        f.jdbc.update("DELETE FROM sessions WHERE id=?",op.targetSessionId());
        assertThat(service.get(op.operationId()).targetAvailable()).isFalse();
        assertThat(repo.binding(op.targetSessionId())).isEmpty();
        assertThat(Files.exists(Path.of(op.packagePath()))).isTrue();
        service.recover();
        assertThat(Files.exists(Path.of(op.packagePath()))).isFalse();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE id IN ('A','B')",Integer.class)).isEqualTo(2);
    }

    @Test void unexpectedRestartContinuesSealedSnapshotWithoutLiveSources() throws Exception {
        stub((input,cb) -> { throw new LlmApiException("denied",false,401); });
        var paused=await(service.start(request(),"restart").operationId());
        service.shutdown(); f.jdbc.update("UPDATE session_merges SET status='preparing' WHERE operation_id=?",paused.operationId());
        f.jdbc.update("DELETE FROM messages WHERE session_id IN ('A','B')"); f.jdbc.update("DELETE FROM sessions WHERE id IN ('A','B')");
        stub((input,cb) -> respond(cb,answer("recovered")));
        service=create(); service.recover();
        var done=await(paused.operationId()); assertThat(done.status()).isEqualTo("completed");
        assertThat(done.runEpoch()).isEqualTo(paused.runEpoch()+1);
    }
}
