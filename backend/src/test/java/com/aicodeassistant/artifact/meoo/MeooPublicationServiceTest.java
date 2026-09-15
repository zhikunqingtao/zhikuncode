package com.aicodeassistant.artifact.meoo;

import com.aicodeassistant.config.database.V023_CreateMeooPublications;
import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.artifact.PublishMeooTool;
import com.aicodeassistant.config.meoo.MeooPublishProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MeooPublicationServiceTest {
    @TempDir Path root;
    SingleConnectionDataSource ds; MeooPublicationStore store;MeooPublicationPolicy policy;MeooCliClient cli;MeooAccessVerifier verifier;MeooPublicationService service;
    ToolUseContext context; ToolInput input; MeooPublicationPolicy.Snapshot snapshot;
    @BeforeEach void setup() throws Exception {
        ds=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);var jdbc=new JdbcTemplate(ds);new V023_CreateMeooPublications(jdbc).execute();
        store=new MeooPublicationStore(jdbc);policy=mock(MeooPublicationPolicy.class);cli=mock(MeooCliClient.class);verifier=mock(MeooAccessVerifier.class);
        service=new MeooPublicationService(policy,store,cli,verifier);
        context=ToolUseContext.of(root.toString(),"session").withCurrentRunId("run").withToolUseId("tool-1");
        input=ToolInput.from(Map.of("path",".","runtime","static","verification_id","ev","_approved_sha256","hash","_approved_account","account"));
        snapshot=new MeooPublicationPolicy.Snapshot(root,".","static","Demo","account",List.of(new MeooPublicationPolicy.FileFact("index.html",root.resolve("index.html"),20,"filehash")),20,"hash","ev");
        when(policy.inspect(any(),any(),eq(true))).thenReturn(snapshot);
        doAnswer(call->{Path destination=call.getArgument(1);Files.createDirectories(destination);Files.writeString(destination.resolve("index.html"),"<h1>test</h1>");return null;}).when(policy).stage(any(),any());
        var json=new ObjectMapper();
        when(cli.execute(argThat(args->args!=null && args.getFirst().equals("projects")),any(),any(),any())).thenReturn(json.readTree("{\"urlId\":\"new-site\"}"));
        when(cli.execute(argThat(args->args!=null && args.getFirst().equals("deploy")),any(),any(),any())).thenReturn(json.readTree("{\"version\":1,\"accessUrl\":\"https://new-site.meoo.fun\",\"projectUrl\":\"https://meoo.com/chat/new-site\"}"));
        when(verifier.verify(anyString(),any())).thenReturn(true);
    }
    @AfterEach void close(){ds.destroy();}
    @Test void resultCardOmitsSuccessGuidanceAndPreservesActionableStates() {
        var publicationService=mock(MeooPublicationService.class);
        var tool=new PublishMeooTool(publicationService,new MeooPublishProperties());
        var json=new ObjectMapper();
        for(String state:List.of("public_verified","deployed_unverified","failed","unknown")) {
            String code=switch(state) {
                case "deployed_unverified" -> "MEOO_ACCESS_UNVERIFIED";
                case "failed" -> "MEOO_BUILD_FAILED";
                default -> null;
            };
            when(publicationService.publish(input,context)).thenReturn(new MeooPublicationStore.Publication(
                "id","Demo","static",state,"new-site","https://meoo.com/chat/new-site","1","https://new-site.meoo.pub",code));
            var card=json.valueToTree(tool.call(input,context).metadata()).path("structuredResult");
            assertThat(card.path("state").asText()).isEqualTo(state);
            if(state.equals("public_verified")) {
                assertThat(card.has("guidance")).isFalse();
                assertThat(card.has("errorCode")).isFalse();
            } else {
                assertThat(card.path("guidance").asText()).isEqualTo(MeooException.guidance(code)).isNotBlank();
            }
        }
    }
    @Test void createsOncePerToolCallAndNewSiteOnNewCall() {
        var first=service.publish(input,context);var same=service.publish(input,context);
        assertThat(first.state()).isEqualTo("public_verified");assertThat(same.id()).isEqualTo(first.id());
        verify(cli,times(1)).execute(argThat(args->args!=null && args.getFirst().equals("projects")),any(),any(),any());
        var second=service.publish(input,context.withToolUseId("tool-2"));assertThat(second.id()).isNotEqualTo(first.id());
        verify(cli,times(2)).execute(argThat(args->args!=null && args.getFirst().equals("projects")),any(),any(),any());
        verify(cli,times(2)).execute(argThat(args->args!=null && args.containsAll(List.of("--skip-build","--skip-push","--project","new-site"))),any(),any(),any());
    }
    @Test void timeoutKeepsProjectAndDoesNotRetry() {
        when(cli.execute(argThat(args->args!=null && args.getFirst().equals("deploy")),any(),any(),any())).thenThrow(new MeooException("MEOO_REMOTE_RESULT_UNKNOWN"));
        var result=service.publish(input,context);assertThat(result.state()).isEqualTo("unknown");assertThat(result.projectId()).isEqualTo("new-site");
        assertThat(result.projectUrl()).isEqualTo("https://meoo.com/chat/new-site");
        service.publish(input,context);verify(cli,times(1)).execute(argThat(args->args!=null && args.getFirst().equals("deploy")),any(),any(),any());
    }
    @Test void quotaAndBadUrlFailWithoutLeakingProviderOutput() throws Exception {
        when(cli.execute(argThat(args->args!=null && args.getFirst().equals("deploy")),any(),any(),any())).thenThrow(new MeooException("MEOO_QUOTA_EXCEEDED"));
        assertThat(service.publish(input,context).state()).isEqualTo("failed");
        when(cli.execute(argThat(args->args!=null && args.getFirst().equals("deploy")),any(),any(),any())).thenReturn(new ObjectMapper().readTree("{\"version\":1,\"accessUrl\":\"http://127.0.0.1/secret\"}"));
        var result=service.publish(input,context.withToolUseId("tool-2"));assertThat(result.accessUrl()).isNull();assertThat(result.state()).isEqualTo("unknown");
    }
    @Test void rejectsMissingApprovalBeforeAnyRemoteAction() {
        assertThatThrownBy(()->service.publish(ToolInput.from(Map.of("path",".","runtime","static")),context)).hasMessage("MEOO_APPROVED_SNAPSHOT_REQUIRED");
        verifyNoInteractions(cli);
    }
    @Test void imageDeployUsesRemoteRuntimeAndRetainsBuildFailure() throws Exception {
        var image=new MeooPublicationPolicy.Snapshot(root,".","image","Demo","account",snapshot.files(),20,"hash","ev");
        when(policy.inspect(any(),any(),eq(true))).thenReturn(image);
        // Exact shape of CLI 0.5.3 image success: no projectUrl field.
        when(cli.execute(argThat(a->a!=null && a.getFirst().equals("deploy")),any(),any(),any())).thenReturn(new ObjectMapper().readTree("{\"version\":\"1\",\"accessUrl\":\"https://new-site.meoo.pub\",\"imageTag\":\"fixture:1\"}"));
        var published=service.publish(input,context);
        assertThat(published.state()).isEqualTo("public_verified");
        assertThat(published.projectUrl()).isEqualTo("https://meoo.com/chat/new-site");
        verify(cli).execute(eq(List.of("deploy","--project","new-site","--runtime","image","--force")),any(),any(),any());
        when(cli.execute(argThat(a->a!=null && a.getFirst().equals("deploy")),any(),any(),any())).thenThrow(new MeooException("MEOO_BUILD_FAILED"));
        var failed=service.publish(input,context.withToolUseId("tool-2"));
        assertThat(failed.state()).isEqualTo("failed");assertThat(failed.projectId()).isEqualTo("new-site");
        assertThat(failed.projectUrl()).isEqualTo("https://meoo.com/chat/new-site");
    }
    @Test void missingAccessUrlIsUnknownAndDoesNotInventUrl() throws Exception {
        when(cli.execute(argThat(a->a!=null && a.getFirst().equals("deploy")),any(),any(),any())).thenReturn(new ObjectMapper().readTree("{\"version\":1}"));
        var result=service.publish(input,context);assertThat(result.state()).isEqualTo("unknown");assertThat(result.accessUrl()).isNull();
    }
    @Test void publicCheckFailureIsNotMisreportedAsSuccess() {
        when(verifier.verify(anyString(),any())).thenReturn(false);
        assertThat(service.publish(input,context).state()).isEqualTo("deployed_unverified");
    }
}
