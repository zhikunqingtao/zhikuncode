package com.aicodeassistant.engine;

import com.aicodeassistant.config.oss.OssPublishProperties;
import com.aicodeassistant.llm.*;
import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.aicodeassistant.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual download, conversion, both guards, and the provider's final HTTP request. No API credentials. */
class UserImageRequestIntegrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"kimi-k3", "kimi-k2.7-code"})
    void sendsLargePngThroughBothRealGuardsAndKeepsUrlHistory(String model) throws Exception {
        try (var storage = new MockWebServer(); var api = new MockWebServer()) {
            storage.start(); api.start();
            byte[] png = UserImageTranscoderTest.png(640);
            storage.enqueue(new MockResponse().addHeader("Content-Type", "image/png")
                    .setBody(new Buffer().write(png)));
            String url = storage.url("/clipboard/image.png").toString();
            // Only the origin policy is substituted to permit the local HTTP fixture.
            var properties = mock(OssPublishProperties.class);
            when(properties.isTrustedClipboardImageUrl(url)).thenReturn(true);
            var original = UserImageTranscoderTest.message("stored-user-message", url);
            var models = new ModelRegistry(new LlmProviderRegistry(List.of(), new MockEnvironment()));
            assertThat(models.getCapabilities(model).imageInputMode()).isEqualTo(ModelCapabilities.ImageInputMode.BASE64_ONLY);
            var prepared = new UserImageTranscoder(properties).transcode(List.of(original), models.getCapabilities(model));
            var messages = MessageParamConverter.toMaps(new MessageNormalizer().normalizeTyped(prepared.messages()));
            int maxOutput = QueryConfig.getRecommendedMaxTokens(models, model);
            int inputBudget = models.getContextWindowForModel(model) - maxOutput - models.getContextWindowForModel(model) / 20;
            var checked = new TokenBudgetGuard().enforcePhase2(messages, inputBudget, Set.of(), 3.5);
            assertThat(checked.fitsBudget()).isTrue();
            api.enqueue(new MockResponse().addHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"id\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\n" +
                    "data: {\"id\":\"test\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n"));
            var mapper = new ObjectMapper();
            var provider = new OpenAiCompatibleProvider("moonshot-test", mapper,
                    new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2, 30), 5, 5, true),
                    new ApiKeyRotationManager("test"), "test", api.url("/v1").toString(), model, List.of(model),
                    new FinalProviderPayloadGuard(models));
            var failure = new AtomicReference<Throwable>();
            provider.streamChat(model, checked.apiMessages(), "", List.of(), maxOutput, new ThinkingConfig.Disabled(),
                    LlmCallContext.unscoped(), new StreamChatCallback() {
                        public void onEvent(LlmStreamEvent event) {}
                        public void onComplete() {}
                        public void onError(Throwable error) { failure.set(error); }
                    });
            assertThat(failure.get()).isNull();
            var request = api.takeRequest(2, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            var body = mapper.readTree(request.getBody().readUtf8());
            var imageUrl = body.path("messages").get(0).path("content").get(1).path("image_url").path("url").asText();
            assertThat(imageUrl).startsWith("data:image/png;base64,");
            assertThat(Base64.getDecoder().decode(imageUrl.substring(imageUrl.indexOf(',') + 1))).isEqualTo(png);
            assertThat(body.toString()).doesNotContain(url);
            assertThat(((ContentBlock.ImageBlock) original.content().get(1)).url()).isEqualTo(url);
            assertThat(storage.getRequestCount()).isEqualTo(1);
        }
    }
}
