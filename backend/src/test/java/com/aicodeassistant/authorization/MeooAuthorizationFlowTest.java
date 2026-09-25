package com.aicodeassistant.authorization;

import com.aicodeassistant.artifact.meoo.*;
import com.aicodeassistant.config.database.*;
import com.aicodeassistant.config.meoo.MeooPublishProperties;
import com.aicodeassistant.interaction.*;
import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.run.RunControlService;
import com.aicodeassistant.security.*;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.artifact.PublishMeooTool;
import com.aicodeassistant.tool.bash.BashSecurityAnalyzer;
import com.aicodeassistant.verify.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MeooAuthorizationFlowTest {
    @TempDir Path temp;
    @Test void deniedOnceCausesZeroCloudWrites() throws Exception {
        try(var f=new Fixture(temp, false)) {
            var pending=f.request("denied");
            var request=f.delivered();
            assertThat(f.json.readTree(request.promptJson()).path("riskLevel").asText()).isEqualTo("high");
            f.decide(request,false);
            assertThatThrownBy(()->pending.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(AuthorizationException.class);
            verifyNoInteractions(f.cli);
            assertThat(f.jdbc.queryForObject("SELECT count(*) FROM meoo_publications",Integer.class)).isZero();
        }
    }
    @Test void approvedOncePassesRealFinalRecheckAndRepeatToolDeliveryCreatesNoNewProject() throws Exception {
        try(var f=new Fixture(temp, false)) {
            when(f.cli.execute(argThat(a->a!=null && a.getFirst().equals("projects")),any(),any(),any())).thenReturn(f.json.readTree("{\"urlId\":\"new-site\"}"));
            when(f.cli.execute(argThat(a->a!=null && a.getFirst().equals("deploy")),any(),any(),any())).thenReturn(f.json.readTree("{\"version\":1,\"accessUrl\":\"https://new-site.meoo.fun\",\"projectUrl\":\"https://meoo.com/chat/new-site\"}"));
            var pending=f.request("approved");f.decide(f.delivered(),true);
            assertThat(pending.get(5,TimeUnit.SECONDS).isError()).isFalse();
            assertThat(f.jdbc.queryForObject("SELECT count(*) FROM permission_grants",Integer.class)).isZero();
            // Durable response and publication row survive a replay of the same tool call.
            assertThat(f.request("approved").get(5,TimeUnit.SECONDS).isError()).isFalse();
            verify(f.cli,times(1)).execute(argThat(a->a!=null && a.getFirst().equals("projects")),any(),any(),any());
        }
    }

    @Test void autoApproveModePublishesWithoutInteraction() throws Exception {
        try(var f=new Fixture(temp,false,null,null,PermissionMode.AUTO_APPROVE)) {
            when(f.cli.execute(argThat(a->a!=null && a.getFirst().equals("projects")),any(),any(),any())).thenReturn(f.json.readTree("{\"urlId\":\"new-site\"}"));
            when(f.cli.execute(argThat(a->a!=null && a.getFirst().equals("deploy")),any(),any(),any())).thenReturn(f.json.readTree("{\"version\":1,\"accessUrl\":\"https://new-site.meoo.fun\",\"projectUrl\":\"https://meoo.com/chat/new-site\"}"));
            // AUTO_APPROVE replaces the per-publication permission card; policy checks,
            // snapshot binding and the final dynamic recheck still execute.
            assertThat(f.request("auto-approved").get(5,TimeUnit.SECONDS).isError()).isFalse();
            assertThat(f.interactions.pending(f.context.sessionId())).isEmpty();
            assertThat(f.jdbc.queryForObject("SELECT count(*) FROM meoo_publications",Integer.class)).isEqualTo(1);
            verify(f.cli,times(1)).execute(argThat(a->a!=null && a.getFirst().equals("projects")),any(),any(),any());
            // Replaying the same tool call stays idempotent without any interaction.
            assertThat(f.request("auto-approved").get(5,TimeUnit.SECONDS).isError()).isFalse();
            verify(f.cli,times(1)).execute(argThat(a->a!=null && a.getFirst().equals("projects")),any(),any(),any());
        }
    }

    static final class Fixture implements AutoCloseable {
        final ObjectMapper json=new ObjectMapper().findAndRegisterModules(); final SqliteConfig sqlite;
        final JdbcTemplate jdbc; final DurableInteractionService interactions;
        final RunControlService runs; final EvidenceStore evidence; final MeooPublicationPolicy policy;
        final MeooCliClient cli; final PublishMeooTool tool; final AuthorizationService authorization;
        final ToolExecutionGateway gateway; final ToolUseContext context; final ToolInput input;
        Future<ToolResult> lastRequest;
        final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
        Fixture(Path base, boolean live) throws Exception { this(base,live,null,null,PermissionMode.DEFAULT); }
        Fixture(Path base, boolean live, Path reportPath, Path source) throws Exception { this(base,live,reportPath,source,PermissionMode.DEFAULT); }
        Fixture(Path base, boolean live, Path reportPath, Path source, PermissionMode mode) throws Exception {
            Files.createDirectories(base);
            var resolver=new DatabaseResolver("",base.toString());sqlite=new SqliteConfig(resolver);
            var ds=sqlite.getProjectDataSource(base);jdbc=new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY,working_dir TEXT)");
            jdbc.execute("CREATE TABLE IF NOT EXISTS run_envelopes(id TEXT PRIMARY KEY,session_id TEXT NOT NULL,parent_run_id TEXT,status TEXT NOT NULL,version INTEGER NOT NULL DEFAULT 0,agent_type TEXT,model TEXT NOT NULL,prompt_hash TEXT,started_at TEXT NOT NULL,finished_at TEXT,terminal_at TEXT,exit_reason TEXT,requested_exit_reason TEXT,verification_status TEXT NOT NULL,waiting_reason TEXT,abort_reason TEXT,total_tokens INTEGER NOT NULL,total_cost_usd REAL NOT NULL,tool_call_count INTEGER NOT NULL,turn_count INTEGER NOT NULL,error_summary TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)");
            jdbc.execute("CREATE TABLE IF NOT EXISTS run_event_log(id INTEGER PRIMARY KEY AUTOINCREMENT,run_id TEXT NOT NULL,seq INTEGER NOT NULL,event_type TEXT NOT NULL,event_data TEXT NOT NULL,ts INTEGER NOT NULL,UNIQUE(run_id,seq))");
            new V015_CreateInteractionSchema(jdbc).execute();new V019_CreateAuthorizationSchema(jdbc).execute();new V023_CreateMeooPublications(jdbc).execute();
            new V007_AddEvidenceTables(jdbc).execute();
            if(jdbc.queryForList("PRAGMA table_info(evidence_bundles)").stream().noneMatch(r->"run_id".equals(r.get("name")))) jdbc.execute("ALTER TABLE evidence_bundles ADD COLUMN run_id TEXT");
            Path root=source==null?Files.createDirectory(base.resolve("site")).toRealPath():source.toRealPath();
            if(!live) Files.writeString(root.resolve("index.html"),"<h1>Test</h1>");
            String session="meoo-acceptance-"+UUID.randomUUID();
            jdbc.update("INSERT INTO sessions(id,working_dir) VALUES(?,?)",session,root.toString());
            var tx=new DataSourceTransactionManager(ds);
            runs=new RunControlService(jdbc,sqlite,resolver,tx,json);
            var run=runs.start(session,null,"main","test");
            var workspace=new WorkspaceIdentityService();
            var grants=new PermissionGrantRepository(jdbc,sqlite,resolver,tx,json,workspace);
            interactions=new DurableInteractionService(jdbc,sqlite,resolver,tx,json,runs,event->{},grants);
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(interactions,"reconcileCapacityAfterRestart");
            var props=new MeooPublishProperties();props.setEnabled(true);
            if(!live) {
                Path credentials=base.resolve("credentials.json");
                Files.writeString(credentials,"{\"apiBaseUrl\":\"https://meoo.com\",\"credentialType\":\"api_key\",\"apiKey\":\"meoo_ak_test_fixture_only\",\"userId\":\"test-account\"}");
                Files.setPosixFilePermissions(credentials,PosixFilePermissions.fromString("rw-------"));props.setCredentialsFile(credentials.toString());
            }
            evidence=new EvidenceStore(jdbc,json,new SensitiveDataFilter());policy=new MeooPublicationPolicy(props,evidence);
            context=ToolUseContext.of(root.toString(),session).withCurrentRunId(run.id());
            String runtime="static";
            com.fasterxml.jackson.databind.JsonNode report=null;
            if(live) { report=json.readTree(Files.readString(reportPath));assertThat(report.path("verdict").asText()).isEqualTo("verified");assertThat(report.path("root").asText()).isEqualTo(root.toString());runtime=report.path("runtime").asText(); }
            var snapshot=policy.inspect(ToolInput.from(Map.of("path",".","runtime",runtime)),context,false);
            if(live) assertThat(snapshot.sha256()).isEqualTo(report.path("sha256").asText());
            var saved=evidence.save(EvidenceBundle.builder().sessionId(session).runId(run.id()).kind("journey").verdict("verified")
                .claim(live?"Verified by scripts/meoo/verify-acceptance.mjs; see external report":"test fixture")
                .items(List.of(new EvidenceItem(null,"test","Verified snapshot",null,Map.of("workspace",root.toString(),"meooSnapshotSha256",snapshot.sha256(),"meooRuntime",runtime)))).build());
            input=ToolInput.from(Map.of("path",".","runtime",runtime,"name",live?"ZhikunCode "+root.getFileName()+" acceptance":"Demo","verification_id",saved.bundleId()));
            cli=live?new MeooCliClient(props,new com.aicodeassistant.tool.process.ManagedProcessRunner(),json):mock(MeooCliClient.class);
            var verifier=live?new MeooAccessVerifier():mock(MeooAccessVerifier.class);
            if(!live)when(verifier.verify(anyString(),any())).thenReturn(true);
            tool=new PublishMeooTool(new MeooPublicationService(policy,new MeooPublicationStore(jdbc),cli,verifier),props);
            var registry=new OperationAnalyzerRegistry(json,mock(BashSecurityAnalyzer.class),new SensitiveDataFilter(),mock(PathSecurityService.class));registry.setMeooPublicationPolicy(policy);
            var modes=mock(PermissionModeManager.class);when(modes.getMode(anyString())).thenReturn(mode);
            authorization=new AuthorizationService(new AuthorizationSubjectResolver(jdbc,workspace),registry,grants,interactions,modes,runs,json,mock(ProjectWorkspaceService.class));
            gateway=new ToolExecutionGateway(authorization,runs);
        }
        Future<ToolResult> request(String toolId) {
            return lastRequest=executor.submit(()->{
                var ctx=context.withToolUseId(toolId);
                try(var frozen=new FrozenToolInputFactory(json,1024*1024,4*1024*1024).freeze(tool.getName(),input)) {
                    return gateway.execute(tool,authorization.authorize(tool,frozen,input,ctx),ctx);
                }
            });
        }
        InteractionRequest delivered() throws Exception {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<until) {
                if(lastRequest!=null && lastRequest.isDone()) lastRequest.get();
                var pending=interactions.pending(context.sessionId());
                if(!pending.isEmpty()) {
                    String id=pending.getFirst().interactionId();interactions.markDispatched(id,"acceptance-review");
                    var r=interactions.findById(id);interactions.acknowledgeReceived(id,r.deliveryGeneration(),"acceptance-review");return interactions.findById(id);
                }
                Thread.sleep(50);
            }
            throw new AssertionError("No per-publication authorization card was created");
        }
        void decide(InteractionRequest r, boolean allow) throws Exception {
            var prompt=json.readTree(r.promptJson());
            interactions.decideRequest(r.interactionId(),r.version(),allow?InteractionRequest.Status.ANSWERED:InteractionRequest.Status.DENIED,
                Map.of("operationHash",prompt.path("operationHash").asText(),"deliveryGeneration",r.deliveryGeneration(),"optionId",allow?"allow_once":"deny","decision",allow?"allow":"deny","scope","once","remember",false),allow?"USER_APPROVED":"USER_DENIED");
        }
        public void close() {executor.shutdownNow();sqlite.destroy();}
    }
}
