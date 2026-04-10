package com.aicodeassistant.llm.impl;

import com.aicodeassistant.llm.*;
import com.aicodeassistant.model.Usage;
import com.aicodeassistant.prompt.SystemPromptSegment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anthropic 原生 Provider — 直接调用 Anthropic Messages API。
 * <p>
 * 关键差异 vs OpenAI Compatible:
 * - system 消息: 独立 {@code system} 字段（非 messages 数组内）
 * - cache_control: {@code cache_control:{"type":"ephemeral"}} — 费用节省 50-90%
 * - thinking: {@code thinking:{"type":"enabled","budget_tokens":N}}
 * - 流格式: {@code event: content_block_delta\ndata: {...}} (两行序列)
 * <p>
 * 仅在 {@code llm.provider=anthropic} 时激活。
 */
@Service
@ConditionalOnProperty(name = "llm.provider", havingValue = "anthropic")
public class AnthropicProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json");

    private final OkHttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final String defaultModel;
    private final List<String> supportedModels;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Call> activeCalls = new ConcurrentHashMap<>();

    private static final Map<String, ModelCapabilities> MODEL_CAPABILITIES = Map.ofEntries(
            Map.entry("claude-sonnet-4-20250514", new ModelCapabilities(
                    "claude-sonnet-4-20250514", "Claude Sonnet 4", 16384, 200000,
                    true, true, true, true, 0.003, 0.015)),
            Map.entry("claude-3-7-sonnet-20250219", new ModelCapabilities(
                    "claude-3-7-sonnet-20250219", "Claude 3.7 Sonnet", 16384, 200000,
                    true, true, true, true, 0.003, 0.015)),
            Map.entry("claude-3-5-sonnet-20241022", new ModelCapabilities(
                    "claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet", 8192, 200000,
                    true, false, true, true, 0.003, 0.015)),
            Map.entry("claude-3-5-haiku-20241022", new ModelCapabilities(
                    "claude-3-5-haiku-20241022", "Claude 3.5 Haiku", 8192, 200000,
                    true, false, false, true, 0.001, 0.005)),
            Map.entry("claude-3-opus-20240229", new ModelCapabilities(
                    "claude-3-opus-20240229", "Claude 3 Opus", 4096, 200000,
                    true, false, true, true, 0.015, 0.075))
    );

    public AnthropicProvider(
            ObjectMapper objectMapper,
            LlmHttpProperties httpProperties,
            @Value("${llm.anthropic.api-key:}") String apiKey,
            @Value("${llm.anthropic.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${llm.anthropic.default-model:claude-sonnet-4-20250514}") String defaultModel,
            @Value("${llm.anthropic.models:claude-sonnet-4-20250514,claude-3-7-sonnet-20250219,claude-3-5-sonnet-20241022}") List<String> supportedModels) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.defaultModel = defaultModel;
        this.supportedModels = supportedModels;

        this.httpClient = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(
                        httpProperties.pool().maxIdleConnections(),
                        httpProperties.pool().keepAliveSeconds(),
                        java.util.concurrent.TimeUnit.SECONDS))
                .connectTimeout(Duration.ofSeconds(httpProperties.connectTimeoutSeconds()))
                .readTimeout(Duration.ofMinutes(5)) // SSE 5分钟读超时（防止连接泄漏）
                .writeTimeout(Duration.ofSeconds(httpProperties.writeTimeoutSeconds()))
                .retryOnConnectionFailure(httpProperties.retryOnFailure())
                .build();

        log.info("Anthropic provider initialized: baseUrl={}, models={}", this.baseUrl, supportedModels);
    }

    // ==================== LlmProvider 接口实现 ====================

    @Override
    public String getProviderName() { return "anthropic"; }

    @Override
    public List<String> getSupportedModels() { return supportedModels; }

    @Override
    public String getDefaultModel() { return defaultModel; }

    @Override
    public String getFastModel() { return "claude-3-5-haiku-20241022"; }

    @Override
    public ModelCapabilities getModelCapabilities(String model) {
        return MODEL_CAPABILITIES.getOrDefault(model,
                new ModelCapabilities(model, model, 8192, 200000,
                        true, false, true, true, 0.003, 0.015));
    }

    @Override
    public boolean supportsCaching() { return true; }

    @Override
    public void abort() {
        activeCalls.values().forEach(Call::cancel);
        activeCalls.clear();
    }

    /**
     * 按 session 中断流式请求。
     */
    public void abortStream(String sessionId) {
        Call call = activeCalls.remove(sessionId);
        if (call != null) call.cancel();
    }

    // ==================== 流式调用 (Map 格式, 兼容旧接口) ====================

    @Override
    @SuppressWarnings("deprecation")
    public void streamChat(
            String model,
            List<Map<String, Object>> messages,
            String systemPrompt,
            List<Map<String, Object>> tools,
            int maxTokens,
            ThinkingConfig thinkingConfig,
            StreamChatCallback callback) {

        Map<String, Object> body = buildRequestBody(model, messages, systemPrompt, tools, maxTokens, thinkingConfig);
        executeStreamRequest(body, "stream-" + System.nanoTime(), callback);
    }

    // ==================== 请求构建 ====================

    private Map<String, Object> buildRequestBody(
            String model,
            List<Map<String, Object>> messages,
            String systemPrompt,
            List<Map<String, Object>> tools,
            int maxTokens,
            ThinkingConfig thinkingConfig) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("stream", true);

        // system 独立字段 + cache_control
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", List.of(Map.of(
                    "type", "text",
                    "text", systemPrompt,
                    "cache_control", Map.of("type", "ephemeral"))));
        }

        // messages 转换
        body.put("messages", messages);

        // thinking 配置
        if (thinkingConfig instanceof ThinkingConfig.Enabled enabled) {
            int budget = Math.min(maxTokens - 1, enabled.budgetTokens());
            body.put("thinking", Map.of("type", "enabled", "budget_tokens", budget));
            // thinking 启用时不设置 temperature
        } else if (thinkingConfig instanceof ThinkingConfig.Adaptive) {
            body.put("thinking", Map.of("type", "adaptive"));
        } else {
            body.put("temperature", 1.0);
        }

        // 工具定义
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }

        return body;
    }

    // ==================== SSE 流式解析 ====================

    private void executeStreamRequest(Map<String, Object> body, String sessionId,
                                       StreamChatCallback callback) {
        try {
            byte[] bodyBytes = objectMapper.writeValueAsBytes(body);
            Request request = new Request.Builder()
                    .url(baseUrl + "/v1/messages")
                    .post(RequestBody.create(bodyBytes, JSON_MEDIA))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .build();

            Call call = httpClient.newCall(request);
            activeCalls.put(sessionId, call);

            try (Response response = call.execute()) {
                if (!response.isSuccessful()) {
                    String respBody = response.body() != null ? response.body().string() : "";
                    int code = response.code();
                    boolean retryable = code == 429 || code == 529 || code == 503;
                    throw new LlmApiException(respBody, retryable, code);
                }

                parseAnthropicSSE(response, callback);
                callback.onComplete();
            } catch (IOException e) {
                if (call.isCanceled()) {
                    callback.onComplete(); // abort
                } else {
                    callback.onError(e);
                }
            } finally {
                activeCalls.remove(sessionId);
            }
        } catch (Exception e) {
            if (e instanceof LlmApiException) {
                callback.onError(e);
            } else {
                callback.onError(new LlmApiException(e.getMessage(), e, false));
            }
        }
    }

    /**
     * 解析 Anthropic SSE 流。
     * Anthropic SSE 格式: event: <type>\ndata: {json}\n\n
     */
    private void parseAnthropicSSE(Response response, StreamChatCallback callback) throws IOException {
        BufferedSource source = response.body().source();
        String pendingEventType = null;
        int inputTokens = 0;
        int outputTokens = 0;
        int cacheReadTokens = 0;
        int cacheCreationTokens = 0;

        while (!source.exhausted()) {
            String line = source.readUtf8Line();
            if (line == null || line.isEmpty()) {
                pendingEventType = null;
                continue;
            }

            if (line.startsWith("event: ")) {
                pendingEventType = line.substring(7).trim();
                continue;
            }

            if (!line.startsWith("data: ")) continue;
            String json = line.substring(6);

            // Handle error event
            if ("error".equals(pendingEventType)) {
                JsonNode errorEvent = objectMapper.readTree(json);
                String errorType = errorEvent.path("error").path("type").asText("unknown");
                String errorMsg = errorEvent.path("error").path("message").asText();
                boolean retryable = "overloaded_error".equals(errorType)
                        || "rate_limit_error".equals(errorType);
                callback.onError(new LlmApiException(errorMsg, retryable, 0));
                return;
            }

            JsonNode event = objectMapper.readTree(json);
            String eventType = event.has("type") ? event.get("type").asText()
                    : (pendingEventType != null ? pendingEventType : "unknown");

            switch (eventType) {
                case "message_start" -> {
                    callback.onEvent(new LlmStreamEvent.MessageStart(
                            event.at("/message/id").asText()));
                    // Extract initial usage
                    JsonNode msgUsage = event.at("/message/usage");
                    if (msgUsage != null && !msgUsage.isMissingNode()) {
                        inputTokens = msgUsage.path("input_tokens").asInt(0);
                        cacheReadTokens = msgUsage.path("cache_read_input_tokens").asInt(0);
                        cacheCreationTokens = msgUsage.path("cache_creation_input_tokens").asInt(0);
                    }
                }
                case "content_block_start" -> {
                    int blockIndex = event.get("index").asInt();
                    JsonNode block = event.get("content_block");
                    String blockType = block.get("type").asText();
                    switch (blockType) {
                        case "text" -> callback.onEvent(new LlmStreamEvent.TextStart(blockIndex));
                        case "tool_use" -> callback.onEvent(new LlmStreamEvent.ToolUseStart(
                                block.get("id").asText(),
                                block.get("name").asText()));
                        case "thinking" -> callback.onEvent(new LlmStreamEvent.ThinkingStart(blockIndex));
                    }
                }
                case "content_block_delta" -> {
                    JsonNode delta = event.get("delta");
                    String deltaType = delta.get("type").asText();
                    switch (deltaType) {
                        case "text_delta" -> callback.onEvent(
                                new LlmStreamEvent.TextDelta(delta.get("text").asText()));
                        case "input_json_delta" -> callback.onEvent(
                                new LlmStreamEvent.ToolInputDelta(
                                        null, delta.get("partial_json").asText()));
                        case "thinking_delta" -> callback.onEvent(
                                new LlmStreamEvent.ThinkingDelta(delta.get("thinking").asText()));
                    }
                }
                case "content_block_stop" -> {
                    callback.onEvent(new LlmStreamEvent.BlockStop(event.get("index").asInt()));
                }
                case "message_delta" -> {
                    JsonNode delta = event.get("delta");
                    String stopReason = delta.has("stop_reason") && !delta.get("stop_reason").isNull()
                            ? delta.get("stop_reason").asText() : null;
                    JsonNode usage = event.get("usage");
                    if (usage != null && !usage.isMissingNode()) {
                        outputTokens = usage.path("output_tokens").asInt(0);
                    }
                    callback.onEvent(new LlmStreamEvent.MessageDelta(
                            new Usage(inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens),
                            stopReason));
                }
                case "message_stop" -> {
                    // Stream complete — onComplete called by caller
                }
                default -> log.trace("Unknown Anthropic SSE event: {}", eventType);
            }
        }
    }
}
