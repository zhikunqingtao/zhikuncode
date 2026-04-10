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
import java.util.concurrent.ConcurrentHashMap;

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
    private final ApiKeyRotationManager keyRotationManager;

    private final ConcurrentHashMap<String, Call> activeCalls = new ConcurrentHashMap<>();

    /** 内置模型能力映射表 — 对齐 SPEC §3.1.3 */
    private static final Map<String, ModelCapabilities> MODEL_CAPABILITIES = Map.ofEntries(
            // OpenAI 模型
            Map.entry("gpt-4o", new ModelCapabilities("gpt-4o", "GPT-4o", 16384, 128000, true, false, true, true, 0.005, 0.015)),
            Map.entry("gpt-4o-mini", new ModelCapabilities("gpt-4o-mini", "GPT-4o Mini", 16384, 128000, true, false, true, true, 0.00015, 0.0006)),
            Map.entry("gpt-4-turbo", new ModelCapabilities("gpt-4-turbo", "GPT-4 Turbo", 4096, 128000, true, false, true, true, 0.01, 0.03)),
            // DeepSeek 模型
            Map.entry("deepseek-chat", new ModelCapabilities("deepseek-chat", "DeepSeek Chat", 8192, 64000, true, true, false, true, 0.00027, 0.0011)),
            Map.entry("deepseek-reasoner", new ModelCapabilities("deepseek-reasoner", "DeepSeek Reasoner", 8192, 64000, true, true, false, false, 0.00055, 0.0022)),
            // 阿里云百炼 - 通义千问模型
            Map.entry("qwen3.6-plus", new ModelCapabilities("qwen3.6-plus", "通义千问 3.6 Plus", 8192, 131072, true, false, true, true, 0.0008, 0.002)),
            Map.entry("qwen-max", new ModelCapabilities("qwen-max", "通义千问 Max", 8192, 32768, true, false, true, true, 0.0007, 0.002)),
            Map.entry("qwen-plus", new ModelCapabilities("qwen-plus", "通义千问 Plus", 8192, 131072, true, false, true, true, 0.0008, 0.002)),
            Map.entry("qwen-turbo", new ModelCapabilities("qwen-turbo", "通义千问 Turbo", 8192, 131072, true, false, false, true, 0.0003, 0.0006)),
            Map.entry("qwen-coder-plus", new ModelCapabilities("qwen-coder-plus", "通义千问 Coder Plus", 8192, 131072, true, false, false, true, 0.0007, 0.002))
    );

    public OpenAiCompatibleProvider(
            ObjectMapper objectMapper,
            LlmHttpProperties httpProperties,
            ApiKeyRotationManager keyRotationManager,
            @Value("${llm.openai.api-key:}") String apiKey,
            @Value("${llm.openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${llm.openai.default-model:gpt-4o}") String defaultModel,
            @Value("${llm.openai.models:gpt-4o,gpt-4o-mini,gpt-4-turbo}") List<String> supportedModels) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.keyRotationManager = keyRotationManager;
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

        // P1-12: 使用 Key 轮换管理器获取 API Key
        String effectiveApiKey = keyRotationManager.getKeyCount() > 0
                ? keyRotationManager.getNextKey() : apiKey;

        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + effectiveApiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), JSON_MEDIA))
                .build();

        String callId = "openai-" + System.nanoTime();
        Call call = httpClient.newCall(request);
        activeCalls.put(callId, call);

        // 工具调用累积器 — OpenAI 的工具调用通过多个 delta 增量拼接
        Map<Integer, ToolCallAccumulator> toolCallAccumulators = new HashMap<>();

        try (Response response = call.execute()) {
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
            if (call.isCanceled()) {
                callback.onComplete();
            } else {
                callback.onError(new LlmApiException("OpenAI stream error: " + e.getMessage(), true));
            }
        } finally {
            activeCalls.remove(callId);
        }
    }

    @Override
    public void abort() {
        activeCalls.values().forEach(Call::cancel);
        activeCalls.clear();
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

        // 2. 转换消息列表 — Anthropic 内部格式 → OpenAI Chat Completions 格式
        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            Object content = msg.get("content");

            if (content instanceof List<?> blocks) {
                // 检查是否包含 tool_result 块 → 转为 role:"tool" 消息
                boolean hasToolResult = blocks.stream().anyMatch(b ->
                        b instanceof Map<?,?> m && "tool_result".equals(m.get("type")));
                if (hasToolResult) {
                    for (Object block : blocks) {
                        if (block instanceof Map<?,?> b && "tool_result".equals(b.get("type"))) {
                            ObjectNode toolMsg = messagesArray.addObject();
                            toolMsg.put("role", "tool");
                            toolMsg.put("tool_call_id", (String) b.get("tool_use_id"));
                            Object resultContent = b.get("content");
                            toolMsg.put("content", resultContent != null ? resultContent.toString() : "");
                        }
                    }
                    continue;
                }

                // 检查 assistant 消息是否包含 tool_use 块 → 转为 tool_calls 数组
                boolean hasToolUse = blocks.stream().anyMatch(b ->
                        b instanceof Map<?,?> m && "tool_use".equals(m.get("type")));
                if ("assistant".equals(role) && hasToolUse) {
                    ObjectNode msgNode = messagesArray.addObject();
                    msgNode.put("role", "assistant");
                    // 提取文本内容
                    StringBuilder textContent = new StringBuilder();
                    for (Object block : blocks) {
                        if (block instanceof Map<?,?> b && "text".equals(b.get("type"))) {
                            Object text = b.get("text");
                            if (text != null && !text.toString().isEmpty()) {
                                if (!textContent.isEmpty()) textContent.append("\n");
                                textContent.append(text);
                            }
                        }
                    }
                    if (!textContent.isEmpty()) {
                        msgNode.put("content", textContent.toString());
                    } else {
                        msgNode.putNull("content");
                    }
                    // 构建 tool_calls 数组
                    ArrayNode toolCalls = msgNode.putArray("tool_calls");
                    for (Object block : blocks) {
                        if (block instanceof Map<?,?> b && "tool_use".equals(b.get("type"))) {
                            ObjectNode tc = toolCalls.addObject();
                            tc.put("id", (String) b.get("id"));
                            tc.put("type", "function");
                            ObjectNode fn = tc.putObject("function");
                            fn.put("name", (String) b.get("name"));
                            Object input = b.get("input");
                            try {
                                fn.put("arguments", input != null
                                        ? objectMapper.writeValueAsString(input) : "{}");
                            } catch (Exception e) {
                                fn.put("arguments", "{}");
                            }
                        }
                    }
                    continue;
                }

                // 普通 assistant/user 消息: 提取文本内容为字符串
                StringBuilder textContent = new StringBuilder();
                for (Object block : blocks) {
                    if (block instanceof Map<?,?> b && "text".equals(b.get("type"))) {
                        Object text = b.get("text");
                        if (text != null && !text.toString().isEmpty()) {
                            if (!textContent.isEmpty()) textContent.append("\n");
                            textContent.append(text);
                        }
                    }
                }
                ObjectNode msgNode = messagesArray.addObject();
                msgNode.put("role", role != null ? role : "user");
                msgNode.put("content", textContent.toString());
            } else {
                // 纯字符串消息
                ObjectNode msgNode = messagesArray.addObject();
                msgNode.put("role", role != null ? role : "user");
                if (content instanceof String s) {
                    msgNode.put("content", s);
                } else {
                    msgNode.put("content", content != null ? content.toString() : "");
                }
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

    /**
     * 标准化 OpenAI finish_reason 为内部统一格式。
     * <p>
     * OpenAI/Qwen 返回: "stop", "length", "tool_calls", "content_filter"
     * 内部统一:      "end_turn", "max_tokens", "tool_use", "content_filter"
     */
    private static String normalizeFinishReason(String finishReason) {
        if (finishReason == null) return null;
        return switch (finishReason) {
            case "stop" -> "end_turn";
            case "tool_calls" -> "tool_use";
            case "length" -> "max_tokens";
            default -> finishReason;
        };
    }

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
                    callback.onEvent(new LlmStreamEvent.MessageDelta(usage, "end_turn"));
                }
                return;
            }

            JsonNode choice = choices.get(0);
            JsonNode delta = choice.get("delta");
            String rawFinishReason = choice.has("finish_reason") && !choice.get("finish_reason").isNull()
                    ? choice.get("finish_reason").asText() : null;
            String finishReason = normalizeFinishReason(rawFinishReason);

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

            // 流结束原因 — 接受有 usage 或有 finish_reason 的 chunk
            if (finishReason != null) {
                Usage usage = chunk.has("usage")
                        ? parseUsage(chunk.get("usage"))
                        : Usage.zero();
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

        // P1-12: 429 限流时标记 Key 冷却
        if (code == 429 && keyRotationManager.getKeyCount() > 1) {
            String retryAfter = response.header("Retry-After");
            java.time.Duration cooldown = null;
            if (retryAfter != null) {
                try {
                    cooldown = java.time.Duration.ofSeconds(Long.parseLong(retryAfter));
                } catch (NumberFormatException ignored) {}
            }
            keyRotationManager.markRateLimited(keyRotationManager.getCurrentKey(), cooldown);
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
