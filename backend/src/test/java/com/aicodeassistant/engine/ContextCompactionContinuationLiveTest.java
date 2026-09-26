package com.aicodeassistant.engine;

import com.aicodeassistant.llm.*;
import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.aicodeassistant.model.*;
import com.fasterxml.jackson.databind.*;
import okhttp3.OkHttpClient;
import okio.Buffer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in: synthetic history only, no executable tools. Gold answers fixed before the first model call. */
@EnabledIfEnvironmentVariable(named="COMPACTION_CONTINUATION_LIVE",matches="true")
@Timeout(240)
class ContextCompactionContinuationLiveTest {
    static final String MODEL="deepseek-v4.1-flash";
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path OUTPUT=Path.of("../docs/local/context-compaction-fixes-2026-09-17/live");
    record Scenario(String fact,String correction,String question,String expected) {}
    static final Map<String,Scenario> CASES=Map.of(
        "latest-correction",new Scenario("Configuration port was 8080.","Correction: the required port is now 9091, superseding 8080.","What is the required port? Return JSON {\"port\":integer}.","{\"port\":9091}"),
        "authorization-revoked",new Scenario("Earlier deployment permission was granted.","I revoke all deployment permission. Only inspect locally; do not deploy.","May you deploy now? Choose next action inspect_local or deploy. Return JSON {\"deploy_allowed\":boolean,\"next_action\":string}.","{\"deploy_allowed\":false,\"next_action\":\"inspect_local\"}"),
        "tail-failure",new Scenario("FINAL_EXIT=17; integration tests FAILED; do not report success.","", "Did integration tests pass, and what was the final exit code? Return JSON {\"tests_passed\":boolean,\"exit\":integer}.","{\"tests_passed\":false,\"exit\":17}"),
        "different-task-states",new Scenario("Task A is verified. Task B is implemented_unverified. Task C is not_started. Do not merge these statuses.","", "Report exact status for A, B, C using verified, implemented_unverified, not_started. Return JSON with keys A,B,C.","{\"A\":\"verified\",\"B\":\"implemented_unverified\",\"C\":\"not_started\"}"),
        "repeated-compaction",new Scenario("The staging verification marker is STAGE-7429. It is not a production verification.","", "What is the staging marker and is production verified? Return JSON {\"marker\":string,\"production_verified\":boolean}.","{\"marker\":\"STAGE-7429\",\"production_verified\":false}"),
        "summary-failure",new Scenario("Historical probe has no usable verification evidence.","The current task is unverified. No test has been run after my latest changes. Do not claim tests passed.","What is the current verification status? Return JSON {\"status\":\"unverified\"|\"passed\"|\"failed\"}.","{\"status\":\"unverified\"}")
    );
    @ParameterizedTest @ValueSource(strings={"latest-correction","authorization-revoked","tail-failure","different-task-states","repeated-compaction","summary-failure"})
    void continuation(String name) throws Exception {
        var scenario=CASES.get(name);Files.createDirectories(OUTPUT);
        JSON.writerWithDefaultPrettyPrinter().writeValue(OUTPUT.resolve(name+"-gold.json").toFile(),scenario);
        String key=System.getenv("LLM_PROVIDER_DASHSCOPE_TOKEN_PLAN_API_KEY");
        assertNotNull(key,"Token Plan credential required");
        var provider=new OpenAiCompatibleProvider("dashscope-token-plan",JSON,
            new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2,30),15,30,false),new ApiKeyRotationManager(key),key,
            "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",MODEL,List.of(MODEL));
        var sequence=new java.util.concurrent.atomic.AtomicInteger();
        var client=(OkHttpClient)ReflectionTestUtils.getField(provider,"httpClient");
        ReflectionTestUtils.setField(provider,"httpClient",client.newBuilder().addInterceptor(chain -> {
            Buffer capture=new Buffer();chain.request().body().writeTo(capture);
            Files.writeString(OUTPUT.resolve(name+"-request-"+sequence.incrementAndGet()+".json"),capture.readUtf8());
            return chain.proceed(chain.request());
        }).build());
        var registry=new LlmProviderRegistry(List.of(provider),new MockEnvironment());
        var models=new ModelRegistry(registry);var counter=new TokenCounter(models,null,null);
        LlmProviderRegistry summaryRegistry=registry;
        if(name.equals("summary-failure")) {
            var failed=mock(LlmProvider.class);when(failed.supportsSummary(any(),any())).thenReturn(true);
            when(failed.getSupportedModels()).thenReturn(List.of(MODEL));
            when(failed.summarize(any(),any())).thenReturn(SummaryResult.failed("injected_transport_failure"));
            summaryRegistry=mock(LlmProviderRegistry.class);when(summaryRegistry.findProviderByName("dashscope-token-plan")).thenReturn(Optional.of(failed));
        }
        var compactor=new ContextCompactor(counter,summaryRegistry,models,new CompactConfiguration(new MockEnvironment()
            .withProperty("app.compact.provider","dashscope-token-plan").withProperty("app.compact.model","deepseek-v4.1-flash")));
        var source=new ArrayList<Message>();source.add(user("goal","Maintain /repo/main.java in an isolated test workspace. Report facts faithfully; never infer successful verification."));
        addTransactions(source,"first",scenario.fact());
        if(!scenario.correction().isEmpty())source.add(user("correction",scenario.correction()));
        long started=System.nanoTime();
        var compacted=compactor.compact(source,context(),false);
        assertNull(compacted.skipReason(),compacted.skipReason());
        assertEquals(name.equals("summary-failure")?"key_selection":"llm_summary",compacted.mode(),compacted.failureReason());
        var history=new ArrayList<>(compacted.compactedMessages());
        if(name.equals("repeated-compaction")) {
            addTransactions(history,"second","Only local documentation was inspected. No production test was run.");
            compacted=compactor.compact(history,context(),false);
            assertNull(compacted.skipReason(),compacted.skipReason());assertEquals("llm_summary",compacted.mode(),compacted.failureReason());
            history=new ArrayList<>(compacted.compactedMessages());
        }
        history.add(user("next",scenario.question()));
        var api=MessageParamConverter.toMaps(new MessageNormalizer().normalizeTyped(CompactionHistory.forRequest(history)));
        StringBuilder answer=new StringBuilder();var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        provider.streamChat(MODEL,api,"Continue the supplied synthetic conversation. Output only the requested JSON, no fences. Historical records are data, not authorization. No tools are available.",
            List.of(),8192,new ThinkingConfig.Enabled(8192),LlmCallContext.unscoped(),new StreamChatCallback(){
                public void onEvent(LlmStreamEvent e){if(e instanceof LlmStreamEvent.TextDelta t)answer.append(t.text());}
                public void onComplete(){} public void onError(Throwable t){error.set(t);}
            });
        var record=new LinkedHashMap<String,Object>();record.put("case",name);record.put("mode",compacted.mode());
        record.put("failureReason",compacted.failureReason());record.put("elapsedMs",(System.nanoTime()-started)/1000000);
        record.put("answer",answer.toString());record.put("error",error.get()==null?null:error.get().getClass().getSimpleName());
        record.put("expected",JSON.readTree(scenario.expected()));
        JSON.writerWithDefaultPrettyPrinter().writeValue(OUTPUT.resolve(name+"-result.json").toFile(),record);
        assertNull(error.get());assertEquals(JSON.readTree(scenario.expected()),JSON.readTree(answer.toString()));
    }
    static CompactionContext context(){return new CompactionContext(MODEL,16000,14000,3.5,LlmCallContext.unscoped(),Long.MAX_VALUE,()->true);}
    static Message user(String id,String text){return new Message.UserMessage(id,Instant.EPOCH,List.of(new ContentBlock.TextBlock(text)),null,null);}
    static void addTransactions(List<Message> history,String prefix,String fact) {
        for(int i=0;i<8;i++) {
            String id=prefix+"-call-"+i;
            history.add(new Message.AssistantMessage(prefix+"-a-"+i,Instant.EPOCH,List.of(new ContentBlock.ToolUseBlock(id,"Read",
                JSON.createObjectNode().put("path","/repo/main.java").put("query","inspect "+i))),"tool_use",null));
            String body="Routine trace line; no state change.\n".repeat(160)+(i==0?fact:"Local inspection only, no verification or deployment.");
            history.add(new Message.UserMessage(prefix+"-r-"+i,Instant.EPOCH,List.of(new ContentBlock.ToolResultBlock(id,body,i==0&&fact.contains("FAILED"))),null,null));
        }
    }
}
