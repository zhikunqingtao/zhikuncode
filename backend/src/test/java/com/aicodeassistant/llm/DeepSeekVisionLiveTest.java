package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 DeepSeek 视觉调用；默认禁用，避免常规测试产生费用。 */
class DeepSeekVisionLiveTest {

    private static final String MODEL = "deepseek-v4-flash-vision-exp";

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_VISION_LIVE_TEST", matches = "true")
    void recognizesAuthorizedLocalImageThroughProvider() throws Exception {
        String apiKey = System.getenv("LLM_PROVIDER_DEEPSEEK_API_KEY");
        String imagePath = System.getenv("DEEPSEEK_VISION_TEST_IMAGE_PATH");
        String imageUrl = System.getenv("DEEPSEEK_VISION_TEST_IMAGE_URL");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "DeepSeek API key is required");
        Assumptions.assumeTrue((imageUrl != null && !imageUrl.isBlank())
                        || (imagePath != null && !imagePath.isBlank()),
                "Test image URL or path is required");

        Map<String, Object> imageSource;
        if (imageUrl != null && !imageUrl.isBlank()) {
            imageSource = Map.of("type", "url", "url", imageUrl);
        } else {
            Path path = Path.of(imagePath);
            Assumptions.assumeTrue(Files.isRegularFile(path), "Test image must exist");
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(path));
            String mediaType = Files.probeContentType(path);
            if (mediaType == null) mediaType = "image/png";
            imageSource = Map.of(
                    "type", "base64", "media_type", mediaType, "data", base64);
        }

        LlmHttpProperties http = new LlmHttpProperties(
                new LlmHttpProperties.PoolProperties(2, 30), 15, 30, true);
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                "deepseek-live", new ObjectMapper(), http, new ApiKeyRotationManager(apiKey),
                apiKey, "https://api.deepseek.com/v1", MODEL, List.of(MODEL));

        Map<String, Object> message = Map.of(
                "role", "user",
                "content", List.of(
                        Map.of("type", "text", "text", "请用一句中文准确描述图片中的主要景物。"),
                        Map.of("type", "image", "source", imageSource)));
        StringBuilder response = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();

        provider.streamChat(
                MODEL, List.of(message), "", List.of(), 512,
                new ThinkingConfig.Enabled(10_000), LlmCallContext.unscoped(),
                new StreamChatCallback() {
                    @Override public void onEvent(LlmStreamEvent event) {
                        if (event instanceof LlmStreamEvent.TextDelta text) response.append(text.text());
                    }
                    @Override public void onComplete() {}
                    @Override public void onError(Throwable throwable) { error.set(throwable); }
                });

        assertNull(error.get(), () -> "DeepSeek vision request failed: " + error.get());
        assertTrue(!response.isEmpty(), "DeepSeek vision response must not be empty");
        assertTrue(response.indexOf("荷") >= 0 || response.indexOf("雷峰") >= 0
                        || response.indexOf("塔") >= 0,
                () -> "Response did not identify the main scene: " + response);
        System.out.println("DeepSeek vision live response: " + response);
    }
}
