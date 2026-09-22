package com.aicodeassistant.session.merge;

import com.aicodeassistant.engine.*;
import com.aicodeassistant.llm.*;
import com.aicodeassistant.model.Usage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.aicodeassistant.session.merge.MergeHandoffData.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MergeSummaryServiceTest {
    static final ModelCapabilities CAPS=new ModelCapabilities("test-model","Test",65536,131072,true,true,false,0,true,.01,.02);
    @TempDir Path root;
    MergeFixture f; MergeProgressRepository repo; MergeSummaryService summary; LlmProvider provider;
    @BeforeEach void setup() {
        f=new MergeFixture(root); repo=new MergeProgressRepository(f.jdbc,f.json,new DataSourceTransactionManager(f.ds));
        var tokens=mock(TokenCounter.class);
        when(tokens.estimateTokensForModel(anyString(),anyString())).thenAnswer(i -> Math.max(1,((String)i.getArgument(0)).length()/2));
        summary=new MergeSummaryService(mock(LlmProviderRegistry.class),mock(ModelRegistry.class),tokens); provider=mock(LlmProvider.class);
    }
    Ledger ledger(String history) throws Exception {
        f.message("A",history); f.message("B","frontend contract /api/test ; failed test ; todo rerun");
        String id=UUID.randomUUID().toString(); Path path=f.packages.snapshotPath(id);
        repo.create(id,id,f.json.writeValueAsString(new SessionMergeService.Request(List.of("A","B"),"A","E","test-model")),UUID.randomUUID().toString(),path.toString(),
                new Execution("test-model",root.toString(),"E",PROCESSOR_VERSION,"handoff-v2-1"));
        int version=repo.nextSnapshot(id,1); var snapshot=f.packages.seal(path,List.of("A","B"),version,()->{});
        repo.sealed(id,1,version,snapshot.hash(),Map.of()); return repo.find(id).orElseThrow();
    }
    void stub(List<String> inputs,String answer,boolean reported) {
        doAnswer(call -> {
            List<Map<String,Object>> messages=call.getArgument(1); inputs.add(messages.getFirst().get("content").toString());
            assertThat((List<?>)call.getArgument(3)).isEmpty(); StreamChatCallback cb=call.getArgument(7);
            cb.onEvent(new LlmStreamEvent.TextDelta(answer));
            cb.onEvent(new LlmStreamEvent.MessageDelta(reported?new Usage(40,13370,0,0):null,"end_turn")); cb.onComplete(); return null;
        }).when(provider).streamChat(anyString(),anyList(),anyString(),anyList(),anyInt(),any(),any(),any());
    }
    MergeSummaryService.Prepared prepare(Ledger ledger) throws Exception {
        return summary.prepare(ledger,repo,f.packages,new MergeSummaryService.Selection("test-model",provider,CAPS),new AbortContext(),
                () -> repo.assertCurrent(ledger.operationId(),ledger.runEpoch()));
    }
    @Test void validatesSchemaAndEvidenceAliasesWithoutTrustingModelPaths() throws Exception {
        UnitInput input=new UnitInput(List.of(new InputRef("source-ref","A",20,40)),List.of());
        Detail d=summary.validate(SessionMergeServiceTest.answer("recorded"),input,"u");
        assertThat(d.items().getFirst().evidence()).containsExactly("source-ref@20:40");
        for(String invalid:List.of("{}",SessionMergeServiceTest.answer("x")+" trailing",SessionMergeServiceTest.answer("x").replace("i1","../../secret"),
                SessionMergeServiceTest.answer("x").replace("unverified","approved"),SessionMergeServiceTest.answer("x").replace("i1","i2")))
            assertThatThrownBy(() -> summary.validate(invalid,input,"u")).hasMessage("MERGE_INVALID_JSON");
    }
    @Test void separatesUtf8BytesVisibleTokensAndReasoningAndAcceptsUnknownUsage() throws Exception {
        var ledger=ledger("source"); var inputs=new ArrayList<String>();
        String answer=SessionMergeServiceTest.answer("说明".repeat(750)); // > 4,183 UTF-8 bytes, synthetic regression
        stub(inputs,answer,false);
        var prepared=prepare(ledger);
        assertThat(prepared.body()).contains("HandoffRead");
        assertThat(repo.units(ledger.operationId(),"extracting")).allMatch(u -> u.state().equals("completed"));
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM session_merge_attempts WHERE usage_reported=1",Integer.class)).isZero();
        assertThat(f.jdbc.queryForList("SELECT estimated_cost_usd FROM session_merge_attempts")).allSatisfy(row -> assertThat(row.get("estimated_cost_usd")).isNull());
    }
    @Test void processesMoreThanSixteenUnitsAndPreservesTailAndDetailedResults() throws Exception {
        var ledger=ledger("长历史 development ".repeat(40000)+"FINAL_SERVER_TAIL");
        var inputs=new ArrayList<String>(); stub(inputs,SessionMergeServiceTest.answer("short detail"),true);
        var prepared=prepare(ledger);
        assertThat(inputs.size()).isGreaterThan(16); assertThat(String.join("\n",inputs)).contains("FINAL_SERVER_TAIL","/api/test");
        assertThat(repo.units(ledger.operationId(),"extracting")).hasSizeGreaterThan(16);
        assertThat(summary.capacity(prepared.body(),"test-model")).isLessThanOrEqualTo(2048);
        assertThat(Files.readString(Path.of(ledger.packagePath()).resolve("handoff/details.jsonl"))).contains("sha256","sourceId");
    }
    @Test void provider413SplitsOnlyFailedUnitIntoStrictlySmallerInputs() throws Exception {
        var ledger=ledger("long input ".repeat(2000)); AtomicInteger rejected=new AtomicInteger();
        doAnswer(call -> {
            List<Map<String,Object>> messages=call.getArgument(1); String input=messages.getFirst().get("content").toString();
            if(input.length()>4000) { rejected.incrementAndGet(); throw new LlmApiException("large",false,413); }
            SessionMergeServiceTest.respond(call.getArgument(7),SessionMergeServiceTest.answer("partial")); return null;
        }).when(provider).streamChat(anyString(),anyList(),anyString(),anyList(),anyInt(),any(),any(),any());
        prepare(ledger); assertThat(rejected.get()).isPositive();
        assertThat(repo.units(ledger.operationId(),"extracting")).anyMatch(u -> u.state().equals("split"));
        assertThat(repo.units(ledger.operationId(),"extracting")).allMatch(u -> Set.of("split","completed").contains(u.state()));
    }
    @Test void rejectsCorruptedCommittedUnitOnResumeWithoutRepayingItsCall() throws Exception {
        var ledger=ledger("original"); var inputs=new ArrayList<String>(); stub(inputs,SessionMergeServiceTest.answer("saved"),true);
        prepare(ledger); int count=inputs.size();
        Unit unit=repo.units(ledger.operationId(),"extracting").getFirst();
        Files.writeString(Path.of(ledger.packagePath()).resolve(unit.resultPath()),"tampered");
        assertThatThrownBy(() -> prepare(ledger)).hasMessage("MERGE_RESULT_HASH_MISMATCH");
        assertThat(inputs).hasSize(count);
    }
    @Test void generationReservesReasoningButNotEntireContext() {
        var selected=new MergeSummaryService.Selection("test-model",provider,CAPS);
        assertThat(summary.generationBudget(selected,2048)).isEqualTo(32768+2048);
        assertThat(summary.capacity("中文","test-model")).isEqualTo(1);
    }
}
