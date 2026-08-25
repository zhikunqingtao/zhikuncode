package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleProviderVisionTest {

    private static final String MODEL = "deepseek-v4-flash-vision-exp";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OpenAiCompatibleProvider provider;

    @BeforeEach
    void setUp() {
        LlmHttpProperties http = new LlmHttpProperties(
                new LlmHttpProperties.PoolProperties(2, 30), 10, 10, true);
        provider = new OpenAiCompatibleProvider(
                "deepseek-test", objectMapper, http, new ApiKeyRotationManager("key"),
                "key", "https://api.deepseek.com/v1", MODEL, List.of(MODEL));
    }

    @Test
    void visionRequestUsesOpenAiImageUrlAndDisablesThinking() throws Exception {
        Map<String, Object> image = Map.of(
                "type", "image",
                "source", Map.of(
                        "type", "base64",
                        "media_type", "image/png",
                        "data", "iVBORw0KGgo="));
        Map<String, Object> text = Map.of("type", "text", "text", "请描述图片");
        Map<String, Object> userMessage = Map.of(
                "role", "user",
                "content", List.of(text, image));

        Method buildRequest = OpenAiCompatibleProvider.class.getDeclaredMethod(
                "buildOpenAiRequest", String.class, List.class, String.class,
                List.class, int.class, ThinkingConfig.class);
        buildRequest.setAccessible(true);
        JsonNode body = (JsonNode) buildRequest.invoke(
                provider, MODEL, List.of(userMessage), "", List.of(), 512,
                new ThinkingConfig.Enabled(10_000));

        assertEquals(MODEL, body.path("model").asText());
        assertEquals("disabled", body.path("thinking").path("type").asText());
        assertFalse(body.has("reasoning_effort"));

        JsonNode content = body.path("messages").get(0).path("content");
        assertTrue(content.isArray());
        assertEquals("text", content.get(0).path("type").asText());
        assertEquals("image_url", content.get(1).path("type").asText());
        assertEquals("data:image/png;base64,iVBORw0KGgo=",
                content.get(1).path("image_url").path("url").asText());
    }

    @Test
    void visionRequestKeepsServerValidatedRemoteImageUrl() throws Exception {
        String remoteUrl = "https://bucket.oss-cn-beijing.aliyuncs.com/"
                + "zhikuncode-artifacts/clipboard/session/image.png";
        Map<String, Object> image = Map.of(
                "type", "image",
                "source", Map.of("type", "url", "url", remoteUrl));
        Map<String, Object> userMessage = Map.of(
                "role", "user", "content", List.of(image));

        Method buildRequest = OpenAiCompatibleProvider.class.getDeclaredMethod(
                "buildOpenAiRequest", String.class, List.class, String.class,
                List.class, int.class, ThinkingConfig.class);
        buildRequest.setAccessible(true);
        JsonNode body = (JsonNode) buildRequest.invoke(
                provider, MODEL, List.of(userMessage), "", List.of(), 512,
                new ThinkingConfig.Disabled());

        assertEquals(remoteUrl, body.path("messages").get(0).path("content")
                .get(0).path("image_url").path("url").asText());
    }
}
