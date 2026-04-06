package com.aicodeassistant.llm.impl;

import com.aicodeassistant.llm.*;
import com.aicodeassistant.model.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容供应商实现 — 支持所有 OpenAI Chat Completions API 兼容的模型服务。
 * <p>
 * 通过 baseUrl 可配置性，一套代码同时支持:
 * <ul>
 *   <li>OpenAI 官方 (https://api.openai.com/v1)</li>
 *   <li>Ollama 本地模型 (http://localhost:11434/v1)</li>
 *   <li>通义千问 DashScope (https://dashscope.aliyuncs.com/compatible-mode/v1)</li>
 *   <li>其他 OpenAI 兼容 API（DeepSeek、Moonshot、智谱等）</li>
 * </ul>
 * <p>
 * 【架构裁决 #1】使用 StreamChatCallback 回调模式，方法阻塞直到流结束。
 *
 * @see <a href="SPEC §3.1.3.1">OpenAI 兼容供应商实现</a>
 */
@Service
@ConditionalOnProperty(name = "llm.provider", havingValue = "openai", matchIfMissing = true)
public class OpenAiCompatibleProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json");

    private final OkHttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final List<String> supportedModels;
    private final String defaultModel;

    private volatile Call currentCall;

    /** 内置模型能力映射表 — 对齐 SPEC §3.1.3 */
    private static final Map<String, ModelCapabilities> MODEL_CAPABILITIES = Map.ofEntries(
            Map.entry("gpt-4o", new ModelCapabilities("gpt-4o", "GPT-4o", 16384, 128000, true, false, true, true, 0.005, 0.015)),
            Map.entry("gpt-4o-mini", new ModelCapabilities("gpt-4o-mini", "GPT-4o Mini", 16384, 128000, true, false, true, true, 0.00015, 0.0006)),
            Map.entry("gpt-4-turbo", new ModelCapabilities("gpt-4-turbo", "GPT-4 Turbo", 4096, 128000, true, false, true, true, 0.01, 0.03)),
            Map.entry("deepseek-chat", new ModelCapabilities("deepseek-chat", "DeepSeek Chat", 8192, 64000, true, true, false, true, 0.00027, 0.0011)),
            Map.entry("deepseek-reasoner", new ModelCapabilities("deepseek-reasoner", "DeepSeek Reasoner", 8192, 64000, true, true, false, false, 0.00055, 0.0022)),
            Map.entry("qwen-turbo", new ModelCapabilities("qwen-turbo", "Qwen Turbo", 8192, 131072, true, false, false, true, 0.0003, 0.0006))
    );

    public OpenAiCompatibleProvider(
            ObjectMapper objectMapper,
            @Value("${llm.openai.api-key:}") String apiKey,
            @Value("${llm.openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${llm.openai.default-model:gpt-4o}") String defaultModel,
            @Value("${llm.openai.models:gpt-4o,gpt-4o-mini,gpt-4-turbo}") List<String> supportedModels) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.defaultModel = defaultModel;
        this.supportedModels = supportedModels;

        this.httpClient = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(5, 30, java.util.concurrent.TimeUnit.SECONDS))
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ZERO) // SSE 无读超时
                .writeTimeout(Duration.ofSeconds(10))
                .retryOnConnectionFailure(true)
                .build();

        log.info("OpenAI compatible provider initialized: baseUrl={}, models={}", this.baseUrl, supportedModels);
    }

    @Override
    public String getProviderName() { return "openai-compatible"; }

    @Override
    public List<String> getSupportedModels() { return supportedModels; }

    @Override
    public String getDefaultModel() { return defaultModel; }

    @Override
    public ModelCapabilities getModelCapabilities(String model) {
        return MODEL_CAPABILITIES.getOrDefault(model, ModelCapabilities.DEFAULT);
    }

    // ═══════════════════════════════════════════
    // 核心流式调用
    // ═══════════════════════════════════════════

    @Override
    public void streamChat(
            String model,
            List<Map<String, Object>> messages,
            String systemPrompt,
            List<Map<String, Object>> tools,
            int maxTokens,
            ThinkingConfig thinkingConfig,
            StreamChatCallback callback) {

        ObjectNode requestBody = buildOpenAiRequest(model, messages, systemPrompt, tools, maxTokens);

        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), JSON_MEDIA))
                .build();

        this.currentCall = httpClient.newCall(request);

        // 工具调用累积器 — OpenAI 的工具调用通过多个 delta 增量拼接
        Map<Integer, ToolCallAccumulator> toolCallAccumulators = new HashMap<>();

        try (Response response = currentCall.execute()) {
            if (!response.isSuccessful()) {
                handleErrorResponse(response, callback);
                return;
            }

            // 解析速率限制头
            RateLimitInfo rateLimitInfo = RateLimitInfo.fromOpenAiHeaders(response.headers());
            if (rateLimitInfo.isRateLimited()) {
                log.warn("Rate limited: remaining requests={}, tokens={}",
                        rateLimitInfo.remainingRequests(), rateLimitInfo.remainingTokens());
            }

            // 逐行解析 SSE 流
            ResponseBody body = response.body();
            if (body == null) {
                callback.onError(new LlmApiException("Empty response body", true));
                return;
            }

            BufferedSource source = body.source();
            while (!source.exhausted()) {
                String line = source.readUtf8LineStrict();

                if (line.isEmpty()) continue;

                if ("data: [DONE]".equals(line)) {
                    callback.onComplete();
                    return;
                }

                if (line.startsWith("data: ")) {
                    String json = line.substring(6);
                    processChunk(json, toolCallAccumulators, callback);
                }
            }
            // Stream ended without [DONE] — still complete
            callback.onComplete();

        } catch (IOException e) {
            if (currentCall != null && currentCall.isCanceled()) {
                callback.onComplete();
            } else {
                callback.onError(new LlmApiException("OpenAI stream error: " + e.getMessage(), true));
            }
        }
    }

    @Override
    public void abort() {
        Call call = this.currentCall;
        if (call != null) {
            call.cancel();
        }
    }

    // ═══════════════════════════════════════════
    // 请求构建
    // ═══════════════════════════════════════════

    private ObjectNode buildOpenAiRequest(
            String model,
            List<Map<String, Object>> messages,
            String systemPrompt,
            List<Map<String, Object>> tools,
            int maxTokens) {

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);
        root.put("stream", true);
        ObjectNode streamOptions = root.putObject("stream_options");
        streamOptions.put("include_usage", true);

        ArrayNode messagesArray = root.putArray("messages");

        // 1. 系统提示 → system 消息
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sysMsg = messagesArray.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
        }

        // 2. 转换消息列表
        for (Map<String, Object> msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.set("role", objectMapper.valueToTree(msg.get("role")));
            Object content = msg.get("content");
            if (content instanceof String s) {
                msgNode.put("content", s);
            } else {
                msgNode.set("content", objectMapper.valueToTree(content));
            }
        }

        // 3. 工具定义
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = root.putArray("tools");
            for (Map<String, Object> tool : tools) {
                toolsArray.add(objectMapper.valueToTree(tool));
            }
        }

        return root;
    }

    // ═══════════════════════════════════════════
    // SSE chunk 处理
    // ═══════════════════════════════════════════

    private void processChunk(String json,
                              Map<Integer, ToolCallAccumulator> accumulators,
                              StreamChatCallback callback) {
        try {
            JsonNode chunk = objectMapper.readTree(json);
            JsonNode choices = chunk.get("choices");

            if (choices == null || choices.isEmpty()) {
                // usage-only chunk
                if (chunk.has("usage")) {
                    Usage usage = parseUsage(chunk.get("usage"));
                    callback.onEvent(new LlmStreamEvent.MessageDelta(usage, "stop"));
                }
                return;
            }

            JsonNode choice = choices.get(0);
            JsonNode delta = choice.get("delta");
            String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isNull()
                    ? choice.get("finish_reason").asText() : null;

            if (delta != null) {
                // 文本增量
                if (delta.has("content") && !delta.get("content").isNull()) {
                    String text = delta.get("content").asText();
                    if (!text.isEmpty()) {
                        callback.onEvent(new LlmStreamEvent.TextDelta(text));
                    }
                }

                // 工具调用增量
                if (delta.has("tool_calls")) {
                    for (JsonNode tc : delta.get("tool_calls")) {
                        int index = tc.get("index").asInt();
                        ToolCallAccumulator acc = accumulators.computeIfAbsent(
                                index, k -> new ToolCallAccumulator());

                        if (tc.has("id")) {
                            acc.id = tc.get("id").asText();
                        }
                        if (tc.has("function")) {
                            JsonNode fn = tc.get("function");
                            if (fn.has("name")) {
                                acc.name = fn.get("name").asText();
                                callback.onEvent(new LlmStreamEvent.ToolUseStart(acc.id, acc.name));
                            }
                            if (fn.has("arguments")) {
                                String argDelta = fn.get("arguments").asText();
                                acc.arguments.append(argDelta);
                                callback.onEvent(new LlmStreamEvent.ToolInputDelta(acc.id, argDelta));
                            }
                        }
                    }
                }
            }

            // 流结束原因
            if (finishReason != null && chunk.has("usage")) {
                Usage usage = parseUsage(chunk.get("usage"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(usage, finishReason));
            }

        } catch (Exception e) {
            callback.onError(new LlmApiException(
                    "Failed to parse OpenAI chunk: " + e.getMessage(), false));
        }
    }

    // ═══════════════════════════════════════════
    // 错误处理
    // ═══════════════════════════════════════════

    private void handleErrorResponse(Response response, StreamChatCallback callback) {
        int code = response.code();
        boolean retryable = code == 429 || code >= 500;
        String errorMsg;
        try {
            ResponseBody body = response.body();
            if (body != null) {
                JsonNode bodyJson = objectMapper.readTree(body.string());
                errorMsg = bodyJson.path("error").path("message").asText("Unknown error");
            } else {
                errorMsg = "HTTP " + code;
            }
        } catch (Exception e) {
            errorMsg = "HTTP " + code;
        }
        callback.onError(new LlmApiException(errorMsg, retryable, code));
    }

    // ═══════════════════════════════════════════
    // 内部辅助
    // ═══════════════════════════════════════════

    private Usage parseUsage(JsonNode usageNode) {
        return new Usage(
                usageNode.path("prompt_tokens").asInt(0),
                usageNode.path("completion_tokens").asInt(0),
                0, // cache_read — OpenAI 标准 API 无此字段
                0  // cache_write
        );
    }

    /** 工具调用增量累积器 */
    private static class ToolCallAccumulator {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
