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
 */
public class OpenAiCompatibleProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json");

    private final OkHttpClient httpClient;
    private final String providerName;
    private final String apiKey;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final List<String> supportedModels;
    private final String defaultModel;
    private final ApiKeyRotationManager keyRotationManager;

    private final ConcurrentHashMap<String, Call> activeCalls = new ConcurrentHashMap<>();

    /** 内置模型能力映射表*/
    private static final Map<String, ModelCapabilities> MODEL_CAPABILITIES = Map.ofEntries(
            // DeepSeek 模型
            Map.entry("deepseek-v4-pro", new ModelCapabilities("deepseek-v4-pro", "DeepSeek V4 Pro", 384000, 1000000, true, true, false, true, 0.001, 0.004)),
            Map.entry("deepseek-v4-flash", new ModelCapabilities("deepseek-v4-flash", "DeepSeek V4 Flash", 384000, 1000000, true, true, false, true, 0.0005, 0.002)),
            // 阿里云百炼 - 通义千问模型（qwen3.7-max/qwen3.7-plus/qwen-turbo 已迁移至 ModelRegistry.BUILTIN_MODELS）
            Map.entry("qwen-coder-plus", new ModelCapabilities("qwen-coder-plus", "通义千问 Coder Plus", 8192, 131072, true, false, false, true, 0.0007, 0.002))
    );

    public OpenAiCompatibleProvider(
            String providerName,
            ObjectMapper objectMapper,
            LlmHttpProperties httpProperties,
            ApiKeyRotationManager keyRotationManager,
            String apiKey,
            String baseUrl,
            String defaultModel,
            List<String> supportedModels) {
        this.providerName = providerName;
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
                .readTimeout(Duration.ofMinutes(10)) // SSE 10分钟读超时（防止连接泄漏）
                .writeTimeout(Duration.ofSeconds(httpProperties.writeTimeoutSeconds()))
                .retryOnConnectionFailure(httpProperties.retryOnFailure())
                .build();

        log.info("OpenAI compatible provider initialized: baseUrl={}, models={}", this.baseUrl, supportedModels);
    }

    @Override
    public String getProviderName() { return providerName; }

    @Override
    public List<String> getSupportedModels() { return supportedModels; }

    @Override
    public String getDefaultModel() { return defaultModel; }

    @Override
    public String getFastModel() {
        // 优先使用轻量级模型用于摘要/分类等低延迟场景
        for (String candidate : List.of("qwen-turbo", "qwen3.7-plus")) {
            if (supportedModels.contains(candidate)) {
                return candidate;
            }
        }
        return defaultModel;
    }

    @Override
    public ModelCapabilities getModelCapabilities(String model) {
        ModelCapabilities caps = MODEL_CAPABILITIES.get(model);
        if (caps != null) return caps;
        // 未匹配时抛异常，让 ModelRegistry.getCapabilities() Level 2 的 catch(Exception)
        // 捕获后 fallback 到 Level 3（BUILTIN_MODELS），确保千问模型走正确的查询路径
        throw new IllegalArgumentException("Model not in provider capabilities: " + model);
    }

    /**
     * 思考能力快捷判定 — 覆盖 LlmProvider 默认实现以避免对 MODEL_CAPABILITIES 之外的模型
     * （例如 qwen3.7-max / qwen3.7-plus，已迁移到 ModelRegistry.BUILTIN_MODELS）
     * 触发 getModelCapabilities() 的 IllegalArgumentException。
     * <p>
     * 此处复用 Provider 已有的模型族判定函数（{@link #isDeepSeekV4Model} /
     * {@link #isQwenThinkingModel}），与请求构建阶段的 thinking 参数下发逻辑保持一致；
     * 同时 getModelCapabilities() 的抛异常契约保持不变，ModelRegistry 的 Level 2→Level 3
     * fallback 链路不受影响。
     */
    @Override
    public boolean supportsThinking(String model) {
        if (model == null) return false;
        ModelCapabilities caps = MODEL_CAPABILITIES.get(model);
        if (caps != null) return caps.supportsThinking();
        return isDeepSeekV4Model(model) || isQwenThinkingModel(model);
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

        ObjectNode requestBody = buildOpenAiRequest(model, messages, systemPrompt, tools, maxTokens, thinkingConfig);
        log.info("[DIAG] streamChat: model={}, messagesSize={}, baseUrl={}", model, messages.size(), baseUrl);

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
        log.info("[DIAG] streamChat: 发送 HTTP 请求, callId={}, url={}", callId, baseUrl + "/chat/completions");
        Call call = httpClient.newCall(request);
        activeCalls.put(callId, call);

        // 工具调用累积器 — OpenAI 的工具调用通过多个 delta 增量拼接
        Map<Integer, ToolCallAccumulator> toolCallAccumulators = new HashMap<>();

        try (Response response = call.execute()) {
            log.info("[DIAG] streamChat: HTTP 响应 code={}, callId={}", response.code(), callId);
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
            int maxTokens,
            ThinkingConfig thinkingConfig) {

        ObjectNode root = buildBaseRequest(model, messages, systemPrompt, tools, maxTokens);
        root.put("stream", true);
        ObjectNode streamOptions = root.putObject("stream_options");
        streamOptions.put("include_usage", true);

        // DeepSeek V4 思考模式参数
        // DeepSeek V4 系列（deepseek-v4-pro / deepseek-v4-flash）默认启用思考模式，
        // 必须始终发送 thinking 参数以保持一致性
        // 项目策略：V4 系列一律使用 max 推理强度（不在乎成本与耗时，追求最强推理）。
        if (isDeepSeekV4Model(model)) {
            ObjectNode thinking = root.putObject("thinking");
            thinking.put("type", "enabled");
            root.put("reasoning_effort", "max");
        } else if (isQwenThinkingModel(model) && thinkingConfig.requiresThinkingSupport()) {
            root.put("enable_thinking", true);
        }

        return root;
    }

    /** 判断是否为 DeepSeek V4 系列模型（仅 v4-pro / v4-flash 需要 thinking + max） */
    private static boolean isDeepSeekV4Model(String model) {
        return model != null && model.startsWith("deepseek-v4-");
    }

    /** 判断是否为支持思考模式的 Qwen 模型 */
    private static boolean isQwenThinkingModel(String model) {
        return model != null && (model.startsWith("qwen3.7-") || model.startsWith("qwen3.6-"));
    }

    /**
     * 构建基础请求体（不含 stream 设置）。
     * 公共逻辑：model、max_tokens、messages、system prompt、tools。
     * 由 buildOpenAiRequest（流式）和 chatSync（非流式）共享。
     */
    private ObjectNode buildBaseRequest(
            String model,
            List<Map<String, Object>> messages,
            String systemPrompt,
            List<Map<String, Object>> tools,
            int maxTokens) {

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);

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
                    StringBuilder thinkingContent = new StringBuilder();
                    for (Object block : blocks) {
                        if (block instanceof Map<?,?> b) {
                            if ("text".equals(b.get("type"))) {
                                Object text = b.get("text");
                                if (text != null && !text.toString().isEmpty()) {
                                    if (!textContent.isEmpty()) textContent.append("\n");
                                    textContent.append(text);
                                }
                            } else if ("thinking".equals(b.get("type"))) {
                                Object thinking = b.get("thinking");
                                if (thinking != null && !thinking.toString().isEmpty()) {
                                    thinkingContent.append(thinking);
                                }
                            }
                        }
                    }
                    if (!textContent.isEmpty()) {
                        msgNode.put("content", textContent.toString());
                    } else {
                        msgNode.putNull("content");
                    }
                    // DeepSeek: reasoning_content 必须回传
                    if (!thinkingContent.isEmpty()) {
                        msgNode.put("reasoning_content", thinkingContent.toString());
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
                StringBuilder thinkingContent = new StringBuilder();
                for (Object block : blocks) {
                    if (block instanceof Map<?,?> b) {
                        if ("text".equals(b.get("type"))) {
                            Object text = b.get("text");
                            if (text != null && !text.toString().isEmpty()) {
                                if (!textContent.isEmpty()) textContent.append("\n");
                                textContent.append(text);
                            }
                        } else if ("thinking".equals(b.get("type")) && "assistant".equals(role)) {
                            Object thinking = b.get("thinking");
                            if (thinking != null && !thinking.toString().isEmpty()) {
                                thinkingContent.append(thinking);
                            }
                        }
                    }
                }
                ObjectNode msgNode = messagesArray.addObject();
                msgNode.put("role", role != null ? role : "user");
                msgNode.put("content", textContent.toString());
                // DeepSeek: reasoning_content 必须回传
                if ("assistant".equals(role) && !thinkingContent.isEmpty()) {
                    msgNode.put("reasoning_content", thinkingContent.toString());
                }
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
    // 同步调用（分类器等低延迟场景）
    // ═══════════════════════════════════════════

    /**
     * 同步调用 LLM — 用于 AutoModeClassifier 等低延迟场景。
     * <p>
     * 使用非流式请求（stream:false），支持 stopSequences。
     * HTTP 429 时内部执行一次指数退避重试（AutoModeClassifier.callClassifierLLM()
     * 不使用 ApiRetryService，429 异常会被包装为 ClassifierUnavailableException）。
     *
     * @see com.aicodeassistant.permission.AutoModeClassifier#callClassifierLLM
     */
    @Override
    public String chatSync(String model, String systemPrompt, String userContent,
                           int maxTokens, String[] stopSequences, long timeoutMs) {
        // 1. 构建非流式请求体
        ObjectNode requestBody = buildBaseRequest(model,
                List.of(Map.of("role", "user", "content", userContent)),
                systemPrompt, List.of(), maxTokens);
        requestBody.put("stream", false);

        // 2. 添加 stop sequences
        if (stopSequences != null && stopSequences.length > 0) {
            ArrayNode stopArray = requestBody.putArray("stop");
            for (String seq : stopSequences) {
                stopArray.add(seq);
            }
        }

        // 3. 构建带超时的 OkHttpClient（共享连接池，线程安全）
        OkHttpClient syncClient = httpClient.newBuilder()
                .callTimeout(Duration.ofMillis(timeoutMs))
                .readTimeout(Duration.ofMillis(timeoutMs))
                .build();

        String effectiveApiKey = keyRotationManager.getKeyCount() > 0
                ? keyRotationManager.getNextKey() : apiKey;

        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + effectiveApiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), JSON_MEDIA))
                .build();

        // 4. 执行请求（429 时一次指数退避重试）
        for (int attempt = 0; attempt < 2; attempt++) {
            try (Response response = syncClient.newCall(request).execute()) {
                if (response.code() == 429 && attempt == 0) {
                    long waitMs = 1000;
                    String retryAfter = response.header("Retry-After");
                    if (retryAfter != null) {
                        try { waitMs = Long.parseLong(retryAfter) * 1000; }
                        catch (NumberFormatException ignored) {}
                    }
                    log.warn("chatSync 429 rate limited, retrying after {}ms", Math.min(waitMs, 5000));
                    Thread.sleep(Math.min(waitMs, 5000));
                    continue;
                }

                if (!response.isSuccessful()) {
                    throw new LlmApiException(
                            "chatSync HTTP " + response.code(),
                            response.code() >= 500, response.code());
                }

                ResponseBody body = response.body();
                if (body == null) {
                    throw new LlmApiException("Empty chatSync response", true);
                }

                JsonNode root = objectMapper.readTree(body.string());
                return root.path("choices").path(0)
                           .path("message").path("content").asText("");

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmApiException("chatSync interrupted", false);
            } catch (LlmApiException e) {
                throw e;
            } catch (IOException e) {
                throw new LlmApiException("chatSync IO error: " + e.getMessage(), true);
            }
        }
        throw new LlmApiException("chatSync failed after 429 retry", true, 429);
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
                // DeepSeek reasoning_content 思考增量
                if (delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()) {
                    String thinking = delta.get("reasoning_content").asText();
                    if (!thinking.isEmpty()) {
                        callback.onEvent(new LlmStreamEvent.ThinkingDelta(thinking));
                    }
                }

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
