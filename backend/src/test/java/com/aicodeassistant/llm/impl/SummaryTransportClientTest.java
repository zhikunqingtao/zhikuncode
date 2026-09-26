package com.aicodeassistant.llm.impl;

import com.aicodeassistant.llm.*;
import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.engine.AbortReason;
import com.fasterxml.jackson.databind.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SummaryTransportClientTest {
    final ObjectMapper json = new ObjectMapper();
    MockWebServer server;
    OpenAiCompatibleProvider provider;
    @BeforeEach void setup() throws Exception {
        server = new MockWebServer(); server.start();
        provider = provider(SummaryTransportClient.BAILIAN_ENDPOINT);
        var client = new OkHttpClient.Builder().addInterceptor(chain -> chain.proceed(
                chain.request().newBuilder().url(server.url("/chat/completions")).build())).build();
        ReflectionTestUtils.setField(provider, "httpClient", client);
    }
    OpenAiCompatibleProvider provider(String name, String url) {
        return provider(name, url, List.of("deepseek-flash", "deepseek-v4.1-flash", "qwen3.8-flash", "qwen3.7-plus"));
    }
    OpenAiCompatibleProvider provider(String name, String url, List<String> models) {
        return new OpenAiCompatibleProvider(name, json,
                new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2,30), 2,2,true),
                new ApiKeyRotationManager("test-only"), "test-only", url, "deepseek-v4.1-flash",
                models);
    }
    OpenAiCompatibleProvider provider(String url) {
        return provider("dashscope-token-plan", url);
    }
    @AfterEach void cleanup() throws Exception { server.shutdown(); }
    SummaryRequest request(String model, String mode, long millis) {
        return new SummaryRequest(model, SummaryRequest.ThinkingMode.valueOf(mode), "summary instructions", "history",
                8192, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis));
    }
    MockResponse success(String suffix) {
        return new MockResponse().setBody("{\"id\":\"response-1\",\"model\":\"actual-model\",\"choices\":[{\"message\":{\"content\":\"<summary>body</summary>\"},\"finish_reason\":\"stop\"}]" + suffix + "}");
    }
    @ParameterizedTest @CsvSource({"deepseek-v4.1-flash,MAX", "deepseek-v4.1-flash,LOW", "deepseek-v4.1-flash,OFF",
            "deepseek-flash,MAX", "deepseek-flash,LOW", "deepseek-flash,OFF",
            "qwen3.8-flash,MAX", "qwen3.8-flash,OFF", "qwen3.7-plus,OFF"})
    void exactProfilesAndRawMetadata(String model, String mode) throws Exception {
        server.enqueue(success(",\"usage\":{\"completion_tokens\":12,\"completion_tokens_details\":{\"reasoning_tokens\":null}}"));
        var result = provider.summarize(request(model,mode,5000), LlmCallContext.unscoped());
        assertNull(result.failureReason()); assertEquals("stop",result.finishReason());
        assertEquals("actual-model",result.responseModel()); assertEquals("response-1",result.responseId());
        assertFalse(result.usage().has("prompt_tokens")); assertTrue(result.usage().path("completion_tokens_details").path("reasoning_tokens").isNull());
        JsonNode body = json.readTree(server.takeRequest().getBody().readUtf8());
        assertEquals(8192,body.path("max_completion_tokens").asInt()); assertFalse(body.has("max_tokens"));
        assertFalse(body.path("stream").asBoolean());
        if (model.startsWith("deepseek")) assertEquals(mode.equals("OFF") ? "disabled" : "enabled", body.path("thinking").path("type").asText());
        else assertEquals(!mode.equals("OFF"),body.path("enable_thinking").asBoolean());
        if (mode.equals("OFF")) assertFalse(body.has("reasoning_effort"));
        else assertEquals(mode.toLowerCase(Locale.ROOT),body.path("reasoning_effort").asText());
    }
    @ParameterizedTest @CsvSource({"MAX", "LOW", "OFF"})
    void deepseekProviderSummarizesAgainstOfficialEndpoint(String mode) throws Exception {
        var capturedUrl = new java.util.concurrent.atomic.AtomicReference<String>();
        var deepseekProvider = provider("deepseek", SummaryTransportClient.DEEPSEEK_ENDPOINT);
        var deepseekClient = new OkHttpClient.Builder().addInterceptor(chain -> {
            capturedUrl.set(chain.request().url().toString());
            return chain.proceed(chain.request().newBuilder().url(server.url("/chat/completions")).build());
        }).build();
        ReflectionTestUtils.setField(deepseekProvider, "httpClient", deepseekClient);
        assertTrue(deepseekProvider.supportsSummary("deepseek-flash", SummaryRequest.ThinkingMode.valueOf(mode)));
        server.enqueue(success(""));
        var result = deepseekProvider.summarize(request("deepseek-flash",mode,5000), LlmCallContext.unscoped());
        assertNull(result.failureReason());
        assertEquals("stop", result.finishReason());
        assertTrue(result.content() != null && result.content().contains("<summary>body</summary>"));
        assertEquals("https://api.deepseek.com/v1/chat/completions", capturedUrl.get());
        JsonNode body = json.readTree(server.takeRequest().getBody().readUtf8());
        assertEquals("deepseek-flash", body.path("model").asText());
        assertEquals(8192, body.path("max_tokens").asInt());
        assertFalse(body.has("max_completion_tokens"));
        if (mode.equals("OFF")) {
            assertEquals("disabled", body.path("thinking").path("type").asText());
            assertFalse(body.has("reasoning_effort"));
        } else {
            assertEquals("enabled", body.path("thinking").path("type").asText());
            assertEquals(mode.toLowerCase(Locale.ROOT), body.path("reasoning_effort").asText());
        }
        assertFalse(body.path("stream").asBoolean(true));
    }
    @Test void providerModelListIsolationRejectsWhitelistedModelAbsentFromProvider() {
        var limited = provider("deepseek", SummaryTransportClient.DEEPSEEK_ENDPOINT, List.of("deepseek-flash"));
        var client = new OkHttpClient.Builder().addInterceptor(chain -> chain.proceed(
                chain.request().newBuilder().url(server.url("/chat/completions")).build())).build();
        ReflectionTestUtils.setField(limited, "httpClient", client);
        assertFalse(limited.supportsSummary("qwen3.8-flash", SummaryRequest.ThinkingMode.MAX));
        assertEquals("unsupported_summary",
                limited.summarize(request("qwen3.8-flash","MAX",5000), LlmCallContext.unscoped()).failureReason());
        assertEquals(0, server.getRequestCount());
    }
    @Test void finalHttpContainsSummaryOnceAndCanonicalLegacyIdOnEveryBuild() throws Exception {
        var now=java.time.Instant.EPOCH;
        var source=List.<com.aicodeassistant.model.Message>of(
            new com.aicodeassistant.model.Message.SystemMessage("summary-source",now,"UNIQUE_SUMMARY_FACT",com.aicodeassistant.model.SystemMessageType.COMPACT_SUMMARY),
            new com.aicodeassistant.model.Message.AssistantMessage("assistant-uuid",now,List.of(
                new com.aicodeassistant.model.ContentBlock.ToolUseBlock("actual-call", "Read",json.createObjectNode().put("path","/repo/file"))),"tool_use",null),
            new com.aicodeassistant.model.Message.UserMessage("legacy-result",now,List.of(),"actual result","assistant-uuid"),
            new com.aicodeassistant.model.Message.UserMessage("latest",now,List.of(new com.aicodeassistant.model.ContentBlock.TextBlock("LATEST_REQUIREMENT")),null,null));
        String fingerprint=com.aicodeassistant.engine.CompactionHistory.fingerprint(source);
        for (int i=0;i<2;i++) {
            var normalized=new com.aicodeassistant.engine.MessageNormalizer().normalizeTyped(com.aicodeassistant.engine.CompactionHistory.forRequest(source));
            server.enqueue(new MockResponse().setHeader("Content-Type","text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"));
            var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            provider.streamChat("deepseek-v4.1-flash",MessageParamConverter.toMaps(normalized),"fixed system",List.of(),1024,
                new ThinkingConfig.Disabled(),LlmCallContext.unscoped(),new StreamChatCallback() {
                    public void onEvent(LlmStreamEvent e) {} public void onComplete() {} public void onError(Throwable t) {failure.set(t);}
                });
            assertNull(failure.get());
            String raw=server.takeRequest().getBody().readUtf8();
            JsonNode messages=json.readTree(raw).path("messages");
            assertEquals(1,raw.split("UNIQUE_SUMMARY_FACT",-1).length-1);
            assertTrue(raw.indexOf("UNIQUE_SUMMARY_FACT")<raw.indexOf("LATEST_REQUIREMENT"));
            assertTrue(raw.contains("BEGIN MACHINE HISTORY summary-source"));
            assertTrue(raw.contains("END MACHINE HISTORY summary-source"));
            assertFalse(raw.contains("No result received"));
            boolean found=false;
            for (JsonNode m:messages) if (m.path("role").asText().equals("tool")) {
                assertEquals("actual-call",m.path("tool_call_id").asText());found=true;
            }
            assertTrue(found); assertEquals(fingerprint,com.aicodeassistant.engine.CompactionHistory.fingerprint(source));
        }
    }

    @Test void finishAndUsageAreNeverInvented() {
        for (String reason : List.of("length", "stop", "tool_calls", "content_filter", "")) {
            server.enqueue(new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"partial\"}" +
                    (reason.isEmpty() ? "" : ",\"finish_reason\":\"" + reason + "\"") + "}]}"));
            var r = provider.summarize(request("deepseek-v4.1-flash","MAX",5000), LlmCallContext.unscoped());
            assertNull(r.usage()); assertEquals(reason.isEmpty() ? null : reason, r.finishReason());
        }
    }
    @Test void unsupportedHasNoHttpAndNoSyncBridge() {
        assertEquals("unsupported_summary",provider.summarize(request("qwen3.7-plus","MAX",5000),LlmCallContext.unscoped()).failureReason());
        assertEquals("unsupported_summary",provider("https://example.invalid/v1").summarize(request("deepseek-v4.1-flash","MAX",5000),LlmCallContext.unscoped()).failureReason());
        assertEquals(0,server.getRequestCount());
    }
    @Test void ordinary429GetsOnlyOneRetry() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After","0"));
        server.enqueue(success(""));
        assertNull(provider.summarize(request("deepseek-v4.1-flash","MAX",5000),LlmCallContext.unscoped()).failureReason());
        assertEquals(2,server.getRequestCount());
    }
    @Test void longRetryAfterAndExpiredDeadlineNeverSendEarly() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After","1000"));
        assertEquals("summary_retry_after_deadline",provider.summarize(request("deepseek-v4.1-flash","MAX",5000),LlmCallContext.unscoped()).failureReason());
        assertEquals("summary_timeout",provider.summarize(request("deepseek-v4.1-flash","MAX",-1),LlmCallContext.unscoped()).failureReason());
        assertEquals(1,server.getRequestCount());
        assertEquals(1000,SummaryTransportClient.retryAfterMillis(null));
        assertEquals(1000,SummaryTransportClient.retryAfterMillis("invalid"));
    }
    @Test void summaryTimeoutDoesNotCancelParent() {
        server.enqueue(success("").setBodyDelay(500,TimeUnit.MILLISECONDS));
        var signal = new AbortContext();
        assertEquals("summary_timeout",provider.summarize(request("deepseek-v4.1-flash","MAX",100),new LlmCallContext("timeout",signal)).failureReason());
        assertFalse(signal.isCancelled());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void cancellationDuringHttpAndBackoffStopsOnlyThisCall(boolean backoff) throws Exception {
            server.enqueue(backoff ? new MockResponse().setResponseCode(429).setHeader("Retry-After","5")
                    : success("").setBodyDelay(1,TimeUnit.SECONDS));
            var signal = new AbortContext();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var task = executor.submit(() -> provider.summarize(request("deepseek-v4.1-flash","MAX",15000),new LlmCallContext("cancel-"+backoff,signal)));
                assertNotNull(server.takeRequest(2,TimeUnit.SECONDS));
                if (backoff) Thread.sleep(100);
                signal.abort(AbortReason.USER_INTERRUPT);
                var error = assertThrows(ExecutionException.class, () -> task.get(2,TimeUnit.SECONDS));
                assertInstanceOf(CancellationException.class,error.getCause());
            }
        assertEquals(1,server.getRequestCount());
    }
}
