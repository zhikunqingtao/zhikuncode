package com.aicodeassistant.llm;

import com.aicodeassistant.engine.TokenBudgetGuard;
import com.aicodeassistant.llm.impl.AnthropicProvider;
import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-02 收尾验收 — wire 级出站图片核对（真实 provider 类 + 本地 HTTP stub，无 API 凭据）。
 * <p>
 * 覆盖另外两条真实序列化路径（OpenAI chat 路径见 {@code UserImageRequestIntegrationTest}）：
 * <ul>
 *   <li>Anthropic Messages：{@code content[].type=image} + {@code source.type=base64}</li>
 *   <li>OpenAI Responses（ZenMux）：{@code input[].content[].type=input_image} 的 data URI</li>
 * </ul>
 * 出站图片字节必须与「最终实际保留」的图片完全一致（经真实 enforcePhase2 身份管线后取回），
 * 且内部身份映射（源哈希、injectedSourceToPayloadHashes/retainedSourceImageHashes 等字段名）
 * 不得进入请求体。
 */
class OutboundImageWireIdentityTest {

    private static final int PNG_SIZE = 24;
    /** 与验收用例一致的合成源身份风格：源哈希以可识别前缀标注，便于泄漏断言。 */
    private static final String SOURCE_IDENTITY_PREFIX = "r02-wire-source-";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LlmHttpProperties HTTP = new LlmHttpProperties(
            new LlmHttpProperties.PoolProperties(2, 30), 5, 5, true);

    // ═══════════════ Anthropic Messages 路径 ═══════════════

    @Test
    void anthropicWireSendsRetainedBase64SourceAndKeepsInternalMappingOut() throws Exception {
        byte[] imageBytes = pngBytes(PNG_SIZE);
        String payloadHash = sha256(imageBytes);
        String sourceHash = SOURCE_IDENTITY_PREFIX + payloadHash;
        List<Map<String, Object>> apiMessages = finalRetainedApiMessages(imageBytes, sourceHash, payloadHash);

        try (MockWebServer api = new MockWebServer()) {
            api.start();
            api.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"type\":\"message_start\",\"message\":{\"id\":\"wire-msg\","
                            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n\n"
                            + "data: {\"type\":\"message_stop\"}\n\n"));
            var provider = new AnthropicProvider(MAPPER, HTTP, "fixture-key",
                    api.url("/").toString(), "claude-sonnet-4-6", List.of("claude-sonnet-4-6"),
                    payloadGuard());

            Capture capture = new Capture();
            provider.streamChat("claude-sonnet-4-6", apiMessages, "", List.of(), 8192,
                    new ThinkingConfig.Disabled(), LlmCallContext.unscoped(), capture);

            assertThat(capture.error).isNull();
            assertThat(capture.completed).isTrue();
            assertThat(capture.events).anyMatch(LlmStreamEvent.MessageStart.class::isInstance);

            RecordedRequest request = api.takeRequest(2, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getPath()).isEqualTo("/v1/messages");
            JsonNode body = MAPPER.readTree(request.getBody().readUtf8());

            JsonNode content = body.path("messages").get(0).path("content");
            assertThat(content.get(0).path("type").asText()).isEqualTo("text");
            JsonNode image = content.get(1);
            // wire 形式：Anthropic 原生 base64 source（不是内部 Map 字段结构或裸 data URI）。
            assertThat(image.path("type").asText()).isEqualTo("image");
            assertThat(image.path("source").path("type").asText()).isEqualTo("base64");
            assertThat(image.path("source").path("media_type").asText()).isEqualTo("image/png");
            assertThat(image.has("image_url")).isFalse();
            assertDecodesToExpected(
                    Base64.getDecoder().decode(image.path("source").path("data").asText()), imageBytes);

            assertNoInternalIdentityMapping(body, sourceHash, payloadHash);
        }
    }

    // ═══════════════ OpenAI Responses（ZenMux）路径 ═══════════════

    @Test
    void responsesWireSendsRetainedInputImageAndKeepsInternalMappingOut() throws Exception {
        byte[] imageBytes = pngBytes(PNG_SIZE);
        String payloadHash = sha256(imageBytes);
        String sourceHash = SOURCE_IDENTITY_PREFIX + payloadHash;
        List<Map<String, Object>> apiMessages = finalRetainedApiMessages(imageBytes, sourceHash, payloadHash);

        try (MockWebServer api = new MockWebServer()) {
            api.start();
            api.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"type\":\"response.completed\",\"response\":{\"output\":[],"
                            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}\n\n"));
            var provider = new OpenAiCompatibleProvider("zenmux", MAPPER, HTTP,
                    new ApiKeyRotationManager("fixture-key"), "fixture-key",
                    api.url("/v1").toString(), "openai/gpt-6-astra", List.of("openai/gpt-6-astra"),
                    payloadGuard());

            Capture capture = new Capture();
            provider.streamChat("openai/gpt-6-astra", apiMessages, "", List.of(), 4096,
                    new ThinkingConfig.Adaptive(), LlmCallContext.unscoped(), capture);

            assertThat(capture.error).isNull();
            assertThat(capture.completed).isTrue();

            RecordedRequest request = api.takeRequest(2, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getPath()).isEqualTo("/v1/responses");
            JsonNode body = MAPPER.readTree(request.getBody().readUtf8());

            JsonNode message = body.path("input").get(0);
            assertThat(message.path("type").asText()).isEqualTo("message");
            assertThat(message.path("role").asText()).isEqualTo("user");
            JsonNode parts = message.path("content");
            assertThat(parts.get(0).path("type").asText()).isEqualTo("input_text");
            JsonNode imagePart = parts.get(1);
            // wire 形式：Responses input_image + data URI（不是 Anthropic source 结构）。
            assertThat(imagePart.path("type").asText()).isEqualTo("input_image");
            assertThat(imagePart.path("detail").asText()).isEqualTo("auto");
            assertThat(imagePart.has("source")).isFalse();
            String imageUrl = imagePart.path("image_url").asText();
            assertThat(imageUrl).startsWith("data:image/png;base64,");
            assertDecodesToExpected(
                    Base64.getDecoder().decode(imageUrl.substring(imageUrl.indexOf(',') + 1)), imageBytes);

            assertNoInternalIdentityMapping(body, sourceHash, payloadHash);
        }
    }

    // ═══════════════ 测试设施 ═══════════════

    /**
     * 构造 [text, image] 用户消息并经过真实「最终保留身份」管线（enforcePhase2，
     * 预算充足 → 不降级），返回 provider 实际会序列化的最终 apiMessages。
     */
    private static List<Map<String, Object>> finalRetainedApiMessages(
            byte[] imageBytes, String sourceHash, String payloadHash) {
        var user = new MessageParam.UserParam(List.of(
                new MessageParam.ContentPart.TextPart("describe this image"),
                new MessageParam.ContentPart.ImagePart(
                        "image/png", Base64.getEncoder().encodeToString(imageBytes))));
        List<Map<String, Object>> apiMessages = MessageParamConverter.toMaps(List.of(user));

        TokenBudgetGuard.FinalBudgetResult result = new TokenBudgetGuard().enforcePhase2(
                apiMessages, 100_000, Map.of(sourceHash, payloadHash), Set.of(sourceHash), 3.5);
        assertThat(result.fitsBudget()).isTrue();
        assertThat(result.retainedSourceImageHashes()).containsExactly(sourceHash);
        return result.apiMessages();
    }

    private static FinalProviderPayloadGuard payloadGuard() {
        return new FinalProviderPayloadGuard(
                new ModelRegistry(new LlmProviderRegistry(List.of(), new MockEnvironment())));
    }

    /** 出站字节必须精确还原为保留图片，且仍是可解码的 PNG（核对的是图片内容而非仅字符串）。 */
    private static void assertDecodesToExpected(byte[] decoded, byte[] expected) throws IOException {
        assertThat(decoded).isEqualTo(expected);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(decoded));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(PNG_SIZE);
        assertThat(image.getHeight()).isEqualTo(PNG_SIZE);
    }

    /** 内部身份映射相关字符串（源哈希值 + 映射字段名）不得出现在请求体中。 */
    private static void assertNoInternalIdentityMapping(
            JsonNode body, String sourceHash, String payloadHash) {
        assertThat(body.toString())
                .doesNotContain(sourceHash)
                .doesNotContain(payloadHash)
                .doesNotContain("injectedSourceToPayloadHashes")
                .doesNotContain("retainedSourceImageHashes")
                .doesNotContain("trackedPayloadToSource");
    }

    private static byte[] pngBytes(int size) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(19);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Capture implements StreamChatCallback {
        final List<LlmStreamEvent> events = new ArrayList<>();
        Throwable error;
        boolean completed;

        @Override public void onEvent(LlmStreamEvent event) { events.add(event); }
        @Override public void onComplete() { completed = true; }
        @Override public void onError(Throwable failure) { error = failure; }
    }
}