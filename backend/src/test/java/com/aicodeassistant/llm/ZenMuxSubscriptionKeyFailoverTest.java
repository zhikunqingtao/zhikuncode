package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ZenMux 订阅 key（sk-ss-v1-）优先 + 按量 key（sk-ai-v1-）兜底的自动切换测试。
 * <p>
 * 通过 MockWebServer 模拟 ZenMux /responses 通道：
 * 订阅配额耗尽（402 quote_exceeded）或模型不在订阅计划（404 model_not_available）
 * 时，实际使用的 key 被长冷却（15 分钟），后续请求自动切换到下一把 key。
 */
class ZenMuxSubscriptionKeyFailoverTest {

    private static final String SUBSCRIPTION_KEY = "sk-ss-v1-subscription-key";
    private static final String PAYG_KEY = "sk-ai-v1-payg-key";
    private static final String MODEL = "openai/gpt-5.6-sol";

    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;
    private OpenAiCompatibleProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        // 与 MultiProviderConfiguration 相同的构造方式：逗号拆分多 key + 首 key 作 fallback
        List<String> apiKeys = MultiProviderConfiguration.splitApiKeys(
                SUBSCRIPTION_KEY + "," + PAYG_KEY);
        provider = new OpenAiCompatibleProvider(
                "zenmux", mapper,
                new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2, 30),
                        10, 10, false),
                new ApiKeyRotationManager(apiKeys),
                apiKeys.isEmpty() ? null : apiKeys.getFirst(),
                server.url("/v1").toString(), MODEL,
                List.of(MODEL));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void quoteExceededCoolsSubscriptionKeyAndNextRequestsUsePaygKey() throws Exception {
        server.enqueue(errorResponse(402, "quote_exceeded",
                "subscription quota exhausted"));
        enqueueCompleted();
        enqueueCompleted();

        Capture first = run();
        assertThat(first.completed).isFalse();
        LlmApiException error = assertInstanceOf(LlmApiException.class, first.error);
        assertThat(error.isRetryable()).isFalse();
        assertThat(error.getHttpStatus()).isEqualTo(402);
        assertThat(error.getMessage()).isEqualTo("subscription quota exhausted");

        // 402 后两次请求均应成功，且都走按量 key（订阅 key 已被冷却）
        assertThat(run().completed).isTrue();
        assertThat(run().completed).isTrue();

        // 第三次仍是 payg（而非 round-robin 回到 sub）证明订阅 key 确实被冷却
        assertThat(authorizationOfRequest(0)).isEqualTo("Bearer " + SUBSCRIPTION_KEY);
        assertThat(authorizationOfRequest(1)).isEqualTo("Bearer " + PAYG_KEY);
        assertThat(authorizationOfRequest(2)).isEqualTo("Bearer " + PAYG_KEY);
    }

    @Test
    void modelNotAvailableCoolsSubscriptionKeyAndNextRequestsUsePaygKey() throws Exception {
        server.enqueue(errorResponse(404, "model_not_available",
                "model not included in subscription plan"));
        enqueueCompleted();
        enqueueCompleted();

        Capture first = run();
        assertThat(first.completed).isFalse();
        LlmApiException error = assertInstanceOf(LlmApiException.class, first.error);
        assertThat(error.isRetryable()).isFalse();
        assertThat(error.getHttpStatus()).isEqualTo(404);

        assertThat(run().completed).isTrue();
        assertThat(run().completed).isTrue();

        assertThat(authorizationOfRequest(0)).isEqualTo("Bearer " + SUBSCRIPTION_KEY);
        assertThat(authorizationOfRequest(1)).isEqualTo("Bearer " + PAYG_KEY);
        assertThat(authorizationOfRequest(2)).isEqualTo("Bearer " + PAYG_KEY);
    }

    @Test
    void otherQuotaErrorsDoNotCoolDownKeyAndRoundRobinContinues() throws Exception {
        // 402 但 type 非 quote_exceeded（如按量余额不足）→ 不触发订阅冷却，round-robin 正常轮换
        server.enqueue(errorResponse(402, "insufficient_balance", "payg balance low"));
        enqueueCompleted();
        enqueueCompleted();

        assertThat(run().completed).isFalse();
        assertThat(run().completed).isTrue();
        assertThat(run().completed).isTrue();

        // 无冷却：第三次请求 round-robin 回到订阅 key
        assertThat(authorizationOfRequest(0)).isEqualTo("Bearer " + SUBSCRIPTION_KEY);
        assertThat(authorizationOfRequest(1)).isEqualTo("Bearer " + PAYG_KEY);
        assertThat(authorizationOfRequest(2)).isEqualTo("Bearer " + SUBSCRIPTION_KEY);
    }

    @Test
    void singleKeyConfigKeepsUsingSameKeyAfterSubscriptionError() throws Exception {
        OpenAiCompatibleProvider singleKeyProvider = new OpenAiCompatibleProvider(
                "zenmux", mapper,
                new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2, 30),
                        10, 10, false),
                new ApiKeyRotationManager(SUBSCRIPTION_KEY), SUBSCRIPTION_KEY,
                server.url("/v1").toString(), MODEL,
                List.of(MODEL));

        server.enqueue(errorResponse(402, "quote_exceeded", "subscription quota exhausted"));
        server.enqueue(errorResponse(402, "quote_exceeded", "subscription quota exhausted"));

        assertThat(runWith(singleKeyProvider).completed).isFalse();
        assertThat(runWith(singleKeyProvider).completed).isFalse();

        // 单 key：402 不进入冷却死循环，两次请求仍使用同一 key 且行为一致
        assertThat(authorizationOfRequest(0)).isEqualTo("Bearer " + SUBSCRIPTION_KEY);
        assertThat(authorizationOfRequest(1)).isEqualTo("Bearer " + SUBSCRIPTION_KEY);
    }

    @Test
    void rateLimitCoolsUsedKeyAndNextRequestUsesPaygKey() throws Exception {
        // 429（Retry-After 冷却）：冷却的必须是实际使用的订阅 key，而非健康的下一把 key
        server.enqueue(new MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "60")
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"type\":\"rate_limit_exceeded\",\"message\":\"too many requests\"}}"));
        enqueueCompleted();

        Capture first = run();
        assertThat(first.completed).isFalse();
        assertThat(assertInstanceOf(LlmApiException.class, first.error).getHttpStatus())
                .isEqualTo(429);

        assertThat(run().completed).isTrue();

        // 第二次请求切到按量 key：若误冷却健康 key，第二次请求会继续用订阅 key
        assertThat(authorizationOfRequest(0)).isEqualTo("Bearer " + SUBSCRIPTION_KEY);
        assertThat(authorizationOfRequest(1)).isEqualTo("Bearer " + PAYG_KEY);
    }

    @Test
    void chatSyncSubscriptionErrorCoolsKeyAndNextCallUsesPaygKey() throws Exception {
        server.enqueue(errorResponse(402, "quote_exceeded", "subscription quota exhausted"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]})"));

        // chatSync 同步路径：402 报错不可重试，且冷却实际使用的订阅 key
        LlmApiException error = assertThrows(LlmApiException.class,
                () -> provider.chatSync(MODEL, "system", "hello", 64, null, 5000));
        assertThat(error.getHttpStatus()).isEqualTo(402);
        assertThat(error.isRetryable()).isFalse();
        assertThat(error.getMessage()).contains("402");

        // 下一次 chatSync 调用自动切到按量 key 并成功
        assertThat(provider.chatSync(MODEL, "system", "hello", 64, null, 5000))
                .isEqualTo("ok");

        RecordedRequest first = server.takeRequest();
        RecordedRequest second = server.takeRequest();
        assertThat(first.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(second.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(first.getHeader("Authorization")).isEqualTo("Bearer " + SUBSCRIPTION_KEY);
        assertThat(second.getHeader("Authorization")).isEqualTo("Bearer " + PAYG_KEY);
    }

    @Test
    void chatSyncRateLimitCoolsKeyAndNextCallUsesPaygKey() throws Exception {
        // chatSync 429 两次重试都失败 → 冷却实际使用的订阅 key → 下一次调用切到按量 key
        server.enqueue(new MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "0")
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"type\":\"rate_limit_exceeded\",\"message\":\"too many requests\"}}"));
        server.enqueue(new MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "0")
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"type\":\"rate_limit_exceeded\",\"message\":\"too many requests\"}}"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]})"));

        // 第一次 chatSync：429 两次重试都失败，抛出异常
        LlmApiException error = assertThrows(LlmApiException.class,
                () -> provider.chatSync(MODEL, "system", "hello", 64, null, 5000));
        assertThat(error.getHttpStatus()).isEqualTo(429);

        // 第二次 chatSync：自动切到按量 key 并成功
        assertThat(provider.chatSync(MODEL, "system", "hello", 64, null, 5000))
                .isEqualTo("ok");

        // 前两次请求（429 重试）用订阅 key，第三次（成功）用按量 key
        RecordedRequest first = server.takeRequest();
        RecordedRequest second = server.takeRequest();
        RecordedRequest third = server.takeRequest();
        assertThat(first.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(second.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(third.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(first.getHeader("Authorization")).isEqualTo("Bearer " + SUBSCRIPTION_KEY);
        assertThat(second.getHeader("Authorization")).isEqualTo("Bearer " + SUBSCRIPTION_KEY);
        assertThat(third.getHeader("Authorization")).isEqualTo("Bearer " + PAYG_KEY);
    }

    private Capture run() {
        return runWith(provider);
    }

    private Capture runWith(OpenAiCompatibleProvider target) {
        Capture capture = new Capture();
        target.streamChat(MODEL, List.of(), "system", List.of(), 4096,
                new ThinkingConfig.Disabled(), LlmCallContext.unscoped(), capture);
        return capture;
    }

    private String authorizationOfRequest(int index) throws InterruptedException {
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/v1/responses");
        return request.getHeader("Authorization");
    }

    private void enqueueCompleted() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"type":"response.completed","response":{"output":[],"usage":{"input_tokens":1,"output_tokens":1}}}

                        """));
    }

    private MockResponse errorResponse(int code, String type, String message) {
        return new MockResponse().setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"code\":\"" + code + "\",\"type\":\"" + type
                        + "\",\"message\":\"" + message + "\"}}");
    }

    private static final class Capture implements StreamChatCallback {
        final List<LlmStreamEvent> events = new ArrayList<>();
        Throwable error;
        boolean completed;
        @Override public void onEvent(LlmStreamEvent event) { events.add(event); }
        @Override public void onComplete() { completed = true; }
        @Override public void onError(Throwable error) { this.error = error; }
    }
}
