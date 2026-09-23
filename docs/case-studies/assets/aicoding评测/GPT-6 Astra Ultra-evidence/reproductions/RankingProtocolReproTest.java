package com.aicodeassistant.session.merge;

import com.aicodeassistant.engine.*;
import com.aicodeassistant.llm.*;
import com.aicodeassistant.tool.ToolInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.aicodeassistant.session.merge.MergeHandoffData.*;

/** Observational reproductions: passing means the documented behavior was observed, not fixed. */
class RankingProtocolReproTest {
    @TempDir Path root;

    @Test void explicitHistoricalReferenceCanCopyThroughIntermediateSymlink() throws Exception {
        var f=new MergeFixture(root);
        Path outside=Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("proof.txt"),"SYNTHETIC_OUTSIDE_PROOF");
        // Match SystemScratchpadPathPolicy's canonical root (macOS /var is an alias).
        Path own=Files.createDirectories(root.resolve("scratch/A")).toRealPath();
        Path link=Files.createSymbolicLink(own.resolve("link"),outside);
        f.message("A",link.resolve("proof.txt").toString());
        Path path=f.packages.snapshotPath(UUID.randomUUID().toString());
        f.packages.seal(path,List.of("A","B"),1,()->{});
        var assets=new ArrayList<String>();
        for(String line:Files.readAllLines(path.resolve("snapshot/files.jsonl"))) {
            var file=f.json.readValue(line,FileEntry.class);
            if(file.kind().equals("asset")) assets.add(Files.readString(path.resolve(file.path())));
        }
        assertThat(assets).contains("SYNTHETIC_OUTSIDE_PROOF");
    }

    @Test void malformedExternalReferenceAbortsSealInsteadOfBeingAnExternalGap() throws Exception {
        var f=new MergeFixture(root); f.message("A","/missing/ref\u0000.txt");
        Path path=f.packages.snapshotPath(UUID.randomUUID().toString());
        assertThatThrownBy(()->f.packages.seal(path,List.of("A","B"),1,()->{}))
                .isInstanceOf(InvalidPathException.class);
        assertThat(Files.exists(path.resolve("snapshot/seal.json"))).isFalse();
    }

    @Test void correctedParserConfigurationStillCannotPassUnboundGapProtocol() throws Exception {
        var f=new MergeFixture(root); f.message("A","placeholder");
        String legacy="[{'type':'text','text':'SYNTHETIC_RECOVERABLE_TEXT'}]";
        f.jdbc.update("UPDATE messages SET content_json=? WHERE session_id='A'",legacy);
        Path path=f.packages.snapshotPath(UUID.randomUUID().toString());
        f.packages.seal(path,List.of("A","B"),1,()->{});
        assertThat(Files.readString(path.resolve("snapshot/gaps.jsonl")))
                .contains("\"reason\":\"RECORD_REQUIRES_HANDLING\"");
        // Demonstrate configuration repair using an existing Jackson option, no raw edits.
        f.json.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES,true);
        assertThat(f.json.readTree(legacy).get(0).path("text").asText()).isEqualTo("SYNTHETIC_RECOVERABLE_TEXT");
        assertThatThrownBy(()->f.packages.recoverBlockedProjections(path,()->{}))
                .hasMessage("RECORD_REQUIRES_HANDLING");
    }

    @Test void ordinaryMaterializationLimitCanResumeAfterIncreasingLimit() throws Exception {
        var f=new MergeFixture(root); f.message("A","VISIBLE_SOURCE ".repeat(100));
        ReflectionTestUtils.setField(f.packages,"maxRecordMaterializeBytes",128);
        Path path=f.packages.snapshotPath(UUID.randomUUID().toString());
        assertThat(f.packages.seal(path,List.of("A","B"),1,()->{}).blockedReason()).isNotNull();
        ReflectionTestUtils.setField(f.packages,"maxRecordMaterializeBytes",1024*1024);
        f.packages.recoverBlockedProjections(path,()->{});
        assertThat(Files.exists(path.resolve("work/recovered/seal.json"))).isTrue();
    }

    @Test void nonUtf8AttachmentIsPreservedButMarkedBlocking() throws Exception {
        var f=new MergeFixture(root);
        Path file=Files.createDirectories(root.resolve("scratch/A")).resolve("optional.log");
        Files.write(file,"非关键历史日志".getBytes(java.nio.charset.Charset.forName("GBK")));
        f.message("A","This optional historical log is not needed for the current task: "+file);
        Path path=f.packages.snapshotPath(UUID.randomUUID().toString());
        var snapshot=f.packages.seal(path,List.of("A","B"),1,()->{});
        assertThat(snapshot.copiedCount()).isEqualTo(1);
        assertThat(snapshot.blockedReason()).startsWith("RECORD_REQUIRES_HANDLING:");
        assertThatThrownBy(()->f.packages.recoverBlockedProjections(path,()->{}))
                .hasMessage("RECORD_REQUIRES_HANDLING");
    }

    @Test void changingModelThresholdLeavesPendingAggregateAndChangingBackCanRecover() throws Exception {
        var s=new MergeSummaryServiceTest(); s.root=root; s.setup();
        Ledger ledger=s.ledger("source");
        var tokens=mock(TokenCounter.class);
        when(tokens.estimateTokensForModel(anyString(),anyString())).thenAnswer(i ->
                Math.max(1,((String)i.getArgument(0)).length()/(((String)i.getArgument(1)).equals("new-model")?8:2)));
        var summary=new MergeSummaryService(mock(LlmProviderRegistry.class),mock(ModelRegistry.class),tokens);
        AtomicBoolean fail=new AtomicBoolean(true); AtomicInteger calls=new AtomicInteger();
        doAnswer(call -> {
            calls.incrementAndGet(); LlmCallContext context=call.getArgument(6);
            String unit=s.f.jdbc.queryForObject("SELECT unit_id FROM session_merge_attempts WHERE request_id=?",String.class,context.requestId());
            if(unit.startsWith("aggregate-1") && fail.get()) throw new LlmApiException("synthetic auth failure",false,401);
            String body=unit.startsWith("aggregate-0")?"x".repeat(3500):"short synthetic fact";
            SessionMergeServiceTest.respond(call.getArgument(7),SessionMergeServiceTest.answer(body)); return null;
        }).when(s.provider).streamChat(anyString(),anyList(),anyString(),anyList(),anyInt(),any(),any(),any());
        var oldSelection=new MergeSummaryService.Selection("old-model",s.provider,MergeSummaryServiceTest.CAPS);
        assertThatThrownBy(()->summary.prepare(ledger,s.repo,s.f.packages,oldSelection,new AbortContext(),()->{}))
                .isInstanceOf(LlmApiException.class);
        assertThat(s.repo.units(ledger.operationId(),"aggregating")).anyMatch(u->u.unitId().startsWith("aggregate-1")&&!u.state().equals("completed"));
        s.repo.pause(ledger.operationId(),1,"MERGE_PROVIDER_AUTH","synthetic");
        Ledger resumed=s.repo.resume(ledger.operationId(),1,new Execution("new-model",root.toString(),"E",PROCESSOR_VERSION,"handoff-v2-1"),false);
        int before=calls.get(); fail.set(false);
        assertThatThrownBy(()->summary.prepare(resumed,s.repo,s.f.packages,
                new MergeSummaryService.Selection("new-model",s.provider,MergeSummaryServiceTest.CAPS),new AbortContext(),()->{}))
                .hasMessage("MERGE_INCOMPLETE_UNITS");
        assertThat(calls.get()).isEqualTo(before);
        assertThat(summary.prepare(resumed,s.repo,s.f.packages,oldSelection,new AbortContext(),()->{}).body()).contains("HandoffRead");
    }
}
