package com.aicodeassistant.session.merge;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.engine.AbortContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class MergeReviewReproTest {
 @TempDir Path root;
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
 @Test void imageOnlyUserRequirementMustNotPublishWithoutAnyVisualExtraction() throws Exception {
  var scenario=new SessionMergeServiceTest(); scenario.root=root; scenario.setup();
  try {
   scenario.f.jdbc.update("DELETE FROM messages");
   scenario.f.sessions.addMessageWithId("image-only","A","user",List.of(new ContentBlock.TextBlock("")),null,0,0,Map.of());
   var buffer=new java.io.ByteArrayOutputStream();
   javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",buffer);
   scenario.f.jdbc.update("UPDATE messages SET content_json=? WHERE id='image-only'", scenario.f.json.writeValueAsString(List.of(Map.of("type","text","text","所有实现要求仅在附图中，请按图实现"), Map.of("type","image","base64Data",Base64.getEncoder().encodeToString(buffer.toByteArray()),"mediaType","image/png"))));
   var op=scenario.await(scenario.service.start(scenario.request(),"image-only").operationId());
   assertThat(op.status()).isEqualTo("paused");
  } finally {scenario.shutdown();}
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

}
