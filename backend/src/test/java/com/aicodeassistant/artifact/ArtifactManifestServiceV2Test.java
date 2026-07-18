package com.aicodeassistant.artifact;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.config.database.V017_RebuildArtifactV2Schema;
import com.aicodeassistant.run.RunControlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ArtifactManifestServiceV2Test {
    @TempDir Path temp;
    private SqliteConfig sqlite;

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

    private Fixture fixture() {
        DatabaseResolver resolver=new DatabaseResolver("",temp.resolve("db").toString());
        sqlite=new SqliteConfig(resolver);
        var ds=sqlite.getProjectDataSource(Path.of("ignored"));
        JdbcTemplate jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE sessions(id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE run_envelopes(id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE artifact_manifests(manifest_id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE artifact_entries(artifact_id TEXT PRIMARY KEY)");
        new V017_RebuildArtifactV2Schema(jdbc).execute();
        jdbc.update("INSERT INTO sessions(id) VALUES('s1')");
        jdbc.update("INSERT INTO run_envelopes(id) VALUES('r1'),('r2'),('r3')");
        ArtifactManifestService service=new ArtifactManifestService(jdbc,sqlite,resolver,
                new DataSourceTransactionManager(ds),new ObjectMapper(),mock(RunControlService.class),
                new com.aicodeassistant.security.ManagedWorkspacePathResolver(),
                new com.aicodeassistant.security.ManagedPathLockManager());
        return new Fixture(service);
    }

    private record Fixture(ArtifactManifestService service) {}
}
