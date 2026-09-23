package com.aicodeassistant.session.merge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.aicodeassistant.session.merge.MergeHandoffData.*;

/** Synthetic regressions for the defects confirmed in the frozen merge-v2 review. */
class MergeSnapshotRegressionTest {
    @TempDir Path root;
    @Test void malformedHistoricalAgentOutputPathIsPreservedAsAGap() throws Exception {
        var f=new MergeFixture(root); f.message("A","placeholder");
        f.jdbc.update("UPDATE messages SET content_json=? WHERE session_id='A'",f.json.writeValueAsString(List.of(
                Map.of("type","tool_use","id","agent-call","name","Agent","input",Map.of("run_in_background",true)),
                Map.of("type","tool_result","tool_use_id","agent-call","content","Output file: /invalid\u0000.txt\n"))));
        Path path=f.packages.snapshotPath(UUID.randomUUID().toString());
        assertThat(f.packages.seal(path,List.of("A","B"),1,()->{}).blockedReason()).isNull();
        assertThat(Files.readString(path.resolve("snapshot/gaps.jsonl"))).contains("invalid_path");
    }
    @Test void correctedCheckpointParserKeepsToolEvidenceAndFiltersArchiveOnlyBlocks() throws Exception {
        var f=new MergeFixture(root);
        String checkpoint="[{'uuid':'legacy','type':'user','content':[{'type':'text','text':'VISIBLE_REQUIREMENT'},"
                +"{'type':'thinking','thinking':'ARCHIVE_REASONING'},"
                +"{'type':'provider_response_state','state':'PRIVATE_STATE'}],"
                +"'toolUseResult':'RECOVERED_TOOL_FAILURE','sourceToolAssistantUUID':'call'}]";
        f.jdbc.update("INSERT INTO agent_checkpoints(id,run_id,session_id,agent_id,seq,messages_json,created_at) VALUES('cp','run','A','agent',0,?,'now')",checkpoint);
        Path path=f.packages.snapshotPath(UUID.randomUUID().toString());
        var snapshot=f.packages.seal(path,List.of("A","B"),1,()->{});
        assertThat(snapshot.blockedReason()).startsWith("RECORD_REQUIRES_HANDLING:r_");
        f.jdbc.update("DELETE FROM agent_checkpoints");
        f.json.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES,true);
        f.packages.recoverBlockedProjections(path,()->{});
        assertThat(projectedText(f,path)).contains("VISIBLE_REQUIREMENT","RECOVERED_TOOL_FAILURE")
                .doesNotContain("ARCHIVE_REASONING","PRIVATE_STATE");
        assertThat(f.packages.validateSnapshot(path,snapshot.hash(),()->{})).isEqualTo(snapshot);
    }
 @Test void recoveredProjectionMustNotSendArchiveOnlyBlocksToExtractor() throws Exception {
  var f=new MergeFixture(root);
  f.message("A","placeholder");
  f.jdbc.update("UPDATE messages SET content_json=? WHERE session_id='A'", f.json.writeValueAsString(List.of(
   Map.of("type","text","text","visible requirement ".repeat(100)),
   Map.of("type","thinking","thinking","ARCHIVE_ONLY_REASONING"),
   Map.of("type","provider_response_state","state","PRIVATE_CONTINUATION_STATE"))));
  ReflectionTestUtils.setField(f.packages,"maxRecordMaterializeBytes",128);
  Path path=f.packages.snapshotPath(UUID.randomUUID().toString());
  assertThat(f.packages.seal(path,List.of("A","B"),1,()->{}).blockedReason()).isNotNull();
  ReflectionTestUtils.setField(f.packages,"maxRecordMaterializeBytes",1024*1024);
  f.packages.recoverBlockedProjections(path,()->{});
  var output=new StringBuilder();
  for(String line:Files.readAllLines(MergePackageService.projectionCatalogs(path).getLast())) {
   var file=f.json.readValue(line,MergeHandoffData.FileEntry.class);
   output.append(Files.readString(path.resolve(file.path())));
  }
  assertThat(output.toString()).doesNotContain("ARCHIVE_ONLY_REASONING","PRIVATE_CONTINUATION_STATE");
 }

 @Test void sameCheckpointUuidWithChangedLegacyToolResultMustRetainVersions() throws Exception {
  var f=new MergeFixture(root);
  f.sessions.registerSubAgentSession("child",root.toString(),"A");
  for(int i=0;i<2;i++) {
   var message=Map.of("uuid","same-tool-result","type","user","content",List.of(),"sourceToolAssistantUUID","tool-call","toolUseResult",i==0?"TEST_FAILED_V1":"TEST_PASSED_V2");
   f.jdbc.update("INSERT INTO agent_checkpoints(id,run_id,session_id,agent_id,seq,messages_json,created_at) VALUES(?, 'run','child','agent',?,?, 'now')","cp"+i,i,f.json.writeValueAsString(List.of(message)));
  }
  Path path=f.packages.snapshotPath(UUID.randomUUID().toString());
  f.packages.seal(path,List.of("A","B"),1,()->{});
  var records=new ArrayList<MergeHandoffData.RecordEntry>();
  for(String line:Files.readAllLines(path.resolve("snapshot/records.jsonl"))) {
   var r=f.json.readValue(line,MergeHandoffData.RecordEntry.class);
   if(r.origin().recordId().equals("same-tool-result")) records.add(r);
  }
  assertThat(records).hasSize(2);
 }

 @Test void ordinarySavedCheckpointToolResultMustBeSearchableForExtraction() throws Exception {
  var f=new MergeFixture(root); f.sessions.registerSubAgentSession("child",root.toString(),"A");
  var message=Map.of("uuid","legacy-result","type","user","content",List.of(),"sourceToolAssistantUUID","tool-call","toolUseResult","ONLY_EVIDENCE_TEST_FAILED");
  f.jdbc.update("INSERT INTO agent_checkpoints(id,run_id,session_id,agent_id,seq,messages_json,created_at) VALUES('cp','run','child','agent',0,?,'now')",f.json.writeValueAsString(List.of(message)));
  Path path=f.packages.snapshotPath(UUID.randomUUID().toString()); f.packages.seal(path,List.of("A","B"),1,()->{});
  StringBuilder output=new StringBuilder();
  for(String line:Files.readAllLines(path.resolve("snapshot/files.jsonl"))) {
   var file=f.json.readValue(line,MergeHandoffData.FileEntry.class);
   if(file.kind().equals("text")) output.append(Files.readString(path.resolve(file.path())));
  }
  assertThat(output.toString()).contains("ONLY_EVIDENCE_TEST_FAILED");
 }

 @Test void copiedManagedFileMustNotAlsoBeReportedAsAnExternalGap() throws Exception {
  var f=new MergeFixture(root);
  Path plan=Files.createDirectories(root.resolve("scratch/A")).resolve("plan.md");
  Files.writeString(plan,"managed plan requirement"); f.message("A",plan.toString());
  Path path=f.packages.snapshotPath(UUID.randomUUID().toString());
  var snapshot=f.packages.seal(path,List.of("A","B"),1,()->{});
  assertThat(snapshot.copiedCount()).isEqualTo(1);
  assertThat(snapshot.warningCount()).isZero();
 }

    @Test void historicalReferenceCannotCopyThroughIntermediateSymlink() throws Exception {
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
        assertThat(assets).doesNotContain("SYNTHETIC_OUTSIDE_PROOF");
    }

    @Test void malformedExternalReferenceIsAnExplicitNonBlockingGap() throws Exception {
        var f=new MergeFixture(root); f.message("A","/missing/ref\u0000.txt");
        Path path=f.packages.snapshotPath(UUID.randomUUID().toString());
        assertThat(f.packages.seal(path,List.of("A","B"),1,()->{}).blockedReason()).isNull();
        assertThat(Files.readString(path.resolve("snapshot/gaps.jsonl"))).contains("external_reference");
    }

    @Test void correctedParserConfigurationRecoversTheBoundRawRecord() throws Exception {
        var f=new MergeFixture(root); f.message("A","placeholder");
        String legacy="[{'type':'text','text':'SYNTHETIC_RECOVERABLE_TEXT'}]";
        f.jdbc.update("UPDATE messages SET content_json=? WHERE session_id='A'",legacy);
        Path path=f.packages.snapshotPath(UUID.randomUUID().toString());
        f.packages.seal(path,List.of("A","B"),1,()->{});
        assertThat(Files.readString(path.resolve("snapshot/gaps.jsonl")))
                .contains("RECORD_REQUIRES_HANDLING:r_");
        // Demonstrate configuration repair using an existing Jackson option, no raw edits.
        f.json.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES,true);
        assertThat(f.json.readTree(legacy).get(0).path("text").asText()).isEqualTo("SYNTHETIC_RECOVERABLE_TEXT");
        f.packages.recoverBlockedProjections(path,()->{});
        assertThat(projectedText(f,path)).contains("SYNTHETIC_RECOVERABLE_TEXT");
    }
    private String projectedText(MergeFixture f,Path path) throws Exception {
        StringBuilder text=new StringBuilder();
        for(Path catalog:MergePackageService.projectionCatalogs(path))
            for(String line:Files.readAllLines(catalog)) {
                var file=f.json.readValue(line,FileEntry.class);
                if(file.kind().equals("text")) text.append(Files.readString(path.resolve(file.path())));
            }
        return text.toString();
    }
}
