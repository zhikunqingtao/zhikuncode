package com.aicodeassistant.artifact;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.config.database.V017_RebuildArtifactV2Schema;
import com.aicodeassistant.run.RunControlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ArtifactManifestServiceV2Test {
    @TempDir Path temp;
    private SqliteConfig sqlite;

    @BeforeEach void canonicalizeWorkspace() throws Exception {
        temp = temp.toRealPath();
    }

    @AfterEach void close() { if (sqlite != null) sqlite.destroy(); }

    @Test
    void declareSealAndVerifyAreDistinctAuthoritativeStates() throws Exception {
        Fixture fixture=fixture();
        Path output=temp.resolve("output.txt");
        Files.writeString(output,"complete");

        ArtifactEntry declared=fixture.service.declare("r1","s1","tool-1",output.toString(),
                "created","sha256",temp.toString());
        assertThat(declared.state()).isEqualTo("declared");

        ArtifactEntry sealed=fixture.service.sealFromFile("r1",output.toString(),temp.toString());
        assertThat(sealed.state()).isEqualTo("sealed");
        VerificationResult result=fixture.service.verify(sealed.manifestId());
        assertThat(result.status()).isEqualTo("verified");
        assertThat(fixture.service.getManifest("r1").orElseThrow().entries().getFirst().state())
                .isEqualTo("integrity_verified");
    }

    @Test
    void declaredButUnsealedArtifactCanNeverVerify() throws Exception {
        Fixture fixture=fixture();
        Path output=temp.resolve("pending.txt");
        Files.writeString(output,"exists");
        ArtifactEntry declared=fixture.service.declare("r2","s1","tool-2",output.toString(),
                "created","sha256",temp.toString());

        VerificationResult result=fixture.service.verify(declared.manifestId());
        assertThat(result.status()).isEqualTo("unverified");
        assertThat(result.failures()).extracting(VerificationResult.FailureDetail::reason)
                .contains("SEALED_HASH_MISSING");
    }

    @Test
    void shellStyleDeleteIsObservedButNotReportedAsIntegrityVerified() throws Exception {
        Fixture fixture=fixture();
        Path output=temp.resolve("delete-me.txt");
        Files.writeString(output,"snapshot-before-delete");
        ArtifactEntry declared=fixture.service.declare("r3","s1","tool-3",output.toString(),
                "delete","sha256",temp.toString());
        fixture.service.sealFromFile("r3",output.toString(),temp.toString());

        Files.delete(output);
        VerificationResult result=fixture.service.verify(declared.manifestId());

        assertThat(result.status()).isEqualTo("unverified");
        assertThat(result.failures()).extracting(VerificationResult.FailureDetail::reason)
                .containsExactly("DELETE_CONTENT_NOT_ATOMICALLY_VERIFIED");
        ArtifactEntry verified=fixture.service.getManifest("r3").orElseThrow().entries().getFirst();
        assertThat(verified.state()).isEqualTo("unverified");
        assertThat(verified.validatorResultJson()).contains("deletion_snapshot", "preDeleteSha256");
    }

    @Test
    void externalArtifactsRequireExplicitAdmittedPathAndCanBeSealed()
            throws Exception {
        Fixture fixture = fixture();
        Path workspace = Files.createDirectory(
                temp.resolve("artifact-workspace")).toRealPath();
        Path outside = Files.writeString(
                temp.resolve("artifact-outside.txt"), "complete")
                .toRealPath();

        assertThatThrownBy(() -> fixture.service.declare(
                "r4", "s1", "tool-strict", outside.toString(),
                "created", "sha256", workspace.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ARTIFACT_PATH_INVALID");

        java.util.concurrent.atomic.AtomicReference<ArtifactEntry> declared =
                new java.util.concurrent.atomic.AtomicReference<>();
        fixture.transaction.executeWithoutResult(ignored ->
                declared.set(fixture.service
                        .declareAuthorizedExternalInCurrentTransaction(
                                "r4", "s1", "tool-authorized",
                                outside.toString(), "created", "sha256",
                                workspace.toString())));
        ArtifactEntry sealed = fixture.service
                .sealAuthorizedExternalFromFile(
                        "r4", outside.toString(), workspace.toString());

        assertThat(declared.get().filePath())
                .isEqualTo(outside.toString());
        assertThat(sealed.state()).isEqualTo("sealed");
        assertThat(fixture.service.verify(sealed.manifestId()).status())
                .isEqualTo("verified");
    }

    @Test
    void laterRunCanResolveOnlyVerifiedArtifactsFromItsOwnSession() throws Exception {
        Fixture fixture = fixture();
        Path own = Files.writeString(temp.resolve("own.txt"), "same-session");
        ArtifactEntry ownEntry = fixture.service.declare("r1", "s1", "tool-own",
                own.toString(), "created", "sha256", temp.toString());
        fixture.service.sealFromFile("r1", own.toString(), temp.toString());
        fixture.service.verify(ownEntry.manifestId());

        Path other = Files.writeString(temp.resolve("other.txt"), "other-session");
        ArtifactEntry otherEntry = fixture.service.declare("r5", "s2", "tool-other",
                other.toString(), "created", "sha256", temp.toString());
        fixture.service.sealFromFile("r5", other.toString(), temp.toString());
        fixture.service.verify(otherEntry.manifestId());

        assertThat(fixture.service.getManifestsForRunSession("r2"))
                .extracting(ArtifactManifest::runId).containsExactly("r1");
        assertThat(fixture.service.getManifestsForRunSession("r6"))
                .extracting(ArtifactManifest::runId).containsExactly("r5");
    }

    @Test
    void laterRootRunIncludesDescendantSubagentArtifactsButExcludesOtherLineages()
            throws Exception {
        Fixture fixture = fixture();
        Path childOutput = Files.writeString(
                temp.resolve("child.txt"), "same-root-child");
        ArtifactEntry childEntry = fixture.service.declare(
                "r-child", "subagent-s1", "tool-child",
                childOutput.toString(), "created", "sha256", temp.toString());
        fixture.service.sealFromFile(
                "r-child", childOutput.toString(), temp.toString());
        fixture.service.verify(childEntry.manifestId());

        Path grandchildOutput = Files.writeString(
                temp.resolve("grandchild.txt"), "same-root-grandchild");
        ArtifactEntry grandchildEntry = fixture.service.declare(
                "r-grandchild", "subagent-nested", "tool-grandchild",
                grandchildOutput.toString(), "created", "sha256", temp.toString());
        fixture.service.sealFromFile(
                "r-grandchild", grandchildOutput.toString(), temp.toString());
        fixture.service.verify(grandchildEntry.manifestId());

        Path unrelatedOutput = Files.writeString(
                temp.resolve("unrelated-child.txt"), "other-root-child");
        ArtifactEntry unrelatedEntry = fixture.service.declare(
                "r-other-child", "subagent-s2", "tool-other-child",
                unrelatedOutput.toString(), "created", "sha256", temp.toString());
        fixture.service.sealFromFile(
                "r-other-child", unrelatedOutput.toString(), temp.toString());
        fixture.service.verify(unrelatedEntry.manifestId());

        assertThat(fixture.service.getManifestsForRunSession("r2"))
                .extracting(ArtifactManifest::runId)
                .containsExactlyInAnyOrder("r-child", "r-grandchild");
        assertThat(fixture.service.getManifestsForRunSession("r6"))
                .extracting(ArtifactManifest::runId)
                .containsExactly("r-other-child");
    }

    private Fixture fixture() {
        DatabaseResolver resolver=new DatabaseResolver("",temp.resolve("db").toString());
        sqlite=new SqliteConfig(resolver);
        var ds=sqlite.getProjectDataSource(Path.of("ignored"));
        JdbcTemplate jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE sessions(id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE run_envelopes("
                + "id TEXT PRIMARY KEY, session_id TEXT NOT NULL, parent_run_id TEXT)");
        jdbc.execute("CREATE TABLE artifact_manifests(manifest_id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE artifact_entries(artifact_id TEXT PRIMARY KEY)");
        new V017_RebuildArtifactV2Schema(jdbc).execute();
        jdbc.update("INSERT INTO sessions(id) VALUES"
                + "('s1'),('s2'),('subagent-s1'),('subagent-nested'),('subagent-s2')");
        jdbc.update("INSERT INTO run_envelopes(id,session_id,parent_run_id) VALUES"
                + "('r1','s1',NULL),('r2','s1',NULL),('r3','s1',NULL),('r4','s1',NULL),"
                + "('r5','s2',NULL),('r6','s2',NULL),"
                + "('r-child','subagent-s1','r1'),"
                + "('r-grandchild','subagent-nested','r-child'),"
                + "('r-other-child','subagent-s2','r5')");
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(ds);
        ArtifactManifestService service=new ArtifactManifestService(jdbc,sqlite,resolver,
                transactionManager,new ObjectMapper(),mock(RunControlService.class),
                new com.aicodeassistant.security.ManagedWorkspacePathResolver(),
                new com.aicodeassistant.security.ManagedPathLockManager());
        return new Fixture(service,
                new TransactionTemplate(transactionManager));
    }

    private record Fixture(
            ArtifactManifestService service,
            TransactionTemplate transaction) {}
}
