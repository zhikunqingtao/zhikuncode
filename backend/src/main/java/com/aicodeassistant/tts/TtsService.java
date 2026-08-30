package com.aicodeassistant.tts;

import com.aicodeassistant.llm.LlmProvidersProperties;
import com.aicodeassistant.llm.LlmProvidersProperties.ProviderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * TTS（文字转语音）服务 — 通过 DashScope qwen3-tts-flash 模型实现文字转语音。
 * <p>
 * 直接调用 DashScope 多模态生成 API（非 OpenAI 兼容端点）。TTS 模型不在 Token Plan 白名单中，
 * 故仅支持标准 dashscope provider。合成结果为一个 WAV 音频文件 URL（有效期 24 小时）。
 */
@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    /** DashScope 多模态生成端点（TTS 不走 OpenAI 兼容端点，故此处硬编码） */
    private static final String TTS_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

    /** 单次合成的最大文本长度，超出部分被截断 */
    private static final int MAX_TEXT_LENGTH = 500;

    private final LlmProvidersProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${tts.model:qwen3-tts-flash}")
    private String ttsModel;

    @Value("${tts.voice:Cherry}")
    private String ttsVoice;

    public TtsService(LlmProvidersProperties llmProperties, ObjectMapper objectMapper) {
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * 检查 TTS 服务是否可用（标准 dashscope provider 配置了有效的 apiKey）。
     * <p>
     * TTS 模型不在 Token Plan 白名单中，故不检查 dashscope-token-plan。
     */
    public boolean isAvailable() {
        Map<String, ProviderConfig> providers = llmProperties.providers();
        if (providers == null) {
            return false;
        }
        ProviderConfig dashscope = providers.get("dashscope");
        return dashscope != null && hasApiKey(dashscope);
    }

    /**
     * 将文本合成为语音，返回音频文件 URL。
     *
     * @param text 待合成的文本，超过 500 字符会被截断
     * @return 音频文件（WAV）的 URL，有效期 24 小时
     * @throws IllegalArgumentException 文本为空
     * @throws RuntimeException         API 调用失败
     */
    public String synthesize(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("文本不能为空");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            log.debug("TTS 文本超过 {} 字符，已截断", MAX_TEXT_LENGTH);
            text = text.substring(0, MAX_TEXT_LENGTH);
        }

        // 按优先级选择 provider
        ProviderConfig provider = resolveProvider();

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", ttsModel,
                    "input", Map.of(
                            "text", text,
                            "voice", ttsVoice,
                            "language_type", "Auto"
                    )
            );

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TTS_ENDPOINT))
                    .header("Authorization", "Bearer " + provider.apiKey())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            log.debug("发送 TTS 请求到 {}，model={}, voice={}", TTS_ENDPOINT, ttsModel, ttsVoice);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("TTS API 返回错误状态 {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("TTS API 调用失败，状态码: " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode urlNode = root.path("output").path("audio").path("url");
            if (urlNode.isMissingNode() || urlNode.isNull() || urlNode.asText().isBlank()) {
                throw new RuntimeException("TTS API 响应中缺少 output.audio.url");
            }

            return urlNode.asText();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("TTS 合成失败", e);
            throw new RuntimeException("TTS 合成失败: " + e.getMessage(), e);
        }
    }

    private ProviderConfig resolveProvider() {
        Map<String, ProviderConfig> providers = llmProperties.providers();
        // TTS 模型不在 Token Plan 白名单中，仅标准 dashscope provider 可用
        ProviderConfig dashscope = providers.get("dashscope");
        if (dashscope != null && hasApiKey(dashscope)) {
            return dashscope;
        }
        throw new IllegalStateException("没有可用的 DashScope provider（未配置 apiKey）");
    }

    private boolean hasApiKey(ProviderConfig config) {
        return config.apiKey() != null && !config.apiKey().isBlank();
    }
}
