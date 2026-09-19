package com.aicodeassistant.asr;

import com.aicodeassistant.llm.LlmProvidersProperties;
import com.aicodeassistant.llm.LlmProvidersProperties.ProviderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * ASR（自动语音识别）服务 — 通过 DashScope qwen3-asr-flash 模型实现语音转文字。
 * <p>
 * 使用 OpenAI 兼容协议调用 DashScope ASR API。ASR 模型不在 Token Plan 白名单中（实测返回 model_not_found），
 * 故仅支持标准 dashscope provider。
 * <p>
 * 对话上下文注入：{@link #recognize(byte[], String, String)} 接受可选的 context 文本（最近对话的
 * query+回复），非空时在请求 messages 列表【首位】插入一条 {"role":"system","content":context}
 * 消息作为识别上下文 —— qwen3-asr-flash 的词表匹配机制会使上下文中出现过的词识别更准，
 * 用于提高专有名词识别准确率（依据：阿里云 Qwen-ASR API 参考文档中 qwen3-asr-flash 的
 * chat/completions 请求对 system 上下文消息的支持）。
 */
@Service
public class AsrService {

    private static final Logger log = LoggerFactory.getLogger(AsrService.class);

    /** base64 编码后最大允许大小：约 10MB（DashScope 限制） */
    private static final long MAX_BASE64_SIZE = 10 * 1024 * 1024;

    /** 原始音频数据最大大小：约 7.5MB（base64 编码膨胀约 33%） */
    private static final long MAX_AUDIO_SIZE = (long) (MAX_BASE64_SIZE * 0.75);

    private static final String ASR_MODEL = "qwen3-asr-flash";

    private final LlmProvidersProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AsrService(LlmProvidersProperties llmProperties, ObjectMapper objectMapper) {
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * 检查 ASR 服务是否可用（标准 dashscope provider 配置了有效的 apiKey）。
     * <p>
     * ASR 模型不在 Token Plan 白名单中，故不检查 dashscope-token-plan。
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
     * 识别音频数据，返回转录文本。
     *
     * @param audioData 原始音频字节数组
     * @param mimeType  MIME 类型（如 audio/webm）
     * @param context   识别上下文（最近对话文本），可为 null/空；非空时作为 system 消息置于请求 messages 首位
     * @return 转录后的文本
     * @throws IllegalArgumentException 音频过大或参数无效
     * @throws RuntimeException         API 调用失败
     */
    public String recognize(byte[] audioData, String mimeType, String context) {
        if (audioData == null || audioData.length == 0) {
            throw new IllegalArgumentException("音频数据不能为空");
        }
        if (audioData.length > MAX_AUDIO_SIZE) {
            throw new IllegalArgumentException(
                    "音频数据过大（" + (audioData.length / 1024 / 1024) + "MB），最大允许 " + (MAX_AUDIO_SIZE / 1024 / 1024) + "MB");
        }
        if (mimeType == null || !mimeType.startsWith("audio/")) {
            throw new IllegalArgumentException("无效的 MIME 类型: " + mimeType);
        }

        String base64Audio = Base64.getEncoder().encodeToString(audioData);
        String dataUri = "data:" + mimeType + ";base64," + base64Audio;

        // 按优先级选择 provider
        ProviderConfig provider = resolveProvider();
        String baseUrl = provider.baseUrl().endsWith("/")
                ? provider.baseUrl().substring(0, provider.baseUrl().length() - 1)
                : provider.baseUrl();
        String url = baseUrl + "/chat/completions";

        try {
            // 构造 messages：上下文非空时首位插入 system 消息（词表匹配机制，
            // 上下文中出现过的词识别更准），其后为携带 input_audio 的 user 消息。
            // 注意：system 的 content 必须是数组格式 [{"type":"text","text":...}]；
            // 纯字符串 content 会被阿里云拒绝（400 InvalidParameter，实测验证）。
            List<Map<String, Object>> messages = new ArrayList<>();
            if (context != null && !context.isBlank()) {
                messages.add(Map.of(
                        "role", "system",
                        "content", List.of(Map.of("type", "text", "text", context))
                ));
                log.debug("ASR 请求携带上下文，长度 {} 字符", context.length());
            }
            messages.add(Map.of(
                    "role", "user",
                    "content", List.of(Map.of(
                            "type", "input_audio",
                            "input_audio", Map.of("data", dataUri)
                    ))
            ));

            Map<String, Object> requestBody = Map.of(
                    "model", ASR_MODEL,
                    "messages", messages,
                    "stream", false,
                    "asr_options", Map.of("enable_itn", true)
            );

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + provider.apiKey())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            log.debug("发送 ASR 请求到 {}", url);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("ASR API 返回错误状态 {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("ASR API 调用失败，状态码: " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new RuntimeException("ASR API 响应中缺少 choices[0].message.content");
            }

            return content.asText();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("ASR 识别失败", e);
            throw new RuntimeException("ASR 识别失败: " + e.getMessage(), e);
        }
    }

    private ProviderConfig resolveProvider() {
        Map<String, ProviderConfig> providers = llmProperties.providers();
        // ASR 模型不在 Token Plan 白名单中，仅标准 dashscope provider 可用
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
