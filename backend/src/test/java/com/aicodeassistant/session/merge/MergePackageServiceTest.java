package com.aicodeassistant.session.merge;

import com.aicodeassistant.model.ContentBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MergePackageServiceTest {
    @TempDir Path root;
    @Test void legacyPublishedPackageIsCopiedWithoutDependingOnItsOldDirectory() throws Exception {
        var f=new MergeFixture(root); f.message("A","LEGACY_HISTORY_TAIL");
        String oldId=UUID.randomUUID().toString();
        var legacy=f.packages.build(f.packages.packagePath("legacy",oldId),List.of("A","B"),()->{});
        f.sessions.createSessionRecord("legacy","test-model",root.toString(),"legacy");
        f.jdbc.update("INSERT INTO session_merges(operation_id,idempotency_key,params_json,target_session_id,status,stage,package_path,created_at,updated_at) VALUES(?,?,'{}','legacy','completed','completed',?,'now','now')",oldId,oldId,legacy.path().toString());
        Path path=f.packages.snapshotPath(UUID.randomUUID().toString());
        var snapshot=f.packages.seal(path,List.of("legacy","C"),1,()->{});
        f.packages.deleteUnreferenced(legacy.path());
        f.packages.validateSnapshot(path,snapshot.hash(),()->{});
        StringBuilder text=new StringBuilder();
        try(var lines=Files.lines(path.resolve("snapshot/files.jsonl"))) {
            for(String line:lines.toList()) {
                var file=f.json.readValue(line,MergeHandoffData.FileEntry.class);
                if(file.kind().equals("text")) text.append(Files.readString(path.resolve(file.path())));
            }
        }
        assertThat(text).contains("LEGACY_HISTORY_TAIL");
    }

    @Test void blockedLargeRecordCanBeReparsedOnlyFromItsImmutableCopy() throws Exception {
        var f=new MergeFixture(root);
        f.message("A","RECOVER_FROM_SNAPSHOT_ONLY "+"long text ".repeat(100));
        org.springframework.test.util.ReflectionTestUtils.setField(f.packages,"maxRecordMaterializeBytes",128);
        String id=UUID.randomUUID().toString(); Path path=f.packages.snapshotPath(id);
        var snapshot=f.packages.seal(path,List.of("A","B"),1,()->{});
        assertThat(snapshot.blockedReason()).isNotNull();
        f.jdbc.update("DELETE FROM messages WHERE session_id='A'");
        org.springframework.test.util.ReflectionTestUtils.setField(f.packages,"maxRecordMaterializeBytes",1024*1024);
        f.packages.recoverBlockedProjections(path,()->{});
        assertThat(f.packages.validateSnapshot(path,snapshot.hash(),()->{}).hash()).isEqualTo(snapshot.hash());
        String catalog=Files.readString(MergePackageService.projectionCatalogs(path).getLast());
        var entry=f.json.readValue(catalog.lines().findFirst().orElseThrow(),MergeHandoffData.FileEntry.class);
        assertThat(Files.readString(path.resolve(entry.path()))).contains("RECOVER_FROM_SNAPSHOT_ONLY");
    }

    @Test void emptyRecoveredSealCannotSatisfyABlockedRecord() throws Exception {
        var f=new MergeFixture(root); f.message("A","long text ".repeat(100));
        org.springframework.test.util.ReflectionTestUtils.setField(f.packages,"maxRecordMaterializeBytes",128);
        Path path=f.packages.snapshotPath(UUID.randomUUID().toString());
        assertThat(f.packages.seal(path,List.of("A","B"),1,()->{}).blockedReason()).isNotNull();
        Path recovered=Files.createDirectories(path.resolve("work/recovered"));
        Path catalog=Files.writeString(recovered.resolve("projection-files.jsonl"),"");
        Files.writeString(recovered.resolve("seal.json"),f.json.writeValueAsString(Map.of("catalogHash",MergePackageService.hash(catalog,()->{}))));
        org.springframework.test.util.ReflectionTestUtils.setField(f.packages,"maxRecordMaterializeBytes",1024*1024);
        assertThatThrownBy(()->f.packages.recoverBlockedProjections(path,()->{})).hasMessage("RECORD_REQUIRES_HANDLING");
    }

    @Test void corruptTextAttachmentCannotBeBypassedByRecovery() throws Exception {
        var f=new MergeFixture(root);
        Path scratch=Files.createDirectories(root.resolve("scratch/A"));
        Files.write(scratch.resolve("plan.md"),new byte[]{(byte)0xc3,0x28});
        Path path=f.packages.snapshotPath(UUID.randomUUID().toString());
        var snapshot=f.packages.seal(path,List.of("A","B"),1,()->{});
        assertThat(snapshot.blockedReason()).startsWith("RECORD_REQUIRES_HANDLING:");
        assertThatThrownBy(()->f.packages.recoverBlockedProjections(path,()->{})).hasMessage("RECORD_REQUIRES_HANDLING");
        assertThat(Files.exists(path.resolve("work/recovered/seal.json"))).isFalse();
        assertThat(f.packages.validateSnapshot(path,snapshot.hash(),()->{})).isEqualTo(snapshot);
    }

    @Test void largeCheckpointCanRecoverAfterRaisingTheMaterializationLimit() throws Exception {
        var f=new MergeFixture(root);
        String messages=f.json.writeValueAsString(List.of(Map.of("uuid","historical","type","assistant",
                "content",List.of(Map.of("type","text","text","long checkpoint ".repeat(100)+"CHECKPOINT_TAIL")))));
        f.jdbc.update("INSERT INTO agent_checkpoints(id,run_id,session_id,agent_id,seq,messages_json,created_at) VALUES('cp','run','A','agent',0,?,'now')",messages);
        org.springframework.test.util.ReflectionTestUtils.setField(f.packages,"maxRecordMaterializeBytes",128);
        Path path=f.packages.snapshotPath(UUID.randomUUID().toString());
        assertThat(f.packages.seal(path,List.of("A","B"),1,()->{}).blockedReason()).isNotNull();
        f.jdbc.update("DELETE FROM agent_checkpoints");
        org.springframework.test.util.ReflectionTestUtils.setField(f.packages,"maxRecordMaterializeBytes",1024*1024);
        f.packages.recoverBlockedProjections(path,()->{});
        f.packages.recoverBlockedProjections(path,()->{}); // a complete, verified seal remains reusable
        StringBuilder text=new StringBuilder();
        for(String line:Files.readAllLines(MergePackageService.projectionCatalogs(path).getLast())) {
            var file=f.json.readValue(line,MergeHandoffData.FileEntry.class);
            text.append(Files.readString(path.resolve(file.path())));
        }
        assertThat(text).contains("CHECKPOINT_TAIL");
    }

    @Test void missingUnpersistedChildHistoryIsAnExplicitNonBlockingGap() throws Exception {
        var f=new MergeFixture(root);
        f.sessions.registerSubAgentSession("child",root.toString(),"A");
        Path path=f.packages.snapshotPath(UUID.randomUUID().toString());
        var snapshot=f.packages.seal(path,List.of("A","B"),1,()->{});
        assertThat(snapshot.blockedReason()).isNull();
        var gaps=Files.readAllLines(path.resolve("snapshot/gaps.jsonl"));
        assertThat(gaps).anySatisfy(line -> {
            var gap=f.json.readTree(line);
            assertThat(gap.path("sourceId").asText()).isEqualTo("child");
            assertThat(gap.path("reason").asText()).contains("未持久化");
            assertThat(gap.path("blocking").asBoolean()).isFalse();
        });
    }

    @Test void v2SnapshotPreservesRawAndTextAfterSourceDeletion() throws Exception {
        var f = new MergeFixture(root);
        f.message("A", "中文🙂".repeat(12000)+"FINAL_TAIL");
        Path scratch=Files.createDirectories(root.resolve("scratch/A"));
        Files.writeString(scratch.resolve("plan.md"),"PLAN_ONLY_FACT");
        Path path=f.packages.snapshotPath(UUID.randomUUID().toString());
        var snapshot=f.packages.seal(path,List.of("A","B"),1,()->{});
        assertThat(snapshot.blockedReason()).isNull();
        f.jdbc.update("DELETE FROM messages WHERE session_id='A'");
        f.jdbc.update("DELETE FROM sessions WHERE id='A'");
        Files.delete(scratch.resolve("plan.md"));
        assertThat(f.packages.validateSnapshot(path,snapshot.hash(),()->{})).isEqualTo(snapshot);
        StringBuilder text=new StringBuilder();
        for(String line:Files.readAllLines(path.resolve("snapshot/files.jsonl"))) {
            var entry=f.json.readValue(line,MergeHandoffData.FileEntry.class);
            if("text".equals(entry.kind())) {
                assertThat(entry.bytes()).isLessThanOrEqualTo(32768);
                text.append(Files.readString(path.resolve(entry.path())));
            }
        }
        assertThat(text.toString()).contains("FINAL_TAIL","PLAN_ONLY_FACT");
    }
    @Test void v2ParsingFailurePreservesRawAndSealsWithExplicitBlocker() throws Exception {
        var f=new MergeFixture(root); f.message("A","placeholder");
        f.jdbc.update("UPDATE messages SET content_json='not-json' WHERE session_id='A'");
        Path path=f.packages.snapshotPath(UUID.randomUUID().toString());
        var snapshot=f.packages.seal(path,List.of("A","B"),1,()->{});
        assertThat(snapshot.blockedReason()).contains("RECORD_REQUIRES_HANDLING");
        assertThat(f.packages.validateSnapshot(path,snapshot.hash(),()->{})).isEqualTo(snapshot);
        try(var files=Files.list(path.resolve("snapshot/raw"))) {
            assertThat(files.filter(p -> { try { return Files.readString(p).contains("not-json"); } catch(Exception e) { throw new RuntimeException(e); }}).count()).isEqualTo(1);
        }
    }
    @Test void v2DetectsTamperedSnapshotAndRejectsPathTraversal() throws Exception {
        var f=new MergeFixture(root); f.message("A","before");
        Path path=f.packages.snapshotPath(UUID.randomUUID().toString());
        var snapshot=f.packages.seal(path,List.of("A","B"),1,()->{});
        Path text;
        try(var files=Files.list(path.resolve("snapshot/text"))) { text=files.findFirst().orElseThrow(); }
        Files.writeString(text,"tampered");
        assertThatThrownBy(()->f.packages.validateSnapshot(path,snapshot.hash(),()->{})).hasMessageContaining("SNAPSHOT_CORRUPT");
        assertThatThrownBy(()->MergePackageService.safeFile(path,"../private" )).hasMessageContaining("INVALID_REF");
    }
    @Test void v2OrdinaryTextBeyondLegacyRecordLimitIsFullySplit() throws Exception {
        var f=new MergeFixture(root);
        f.message("A","x".repeat(MergePackageService.MAX_RECORD_BYTES+1)+"LARGE_FINAL_TAIL");
        Path path=f.packages.snapshotPath(UUID.randomUUID().toString());
        var snapshot=f.packages.seal(path,List.of("A","B"),1,()->{});
        assertThat(snapshot.blockedReason()).isNull();
        boolean tail=false;
        try(var lines=Files.lines(path.resolve("snapshot/files.jsonl"))) {
            for(var iterator=lines.iterator();iterator.hasNext();) {
                var entry=f.json.readValue(iterator.next(),MergeHandoffData.FileEntry.class);
                if("text".equals(entry.kind())) {
                    assertThat(entry.bytes()).isLessThanOrEqualTo(32768);
                    tail |= Files.readString(path.resolve(entry.path())).contains("LARGE_FINAL_TAIL");
                }
            }
        }
        assertThat(tail).isTrue();
    }
    @Test void v2RepeatedMergeCopiesIndependentOriginalsAndDeduplicatesMessages() throws Exception {
        var f=new MergeFixture(root);
        new com.aicodeassistant.config.database.V026_ExtendSessionMerges(f.jdbc).execute();
        f.message("A","ONE_ORIGINAL_FACT");
        String firstId=UUID.randomUUID().toString();
        Path first=f.packages.snapshotPath(firstId);
        var snapshot=f.packages.seal(first,List.of("A","B"),1,()->{});
        f.jdbc.update("""
                INSERT INTO session_merges(operation_id,idempotency_key,params_json,target_session_id,status,stage,
                    package_path,created_at,updated_at,protocol_version,snapshot_hash,handoff_hash)
                VALUES(?,?,?,'C','completed','completed',?,'now','now',2,?,'test-handoff')
                """,firstId,firstId,"{}",first.toString(),snapshot.hash());
        f.message("C","AFTER_FIRST_MERGE");
        Path second=f.packages.snapshotPath(UUID.randomUUID().toString());
        var merged=f.packages.seal(second,List.of("C","A"),1,()->{});
        try(var paths=Files.walk(first)) {
            for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
        f.jdbc.update("DELETE FROM sessions WHERE id IN ('A','B','C')");
        assertThat(f.packages.validateSnapshot(second,merged.hash(),()->{})).isEqualTo(merged);
        StringBuilder contents=new StringBuilder();
        for(String line:Files.readAllLines(second.resolve("snapshot/files.jsonl"))) {
            var entry=f.json.readValue(line,MergeHandoffData.FileEntry.class);
            if("text".equals(entry.kind())) contents.append(Files.readString(second.resolve(entry.path())));
        }
        assertThat(contents.toString()).containsOnlyOnce("ONE_ORIGINAL_FACT").contains("AFTER_FIRST_MERGE");
    }
    @Test void preservesLastUnicodeRecordAndBinaryCopiesWithoutImportingToolState() throws Exception {
        var f = new MergeFixture(root);
        Path scratch = Files.createDirectories(root.resolve("scratch/A"));
        byte[] binary = new byte[100001]; new Random(3).nextBytes(binary);
        Files.write(scratch.resolve("output.bin"), binary);
        Files.writeString(scratch.resolve("answer.txt"), "ONLY_IN_ARTIFACT_9273");
        f.message("A", "中文🙂\n".repeat(12000) + "TAIL_SECRET_4837");
        for (String source : List.of("A", "B")) {
            f.sessions.addMessageWithId(UUID.randomUUID().toString(), source, "assistant",
                    List.of(new ContentBlock.ToolUseBlock("same-tool-id", "Read", f.json.createObjectNode().put("file_path", scratch.resolve("missing.txt").toString()))), "tool_use", 0, 0, null);
            f.sessions.addMessageWithId(UUID.randomUUID().toString(), source, "user",
                    List.of(new ContentBlock.ToolResultBlock("same-tool-id", "failure tail", true, Map.of("structuredResult", Map.of("url", "https://example.test/result")))), null, 0, 0, null);
        }
        f.sessions.addMessageWithId("image", "A", "user", List.of(new ContentBlock.ImageBlock("image/png", Base64.getEncoder().encodeToString(binary))), null, 0, 0, null);
        var bundle = f.packages.build(f.packages.packagePath("E", UUID.randomUUID().toString()), List.of("A", "B"), () -> {});
        String all = "";
        for (Path part : bundle.transcripts()) {
            assertThat(Files.size(part)).isLessThanOrEqualTo(32768);
            String content = Files.readString(part);
            assertThat(content.lines().count()).isLessThanOrEqualTo(1000);
            assertThat(content).doesNotContain("\ufffd");
            all += content;
        }
        assertThat(all).contains("TAIL_SECRET_4837", "same-tool-id", "is_error=true", "https://example.test/result");
        assertThat(bundle.assets()).anyMatch(a -> a.status().equals("missing") && a.originalPath().endsWith("missing.txt"));
        var copy = bundle.assets().stream().filter(a -> a.originalPath().endsWith("output.bin")).findFirst().orElseThrow();
        assertThat(Files.readAllBytes(Path.of(copy.copiedPath()))).isEqualTo(binary);
        assertThat(MergePackageService.hash(Path.of(copy.copiedPath()), () -> {})).isEqualTo(copy.sha256());
        assertThat(bundle.assets()).anyMatch(a -> "内嵌图片".equals(a.originalPath()) && a.status().equals("copied"));
        assertThat(Files.readString(bundle.path().resolve("index.md"))).contains("index-00001.md");
        assertThat(f.sessions.loadSession("E")).isEmpty();
        var reader = new com.aicodeassistant.tool.impl.FileReadTool(
                new com.aicodeassistant.security.PathSecurityService(new com.aicodeassistant.security.SystemScratchpadPathPolicy(root.resolve("scratch"))),
                f.sessions, org.mockito.Mockito.mock(com.aicodeassistant.engine.KeyFileTracker.class),
                new com.aicodeassistant.tool.impl.EncodingDetector(), org.mockito.Mockito.mock(com.aicodeassistant.tool.impl.ImageResultExternalizer.class));
        var context = com.aicodeassistant.tool.ToolUseContext.of(root.toRealPath().toString(), "E");
        Path lastA = bundle.transcripts().stream().filter(p -> p.getParent().getFileName().toString().equals("from-A")).toList().getLast();
        var readTail = reader.call(com.aicodeassistant.tool.ToolInput.from(Map.of("file_path", lastA.toString())), context);
        assertThat(readTail.isError()).isFalse();
        assertThat(readTail.content()).contains("TAIL_SECRET_4837");
        var artifact = bundle.assets().stream().filter(a -> a.originalPath().endsWith("answer.txt")).findFirst().orElseThrow();
        var readArtifact = reader.call(com.aicodeassistant.tool.ToolInput.from(Map.of("file_path", artifact.copiedPath())), context);
        assertThat(readArtifact.isError()).isFalse();
        assertThat(readArtifact.content()).isEqualTo("ONLY_IN_ARTIFACT_9273");
    }
    @Test void corruptPersistedMessagesFailInsteadOfSilentlySkipping() {
        var f = new MergeFixture(root);
        f.message("A", "good");
        f.jdbc.update("UPDATE messages SET content_json='broken' WHERE session_id='A'");
        assertThatThrownBy(() -> f.packages.build(f.packages.packagePath("E", UUID.randomUUID().toString()), List.of("A", "B"), () -> {}))
                .hasMessageContaining("SOURCE_MESSAGE_UNREADABLE");
    }
    @Test void includesDescendantCheckpointsAndDoesNotFollowExternalSymlinks() throws Exception {
        var f = new MergeFixture(root);
        f.sessions.registerSubAgentSession("subagent-123", root.toString(), "A");
        Path child = Files.createDirectories(root.resolve("scratch/subagent-123"));
        Files.writeString(child.resolve("child-result.txt"), "CHILD_RESULT");
        Path outside = Files.writeString(root.resolve("private.txt"), "OUTSIDE");
        Files.createSymbolicLink(child.resolve("link"), outside);
        f.jdbc.update("INSERT INTO agent_checkpoints(id,run_id,session_id,agent_id,seq,messages_json,created_at) VALUES('cp','subagent-123','subagent-123','123',0,?,?)",
                "[{\"type\":\"assistant\",\"uuid\":\"msg\",\"content\":[{\"type\":\"text\",\"text\":\"PERSISTED_CHILD_TAIL\"}]}]", java.time.Instant.now().toString());
        var bundle = f.packages.build(f.packages.packagePath("E", UUID.randomUUID().toString()), List.of("A", "B"), () -> {});
        String combined = bundle.transcripts().stream().map(p -> { try { return Files.readString(p); } catch (Exception e) { throw new RuntimeException(e); } }).reduce("", String::concat);
        assertThat(combined).contains("PERSISTED_CHILD_TAIL");
        assertThat(bundle.assets()).anyMatch(a -> a.originalPath().endsWith("child-result.txt") && a.status().equals("copied"));
        assertThat(bundle.assets()).noneMatch(a -> a.originalPath().endsWith("private.txt") && a.status().equals("copied"));
    }
    @Test void splittingHugeSingleRecordPreservesEveryCodepointAndFinalPart() throws Exception {
        String text = "🙂中文xyz".repeat(20000);
        var paths = MergePackageService.writeParts(root, "huge", text);
        assertThat(paths).hasSizeGreaterThan(5);
        for (Path path : paths) assertThat(Files.readString(path).getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(32768);
        assertThat(Files.readString(paths.getLast())).contains("[最后一片]");
    }
    @Test void fileChangedDuringCopyIsOmittedWithWarning() throws Exception {
        var f = new MergeFixture(root);
        Path original = Files.createDirectories(root.resolve("scratch/A")).resolve("changing.txt");
        Files.writeString(original, "original");
        Path target = f.packages.packagePath("E", UUID.randomUUID().toString());
        var changed = new java.util.concurrent.atomic.AtomicBoolean();
        var bundle = f.packages.build(target, List.of("A", "B"), () -> {
            Path copies = target.resolve("from-A/files");
            if (Files.exists(copies) && changed.compareAndSet(false, true)) {
                try { Files.writeString(original, "changed while copying"); }
                catch (java.io.IOException e) { throw new RuntimeException(e); }
            }
        });
        assertThat(changed).isTrue();
        assertThat(bundle.assets()).anyMatch(a -> a.originalPath().endsWith("changing.txt") && a.status().equals("copy_failed") && a.copiedPath() == null);
    }
    @Test void sourceRootSymlinkCannotImportAnotherSessionsFiles() throws Exception {
        var f = new MergeFixture(root);
        Path unrelated = Files.createDirectories(root.resolve("scratch/C"));
        Files.writeString(unrelated.resolve("private.txt"), "UNRELATED_SESSION_DATA");
        Files.createSymbolicLink(root.resolve("scratch/A"), unrelated);
        var bundle = f.packages.build(f.packages.packagePath("E", UUID.randomUUID().toString()), List.of("A", "B"), () -> {});
        assertThat(bundle.assets()).anyMatch(a -> a.sourceSessionId().equals("A") && a.status().equals("ownership_unknown"));
        assertThat(bundle.assets()).noneMatch(a -> a.status().equals("copied"));
    }
    @Test void legacyToolResultsPreserveFailureAndAssociatedBackgroundOutput() throws Exception {
        var f = new MergeFixture(root);
        Path output = Files.createTempFile("agent-legacy-", "-output.txt");
        try {
            Files.writeString(output, "LEGACY_ASYNC_RESULT");
            f.message("A", "placeholder");
            var content = List.of(
                    Map.of("type", "tool_use", "id", "legacy-call", "name", "Agent", "input", Map.of("run_in_background", true)),
                    Map.of("type", "tool_result", "toolUseId", "legacy-call", "isError", true,
                            "content", "Failed\nOutput file: " + output + "\n", "metadata", Map.of("url", "https://example.test/failure")));
            String original = f.json.writeValueAsString(content);
            f.jdbc.update("UPDATE messages SET content_json=? WHERE session_id='A'", original);
            var bundle = f.packages.build(f.packages.packagePath("E", UUID.randomUUID().toString()), List.of("A", "B"), () -> {});
            String all = readTranscripts(bundle);
            assertThat(all).contains("工具结果 legacy-call · is_error=true", "https://example.test/failure");
            var asset = bundle.assets().stream().filter(a -> a.originalPath().equals(output.toString()) && a.status().equals("copied")).findFirst().orElseThrow();
            assertThat(Files.readString(Path.of(asset.copiedPath()))).isEqualTo("LEGACY_ASYNC_RESULT");
            assertThat(f.jdbc.queryForObject("SELECT content_json FROM messages WHERE session_id='A'", String.class)).isEqualTo(original);
        } finally { Files.deleteIfExists(output); }
    }
    @Test void checkpointDeduplicationKeepsChangedVersionsAndEarlierTrimmedMessages() throws Exception {
        var f = new MergeFixture(root);
        f.sessions.registerSubAgentSession("child", root.toString(), "A");
        String old = "{\"type\":\"assistant\",\"uuid\":\"same\",\"content\":[{\"type\":\"text\",\"text\":\"EARLIER_ONLY\"}]}";
        String changed = old.replace("EARLIER_ONLY", "LATER_VERSION");
        for (int n = 0; n < 3; n++) {
            f.jdbc.update("INSERT INTO agent_checkpoints(id,run_id,session_id,agent_id,seq,messages_json,created_at) VALUES(?, 'run', 'child', 'agent', ?, ?, 'now')",
                    List.of("z-first", "a-second", "b-last").get(n), n, "[" + (n < 2 ? old : changed) + "]");
        }
        var bundle = f.packages.build(f.packages.packagePath("E", UUID.randomUUID().toString()), List.of("A", "B"), () -> {});
        String text = readTranscripts(bundle);
        assertThat(text).containsOnlyOnce("EARLIER_ONLY").containsOnlyOnce("LATER_VERSION");
        assertThat(text).contains("完整内容见检查点 z-first / 1");
        assertThat(text.indexOf("z-first")).isLessThan(text.indexOf("a-second"));
    }
    @Test void oversizeRecordFailsBeforeMaterializingItsJson() {
        var f = new MergeFixture(root);
        f.message("A", "placeholder");
        f.jdbc.update("UPDATE messages SET content_json=printf('%.*c', ?, 'x') WHERE session_id='A'", MergePackageService.MAX_RECORD_BYTES + 1);
        assertThatThrownBy(() -> f.packages.build(f.packages.packagePath("E", UUID.randomUUID().toString()), List.of("A", "B"), () -> {}))
                .hasMessageContaining("SOURCE_RECORD_TOO_LARGE_OR_NULL");
    }
    @Test void partsAreWrittenIncrementallyAndReconstructTheExactOriginal() throws Exception {
        String text = "中文🙂abc\r\n".repeat(10000);
        List<Path> paths;
        try (var writer = new MergePackageService.PartWriter(root, "stream", () -> {})) {
            writer.append(text);
            assertThat(Files.exists(root.resolve("stream-00001.md"))).isTrue();
            writer.finish(); paths = writer.paths();
        }
        StringBuilder reconstructed = new StringBuilder();
        for (Path path : paths) {
            String part = Files.readString(path);
            reconstructed.append(part, part.indexOf("\n\n") + 2, part.lastIndexOf("\n\n"));
            assertThat(Files.size(path)).isLessThanOrEqualTo(32768);
            assertThat(part.lines().count()).isLessThanOrEqualTo(1000);
        }
        assertThat(reconstructed.toString()).isEqualTo(text);
    }
    @Test void copyAllowanceIsSharedAcrossSourcesAndResetsForEachOperation() throws Exception {
        var f = new MergeFixture(root);
        org.springframework.test.util.ReflectionTestUtils.setField(f.packages, "maxCopyBytes", 10L);
        for (String id : List.of("A", "B")) Files.writeString(Files.createDirectories(root.resolve("scratch/" + id)).resolve("asset.txt"), "123456");
        for (int attempt = 0; attempt < 2; attempt++) {
            var bundle = f.packages.build(f.packages.packagePath("E", UUID.randomUUID().toString()), List.of("A", "B"), () -> {});
            assertThat(bundle.copiedCount()).isEqualTo(1);
            assertThat(bundle.warningCount()).isEqualTo(1);
            assertThat(bundle.assets()).anyMatch(a -> "copy_failed".equals(a.status()) && a.reason().contains("容量上限"));
            assertThat(Files.readString(bundle.path().resolve("manifest.json"))).contains("容量上限");
        }
        for (String id : List.of("A", "B")) assertThat(Files.readString(root.resolve("scratch/" + id + "/asset.txt"))).isEqualTo("123456");
    }
    @Test void embeddedImagesShareTheFileCopyAllowance() throws Exception {
        var f = new MergeFixture(root);
        org.springframework.test.util.ReflectionTestUtils.setField(f.packages, "maxCopyBytes", 10L);
        f.sessions.addMessageWithId("img", "A", "user", List.of(new ContentBlock.ImageBlock("image/png",
                Base64.getEncoder().encodeToString(new byte[6]))), null, 0, 0, null);
        Files.writeString(Files.createDirectories(root.resolve("scratch/A")).resolve("asset.txt"), "123456");
        var bundle = f.packages.build(f.packages.packagePath("E", UUID.randomUUID().toString()), List.of("A", "B"), () -> {});
        assertThat(bundle.copiedCount()).isEqualTo(1);
        assertThat(bundle.assets()).anyMatch(a -> "内嵌图片".equals(a.originalPath()) && "copied".equals(a.status()));
        assertThat(bundle.assets()).anyMatch(a -> a.originalPath().endsWith("asset.txt") && "copy_failed".equals(a.status()));
    }
    @Test void lowDiskSpaceStopsRequiredTextWithoutTouchingSources() throws Exception {
        var f = new MergeFixture(root);
        var packages = org.mockito.Mockito.spy(f.packages);
        org.springframework.test.util.ReflectionTestUtils.setField(packages, "minFreeBytes", 128L);
        org.mockito.Mockito.doReturn(128L).when(packages).availableBytes(org.mockito.ArgumentMatchers.any());
        f.message("A", "PRESERVE_PROCESS");
        f.sessions.addMessageWithId("img", "A", "user", List.of(new ContentBlock.ImageBlock("image/png", "AQID")), null, 0, 0, null);
        Path original = Files.writeString(Files.createDirectories(root.resolve("scratch/A")).resolve("asset.txt"), "original");
        assertThatThrownBy(() -> packages.build(packages.packagePath("E", UUID.randomUUID().toString()), List.of("A", "B"), () -> {}))
                .hasMessageContaining("MERGE_DISK_SPACE_LOW");
        assertThat(Files.readString(original)).isEqualTo("original");
    }
    @Test void diskDropDuringCopyRemovesPartialCopyWithoutTouchingSource() throws Exception {
        var f = new MergeFixture(root);
        var packages = org.mockito.Mockito.spy(f.packages);
        org.springframework.test.util.ReflectionTestUtils.setField(packages, "minFreeBytes", 128L);
        org.mockito.Mockito.doAnswer(call -> {
            Path dir = call.getArgument(0);
            try (var files = Files.walk(dir)) {
                if (files.anyMatch(p -> { try { return Files.isRegularFile(p) && Files.size(p) >= 65536; }
                    catch (java.io.IOException e) { throw new RuntimeException(e); } })) return 128L;
            }
            return 1000000L;
        }).when(packages).availableBytes(org.mockito.ArgumentMatchers.any());
        Path original = Files.write(Files.createDirectories(root.resolve("scratch/A")).resolve("large.bin"), new byte[150000]);
        var bundle = packages.build(packages.packagePath("E", UUID.randomUUID().toString()), List.of("A", "B"), () -> {});
        assertThat(bundle.copiedCount()).isZero();
        assertThat(bundle.assets()).anyMatch(a -> "copy_failed".equals(a.status()) && a.reason().contains("安全余量"));
        try (var files = Files.list(bundle.path().resolve("from-A/files"))) { assertThat(files).isEmpty(); }
        assertThat(Files.size(original)).isEqualTo(150000);
    }
    @Test void growingSourceCannotExceedCopyAllowanceMidStream() throws Exception {
        var f = new MergeFixture(root);
        org.springframework.test.util.ReflectionTestUtils.setField(f.packages, "maxCopyBytes", 65536L);
        Path original = Files.write(Files.createDirectories(root.resolve("scratch/A")).resolve("growing.bin"), new byte[32768]);
        Path target = f.packages.packagePath("E", UUID.randomUUID().toString());
        var grown = new java.util.concurrent.atomic.AtomicBoolean();
        var bundle = f.packages.build(target, List.of("A", "B"), () -> {
            if (Files.exists(target.resolve("from-A/files")) && grown.compareAndSet(false, true)) {
                try { Files.write(original, new byte[131072], StandardOpenOption.APPEND); }
                catch (java.io.IOException e) { throw new RuntimeException(e); }
            }
        });
        assertThat(grown).isTrue();
        assertThat(bundle.assets()).anyMatch(a -> "copy_failed".equals(a.status()) && a.reason().contains("容量上限"));
        try (var files = Files.list(target.resolve("from-A/files"))) { assertThat(files).isEmpty(); }
        assertThat(Files.size(original)).isEqualTo(163840);
    }
    @Test void exportsPersistedTruncationAndFailureFacts() throws Exception {
        var f = new MergeFixture(root);
        f.sessions.addMessageWithId("partial", "A", "assistant", List.of(new ContentBlock.TextBlock("partial answer")), "max_tokens", 0, 0, null);
        f.jdbc.update("INSERT INTO run_envelopes(id,session_id,status,exit_reason,abort_reason,error_summary) VALUES('failed-run','A','failed','OUTPUT_RECOVERY_EXHAUSTED',NULL,'could not finish')");
        f.jdbc.update("INSERT INTO agent_checkpoints(id,run_id,session_id,agent_id,seq,messages_json,created_at) VALUES('cp','child-run','B','child',0,?,'now')",
                "[{\"type\":\"assistant\",\"stopReason\":\"max_tokens\",\"content\":[{\"type\":\"text\",\"text\":\"child partial\"}]}]");
        var bundle = f.packages.build(f.packages.packagePath("E", UUID.randomUUID().toString()), List.of("A", "B"), () -> {});
        assertThat(readTranscripts(bundle)).contains("partial answer", "child partial", "stop_reason): max_tokens",
                "failed-run", "failed", "OUTPUT_RECOVERY_EXHAUSTED", "could not finish");
        assertThat(readTranscripts(bundle).split("stop_reason\\): max_tokens", -1)).hasSize(3);
    }
    @ParameterizedTest @ValueSource(strings = {"failed", "unverified"})
    void exportsArtifactVerificationSeparatelyFromCompletedRun(String verification) throws Exception {
        var f = new MergeFixture(root);
        f.jdbc.update("INSERT INTO run_envelopes(id,session_id,status,exit_reason,verification_status) VALUES('finished-run','A','completed','model_finished',?)", verification);
        var bundle = f.packages.build(f.packages.packagePath("E", UUID.randomUUID().toString()), List.of("A", "B"), () -> {});
        assertThat(readTranscripts(bundle)).contains("finished-run")
                .containsPattern("\"status\"\\s*:\\s*\"completed\"")
                .containsPattern("\"verification_status\"\\s*:\\s*\"" + verification + "\"");
    }
    @Test void diskDropDuringTextExportStopsBeforeUsingReserve() throws Exception {
        var f = new MergeFixture(root);
        var packages = org.mockito.Mockito.spy(f.packages);
        org.springframework.test.util.ReflectionTestUtils.setField(packages, "minFreeBytes", 128L);
        org.mockito.Mockito.doAnswer(call -> {
            try (var files = Files.walk(call.<Path>getArgument(0))) {
                long written = 0;
                for (Path file : files.filter(Files::isRegularFile).toList()) written += Files.size(file);
                return 50128L - written;
            }
        }).when(packages).availableBytes(org.mockito.ArgumentMatchers.any());
        f.message("A", "a".repeat(30000)); f.message("B", "b".repeat(30000));
        Path path = packages.packagePath("E", UUID.randomUUID().toString());
        assertThatThrownBy(() -> packages.build(path, List.of("A", "B"), () -> {}))
                .hasMessageContaining("MERGE_DISK_SPACE_LOW");
        try (var files = Files.walk(path)) {
            long bytes = 0;
            for (Path file : files.filter(Files::isRegularFile).toList()) bytes += Files.size(file);
            assertThat(bytes).isPositive().isLessThanOrEqualTo(50000);
        }
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM messages", Integer.class)).isEqualTo(2);
    }
    private static String readTranscripts(MergePackageService.Bundle bundle) throws Exception {
        StringBuilder result = new StringBuilder();
        for (Path path : bundle.transcripts()) result.append(Files.readString(path));
        return result.toString();
    }

}
