package com.aicodeassistant.session.merge;

import com.aicodeassistant.authorization.*;
import com.aicodeassistant.engine.*;
import com.aicodeassistant.llm.*;
import com.aicodeassistant.model.*;
import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.nio.file.*;
import java.util.*;
import static com.aicodeassistant.session.merge.MergeHandoffData.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HandoffReadServiceTest {
    @TempDir Path root;
    MergeFixture f; MergeProgressRepository repo; HandoffReadService reads; Ledger ledger;
    @BeforeEach void prepare() throws Exception {
        f=new MergeFixture(root); repo=new MergeProgressRepository(f.jdbc,f.json,new DataSourceTransactionManager(f.ds));
        Path image=Files.createDirectories(root.resolve("scratch/A")).resolve("fixture.png");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",image.toFile());
        f.message("A","SERVER中文路径 /api/order and failed test\n".repeat(1800)+"SOURCE_TAIL");
        f.message("B","CLIENT request orderId, unresolved interface mismatch");
        var provider=mock(LlmProvider.class);
        doAnswer(call -> { SessionMergeServiceTest.respond(call.getArgument(7),SessionMergeServiceTest.answer("接口尚需核对")); return null; })
                .when(provider).streamChat(anyString(),anyList(),anyString(),anyList(),anyInt(),any(),any(),any());
        var counter=mock(TokenCounter.class); when(counter.estimateTokensForModel(anyString(),anyString())).thenAnswer(i -> ((String)i.getArgument(0)).length()/2);
        var summary=new MergeSummaryService(mock(LlmProviderRegistry.class),mock(ModelRegistry.class),counter);
        String id=UUID.randomUUID().toString(); Path path=f.packages.snapshotPath(id);
        repo.create(id,id,f.json.writeValueAsString(new SessionMergeService.Request(List.of("A","B"),"A","E","test-model")),"E",path.toString(),new Execution("test-model",root.toString(),"E",PROCESSOR_VERSION,"handoff-v2-1"));
        int version=repo.nextSnapshot(id,1); var snapshot=f.packages.seal(path,List.of("A","B"),version,()->{});
        repo.sealed(id,1,version,snapshot.hash(),Map.of()); ledger=repo.find(id).orElseThrow();
        var prepared=summary.prepare(ledger,repo,f.packages,new MergeSummaryService.Selection("test-model",provider,MergeSummaryServiceTest.CAPS),new AbortContext(),()->{});
        repo.stage(id,1,"publishing"); f.sessions.createSessionRecord("E","test-model",root.toString(),"E"); repo.complete(id,1,prepared.hash(),Map.of());
        ledger=repo.find(id).orElseThrow(); reads=new HandoffReadService(repo,f.json);
    }
    @Test void readsIndependentRawAndDetailsAfterSourcesAreDeleted() throws Exception {
        f.jdbc.update("DELETE FROM messages WHERE session_id IN ('A','B')"); f.jdbc.update("DELETE FROM sessions WHERE id IN ('A','B')");
        String found=reads.read("E",ToolInput.from(Map.of("action","search","query","SOURCE_TAIL")));
        assertThat(found).contains("SOURCE_TAIL");
        JsonNode catalog=f.json.readTree(reads.read("E",ToolInput.from(Map.of("action","list","section","changes"))));
        String detailRef=catalog.path("entries").get(0).path("ref").asText();
        assertThat(reads.read("E",ToolInput.from(Map.of("action","read","ref",detailRef)))).contains("接口尚需核对","evidence");
        assertThat(reads.read("E",ToolInput.from(Map.of("action","read","ref","records")))).contains("rawRef");
    }
    @Test void paginatesWithinWrapperLimitAndBindsCursorToFiltersAndPackage() throws Exception {
        var input=new LinkedHashMap<String,Object>(); input.put("action","list"); input.put("limit",1);
        Set<String> refs=new HashSet<>(); String firstCursor=null;
        for(int n=0;n<100;n++) {
            String response=reads.read("E",ToolInput.from(input));
            assertThat(response.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(16384);
            JsonNode page=f.json.readTree(response);
            for(JsonNode entry:page.path("entries")) assertThat(refs.add(entry.path("ref").asText())).isTrue();
            if(page.path("complete").asBoolean()) break;
            String cursor=page.path("nextCursor").asText(); if(firstCursor==null) firstCursor=cursor; input.put("cursor",cursor);
        }
        assertThat(refs).hasSizeGreaterThan(4);
        String cursor=firstCursor;
        assertThatThrownBy(() -> reads.read("E",ToolInput.from(Map.of("action","search","query","中文","cursor",cursor)))).hasMessage("HANDOFF_INVALID_CURSOR");
        assertThat(reads.read("E",ToolInput.from(Map.of("action","search","query","中文路径")))).contains("中文路径");
    }
    @Test void rejectsCrossSessionUnknownRefsTraversalAndSymlinks() throws Exception {
        assertThatThrownBy(() -> reads.read("A",ToolInput.from(Map.of("action","list")))).isInstanceOf(AuthorizationException.class);
        for(String ref:List.of("../../secret","/etc/passwd","foreign-package-ref"))
            assertThatThrownBy(() -> reads.read("E",ToolInput.from(Map.of("action","read","ref",ref)))).isInstanceOf(AuthorizationException.class);
        var file=f.json.readValue(Files.readAllLines(Path.of(ledger.packagePath()).resolve("snapshot/files.jsonl")).getFirst(),FileEntry.class);
        Path target=Path.of(ledger.packagePath()).resolve(file.path()); Files.delete(target);
        Files.createSymbolicLink(target,Files.writeString(root.resolve("secret"),"secret"));
        assertThatThrownBy(() -> reads.read("E",ToolInput.from(Map.of("action","read","ref",file.ref())))).isInstanceOf(AuthorizationException.class);
    }
    @Test void rejectsCorruptCatalogAndDeletedTargetBinding() throws Exception {
        Files.writeString(Path.of(ledger.packagePath()).resolve("handoff/details.jsonl"),"tampered");
        assertThatThrownBy(() -> reads.read("E",ToolInput.from(Map.of("action","list")))).isInstanceOf(AuthorizationException.class);
        f.jdbc.update("DELETE FROM sessions WHERE id='E'");
        assertThatThrownBy(() -> reads.binding("E")).isInstanceOf(AuthorizationException.class);
    }
    @Test void projectionUsesTrustedRunRootRespectsBudgetAndDoesNotRewriteCurrentTodos() throws Exception {
        var subjects=mock(AuthorizationSubjectResolver.class);
        when(subjects.resolve("child-run")).thenReturn(new AuthorizationSubject("E","root-run","child-run","workspace",root));
        var tokens=mock(TokenCounter.class);
        when(tokens.estimateTokensForModel(anyString(),anyString())).thenAnswer(i -> ((String)i.getArgument(0)).length()/2);
        var contexts=new HandoffContextService(repo,reads,subjects,tokens);
        var context=ToolUseContext.of(root.toString(),"untrusted-source-A").withCurrentRunId("child-run");
        var projection=contexts.project(context,"small-model",5000,2);
        assertThat(projection.reservedTokens()).isLessThanOrEqualTo(500);
        var current=new Message.UserMessage("current",java.time.Instant.now(),List.of(new ContentBlock.TextBlock("E 最新决定：联调完成，旧待办取消")),null,null);
        var history=new ArrayList<Message>(List.of(current));
        var request=CompactionHistory.forRequest(HandoffContextService.inject(history,projection));
        assertThat(history).containsExactly(current); assertThat(request).contains(current).hasSize(2);
        assertThat(request.getFirst().toString()).contains("HandoffRead","历史");
        assertThat(contexts.project(ToolUseContext.of(root.toString(),"A"),"small-model",5000,2).messages()).isEmpty();
    }
    @Test void imageOutsideWorkspaceIsInjectedOnlyThroughTheCurrentRootsBoundAsset() throws Exception {
        FileEntry image;
        try(var lines=Files.lines(Path.of(ledger.packagePath()).resolve("snapshot/files.jsonl"))) {
            image=lines.map(line -> repo.decode(line,FileEntry.class)).filter(e -> e.kind().equals("asset")).findFirst().orElseThrow();
        }
        Path path=Path.of(ledger.packagePath()).resolve(image.path());
        Path workspace=Files.createDirectory(root.resolve("code-workspace")).toRealPath();
        var subjects=mock(AuthorizationSubjectResolver.class);
        when(subjects.resolve("run")).thenReturn(new AuthorizationSubject("E","run","run","wk",workspace));
        var tool=new com.aicodeassistant.tool.impl.HandoffReadTool(reads,subjects,new com.aicodeassistant.tool.impl.ImageResultExternalizer(f.json));
        var result=tool.call(ToolInput.from(Map.of("action","asset","ref",image.ref())),ToolUseContext.of(workspace.toString(),"E").withCurrentRunId("run"));
        assertThat(result.isError()).isFalse(); assertThat(result.content()).contains("[image_ref]");
        List<Message> messages=List.of(new Message.UserMessage("image-result",java.time.Instant.EPOCH,
                List.of(new ContentBlock.ToolResultBlock("image-tool",result.content(),false)),null,null));
        var injector=new ImageRefInjector(f.json,new com.aicodeassistant.security.PathSecurityService());
        var denied=injector.injectForApiCall(messages,0,100000,Set.of(),new HashMap<>(),workspace.toString(),1);
        assertThat(denied.pendingHashes()).isEmpty();
        var allowed=injector.injectForApiCall(messages,0,100000,Set.of(),new HashMap<>(),workspace.toString(),1,
                (asset,hash) -> reads.isBoundAsset("E",asset,hash));
        assertThat(allowed.pendingHashes()).containsExactly(image.sha256());
        assertThat(reads.isBoundAsset("A",path.toString(),image.sha256())).isFalse();
        assertThat(reads.isBoundAsset("E",path.toString(),"wrong-hash")).isFalse();
    }

    @Test void everyAggregateEvidenceReferenceResolvesToPreservedChild() throws Exception {
        for(var unit:repo.units(ledger.operationId(),"aggregating")) {
            if(!unit.state().equals("completed")) continue;
            Detail detail=f.json.readValue(Files.readString(Path.of(ledger.packagePath()).resolve(unit.resultPath())),Detail.class);
            for(Item item:detail.items()) for(String ref:item.evidence())
                assertThat(reads.read("E",ToolInput.from(Map.of("action","read","ref",ref)))).contains("entries");
        }
    }
    @Test void searchReturnsEveryMatchAndResumesInsideTheSameChunkWithoutDuplicates() throws Exception {
        StringBuilder text=new StringBuilder("x".repeat(10)+"needle"+"x".repeat(900)+"needle");
        text.append("x".repeat(2046-text.length())).append("needle").append("x".repeat(500)).append("need");
        replaceReadableCatalog(List.of(text.toString(),"le tail needle"));
        var input=new LinkedHashMap<String,Object>(Map.of("action","search","query","needle","limit",1));
        List<String> matches=new ArrayList<>(); Set<String> cursors=new HashSet<>(); boolean complete=false;
        for(int page=0;page<20;page++) {
            JsonNode result=f.json.readTree(reads.read("E",ToolInput.from(input)));
            for(JsonNode entry:result.path("entries")) matches.add(entry.path("ref").asText()+":"+entry.path("offset").asLong());
            if(result.path("complete").asBoolean()) { complete=true; break; }
            String cursor=result.path("nextCursor").asText(); assertThat(cursors.add(cursor)).isTrue(); input.put("cursor",cursor);
        }
        assertThat(complete).isTrue();
        assertThat(matches).containsExactly("fixture:p0:10","fixture:p0:916","fixture:p0:2046","fixture:p0:2552","fixture:p1:8");
    }
    @Test void unicodeSearchDoesNotDuplicateACompleteSurrogatePairInTheCarry() throws Exception {
        replaceReadableCatalog(List.of("x".repeat(2046)+"😀"+"x".repeat(7)+"😀"));
        JsonNode result=f.json.readTree(reads.read("E",ToolInput.from(Map.of("action","search","query","😀","limit",20))));
        assertThat(result.path("complete").asBoolean()).isTrue();
        List<Long> offsets=new ArrayList<>(); result.path("entries").forEach(e -> offsets.add(e.path("offset").asLong()));
        assertThat(offsets).containsExactly(2046L,2055L);
    }
    @Test void listingUsesExactCatalogByteOffsetsAndRejectsMidLineCursors() throws Exception {
        replaceReadableCatalog(List.of("中文第一片","中文第二片","第三片"));
        var input=new LinkedHashMap<String,Object>(Map.of("action","list","limit",1));
        JsonNode page=f.json.readTree(reads.read("E",ToolInput.from(input)));
        var cursor=(com.fasterxml.jackson.databind.node.ObjectNode)f.json.readTree(Base64.getUrlDecoder().decode(page.path("nextCursor").asText()));
        String firstLine=Files.readAllLines(Path.of(ledger.packagePath()).resolve("snapshot/files.jsonl")).getFirst()+"\n";
        assertThat(cursor.path("catalog").asInt()).isEqualTo(1);
        assertThat(cursor.path("catalogOffset").asLong()).isEqualTo(firstLine.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        input.put("cursor",page.path("nextCursor").asText());
        assertThat(reads.read("E",ToolInput.from(input))).contains("fixture:p1").doesNotContain("fixture:p0");
        cursor.put("catalogOffset",cursor.path("catalogOffset").asLong()+1);
        input.put("cursor",Base64.getUrlEncoder().withoutPadding().encodeToString(f.json.writeValueAsBytes(cursor)));
        assertThatThrownBy(() -> reads.read("E",ToolInput.from(input))).hasMessage("HANDOFF_INVALID_CURSOR");
    }
    @Test void timeoutAndInterruptionApplyBeforeAndDuringIntegrityVerification() throws Exception {
        java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
        // Expire while hashing the first manifest, before catalog/search processing starts.
        var timed=new HandoffReadService(repo,f.json,() -> checks.getAndIncrement()<5?0:16_000_000_000L);
        assertThatThrownBy(() -> timed.read("E",ToolInput.from(Map.of("action","search","query","SOURCE_TAIL"))))
                .hasMessage("HANDOFF_READ_BUDGET_EXCEEDED");
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> reads.read("E",ToolInput.from(Map.of("action","list"))))
                    .isInstanceOf(java.io.InterruptedIOException.class).hasMessage("HANDOFF_READ_INTERRUPTED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
    @Test void bodyScanBudgetReturnsAProgressingCursorAndEventuallyFindsTheTail() throws Exception {
        replaceReadableCatalog(List.of("x".repeat(8*1024*1024+200)+"TAIL_AFTER_SCAN_BUDGET"));
        var input=new LinkedHashMap<String,Object>(Map.of("action","search","query","TAIL_AFTER_SCAN_BUDGET"));
        JsonNode first=f.json.readTree(reads.read("E",ToolInput.from(input)));
        assertThat(first.path("entries")).isEmpty(); assertThat(first.path("complete").asBoolean()).isFalse();
        JsonNode cursor=f.json.readTree(Base64.getUrlDecoder().decode(first.path("nextCursor").asText()));
        assertThat(cursor.path("offset").asLong()).isBetween(8*1024*1024L-100,8*1024*1024L);
        input.put("cursor",first.path("nextCursor").asText());
        JsonNode second=f.json.readTree(reads.read("E",ToolInput.from(input)));
        assertThat(second.path("complete").asBoolean()).isTrue();
        assertThat(second.path("entries")).hasSize(1);
        assertThat(second.path("entries").get(0).path("offset").asLong()).isEqualTo(8*1024*1024L+200);
    }
    @Test void successfulReadDoesNotCacheIntegrityAcrossCalls() throws Exception {
        replaceReadableCatalog(List.of("original"));
        assertThat(reads.read("E",ToolInput.from(Map.of("action","read","ref","fixture:p0")))).contains("original");
        Files.writeString(Path.of(ledger.packagePath()).resolve("snapshot/fixture-0.txt"),"tampered");
        assertThatThrownBy(() -> reads.read("E",ToolInput.from(Map.of("action","read","ref","fixture:p0"))))
                .hasMessage("HANDOFF_HASH_MISMATCH");
    }
    private void replaceReadableCatalog(List<String> texts) throws Exception {
        Path dir=Path.of(ledger.packagePath()); StringBuilder catalog=new StringBuilder();
        for(int i=0;i<texts.size();i++) {
            String path="snapshot/fixture-"+i+".txt"; Files.writeString(dir.resolve(path),texts.get(i));
            catalog.append(repo.encode(new FileEntry("fixture:p"+i,"fixture","A",i,path,
                    Files.size(dir.resolve(path)),MergePackageService.hash(dir.resolve(path),()->{}),"text"))).append('\n');
        }
        Files.writeString(dir.resolve("snapshot/files.jsonl"),catalog); Files.writeString(dir.resolve("handoff/details.jsonl"),"");
        var manifest=(com.fasterxml.jackson.databind.node.ObjectNode)f.json.readTree(Files.readString(dir.resolve("snapshot/manifest.json")));
        ((com.fasterxml.jackson.databind.node.ObjectNode)manifest.path("catalogs").path("files.jsonl"))
                .put("sha256",MergePackageService.hash(dir.resolve("snapshot/files.jsonl"),()->{}));
        Files.writeString(dir.resolve("snapshot/manifest.json"),repo.encode(manifest));
        String snapshotHash=MergePackageService.hash(dir.resolve("snapshot/manifest.json"),()->{});
        var ready=(com.fasterxml.jackson.databind.node.ObjectNode)f.json.readTree(Files.readString(dir.resolve("handoff/ready.json")));
        ready.put("snapshotHash",snapshotHash).put("detailsHash",MergePackageService.hash(dir.resolve("handoff/details.jsonl"),()->{}));
        Files.writeString(dir.resolve("handoff/ready.json"),repo.encode(ready));
        f.jdbc.update("UPDATE session_merges SET snapshot_hash=?,handoff_hash=? WHERE operation_id=?",snapshotHash,
                MergePackageService.hash(dir.resolve("handoff/ready.json"),()->{}),ledger.operationId());
        ledger=repo.find(ledger.operationId()).orElseThrow();
    }

}
