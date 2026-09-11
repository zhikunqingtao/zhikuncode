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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private static final Set<String> ZENMUX_RESPONSES_MODELS = Set.of(
            "openai/gpt-5.6-sol",
            "openai/gpt-6-astra",
            "google/gemini-3.8-flash",
            "x-ai/grok-4.6");
    /** ZenMux 订阅配额错误（402 quote_exceeded / 404 model_not_available）的 Key 冷却时长 */
    private static final Duration SUBSCRIPTION_QUOTA_COOLDOWN = Duration.ofMinutes(15);

    private final OkHttpClient httpClient;
    private final String providerName;
    private final String apiKey;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final List<String> supportedModels;
    private final String defaultModel;
    private final ApiKeyRotationManager keyRotationManager;
    private final FinalProviderPayloadGuard payloadGuard;

    private final ConcurrentHashMap<String, Call> activeCalls = new ConcurrentHashMap<>();

    /** 内置模型能力映射表*/
    private static final Map<String, ModelCapabilities> MODEL_CAPABILITIES = Map.ofEntries(
            // DeepSeek 模型
            Map.entry("deepseek-flash", new ModelCapabilities("deepseek-flash", "DeepSeek V4.1 Flash", 384000, 1000000, true, true, true, 600, true, 0.0003, 0.0012)),
            Map.entry("deepseek-v4-pro-0813", new ModelCapabilities("deepseek-v4-pro-0813", "DeepSeek V4 Pro 0813（百炼）", 384000, 1000000, true, true, false, 0, true, 0.001, 0.004)),
            Map.entry("deepseek-v4-flash-0731", new ModelCapabilities("deepseek-v4-flash-0731", "DeepSeek V4 Flash 0731（百炼）", 384000, 1000000, true, true, false, 0, true, 0.0005, 0.002)),
            // 阿里云百炼 - 通义千问模型（qwen3.8-max-0902/qwen3.7-plus/qwen-turbo 已迁移至 ModelRegistry.BUILTIN_MODELS）
            Map.entry("qwen-coder-plus", new ModelCapabilities("qwen-coder-plus", "通义千问 Coder Plus", 8192, 131072, true, false, false, 0, true, 0.0007, 0.002))
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
        this(providerName,objectMapper,httpProperties,keyRotationManager,apiKey,baseUrl,defaultModel,supportedModels,null);
    }
    public OpenAiCompatibleProvider(String providerName,ObjectMapper objectMapper,LlmHttpProperties httpProperties,
            ApiKeyRotationManager keyRotationManager,String apiKey,String baseUrl,String defaultModel,
            List<String> supportedModels,FinalProviderPayloadGuard payloadGuard) {
        this.providerName = providerName;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.keyRotationManager = keyRotationManager;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.defaultModel = defaultModel;
        this.supportedModels = supportedModels;
        this.payloadGuard = payloadGuard;

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
     * （例如 qwen3.8-max-0902 / qwen3.7-plus，已迁移到 ModelRegistry.BUILTIN_MODELS）
     * 触发 getModelCapabilities() 的 IllegalArgumentException。
     * <p>
     * 此处复用 Provider 已有的模型族判定函数（{@link #isDeepSeekV4Model} /
     * {@link #isQwenThinkingModel} / {@link #isGlmForcedThinkingModel}），与请求构建阶段的 thinking 参数下发逻辑保持一致；
     * 同时 getModelCapabilities() 的抛异常契约保持不变，ModelRegistry 的 Level 2→Level 3
     * fallback 链路不受影响。
     */
    @Override
    public boolean supportsThinking(String model) {
        if (model == null) return false;
        if ("zenmux".equalsIgnoreCase(providerName)
                && ZENMUX_RESPONSES_MODELS.contains(model)) return true;
        ModelCapabilities caps = MODEL_CAPABILITIES.get(model);
        if (caps != null) return caps.supportsThinking();
        return isDeepSeekV4Model(model) || isQwenThinkingModel(model) || isGlmForcedThinkingModel(model);
    }

    private boolean usesResponsesApi(String model) {
        return "zenmux".equalsIgnoreCase(providerName)
                && ZENMUX_RESPONSES_MODELS.contains(model);
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
            LlmCallContext callContext,
            StreamChatCallback callback) {

        if (usesResponsesApi(model)) {
            streamResponses(model, messages, systemPrompt, tools, maxTokens,
                    thinkingConfig, callContext, callback);
            return;
        }

        ObjectNode requestBody = buildOpenAiRequest(model, messages, systemPrompt, tools, maxTokens, thinkingConfig);
        if(payloadGuard!=null)payloadGuard.validate("openai",model,requestBody,maxTokens);
        log.debug("Starting model stream: model={}, messages={}", model, messages.size());

        // P1-12: 使用 Key 轮换管理器获取 API Key
        String effectiveApiKey = keyRotationManager.getKeyCount() > 0
                ? keyRotationManager.getNextKey() : apiKey;

        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + effectiveApiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), JSON_MEDIA))
                .build();

        String callId = callContext.requestId();
        log.debug("Sending model HTTP request: callId={}", callId);
        Call call = httpClient.newCall(request);
        // 工具调用累积器 — OpenAI 的工具调用通过多个 delta 增量拼接
        Map<Integer, ToolCallAccumulator> toolCallAccumulators = new HashMap<>();

        try (AutoCloseable ignored = LlmCallRegistration.register(activeCalls, callId, call,
                     callContext.cancellation(), Call::cancel);
             Response response = call.execute()) {
            log.debug("Model HTTP response: code={}, callId={}", response.code(), callId);
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
                callback.onError(new LlmApiException(
                        "LLM_CALL_CANCELLED", e, false));
            } else {
                // 保留 cause 链，使 ConnectException 能被重试层/ProviderErrorClassifier 识别
                callback.onError(new LlmApiException("OpenAI stream error: " + e.getMessage(), e, true));
            }
        } catch (Exception e) {
            callback.onError(e instanceof LlmApiException ? e : new LlmApiException(e.getMessage(), e, false));
        }
    }

    private void streamResponses(
            String model,
            List<Map<String, Object>> messages,
            String systemPrompt,
            List<Map<String, Object>> tools,
            int maxTokens,
            ThinkingConfig thinkingConfig,
            LlmCallContext callContext,
            StreamChatCallback callback) {
        ObjectNode requestBody = buildResponsesRequest(
                model, messages, systemPrompt, tools, maxTokens, thinkingConfig);
        if (payloadGuard != null) payloadGuard.validate("zenmux-responses", model, requestBody, maxTokens);

        String effectiveApiKey = keyRotationManager.getKeyCount() > 0
                ? keyRotationManager.getNextKey() : apiKey;
        Request request = new Request.Builder()
                .url(baseUrl + "/responses")
                .header("Authorization", "Bearer " + effectiveApiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), JSON_MEDIA))
                .build();
        String callId = callContext.requestId();
        Call call = httpClient.newCall(request);
        ResponsesStreamState streamState = new ResponsesStreamState();

        try (AutoCloseable ignored = LlmCallRegistration.register(activeCalls, callId, call,
                     callContext.cancellation(), Call::cancel);
             Response response = call.execute()) {
            if (!response.isSuccessful()) {
                handleErrorResponse(response, callback);
                return;
            }
            ResponseBody body = response.body();
            if (body == null) {
                callback.onError(new LlmApiException("Empty Responses body", true));
                return;
            }
            BufferedSource source = body.source();
            while (!source.exhausted()) {
                String line = source.readUtf8LineStrict();
                if (line.isBlank() || line.startsWith(":")) continue;
                if (!line.startsWith("data:")) continue;
                String json = line.substring(5).trim();
                if (json.isEmpty() || "[DONE]".equals(json)) continue;
                JsonNode event = objectMapper.readTree(json);
                processResponsesEvent(event, streamState, callback);
                if (streamState.terminal) return;
            }
            callback.onError(new LlmApiException(
                    "ZENMUX_RESPONSES_INCOMPLETE_STREAM", false, 0,
                    "incomplete_stream", 0));
        } catch (IOException e) {
            if (call.isCanceled()) {
                callback.onError(new LlmApiException("LLM_CALL_CANCELLED", e, false));
            } else {
                // 保留 cause 链，使 ConnectException 能被重试层/ProviderErrorClassifier 识别
                callback.onError(new LlmApiException(
                        "ZenMux Responses stream error: " + e.getMessage(), e, true));
            }
        } catch (Exception e) {
            callback.onError(e instanceof LlmApiException
                    ? e : new LlmApiException(e.getMessage(), e, false));
        }
    }

    @jakarta.annotation.PreDestroy
    void cancelAllOnShutdown() {
        LlmCallRegistration.cancelAll(activeCalls, Call::cancel);
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

        // DeepSeek V4/V4.1 思考模式参数
        // DeepSeek V4 系列、V4.1 Flash（含百炼日期版本）默认启用思考模式，
        // 必须始终发送 thinking 参数以保持一致性
        // 项目策略：V4 系列一律使用 max 推理强度（不在乎成本与耗时，追求最强推理）。
        if (isDeepSeekV4Model(model)) {
            ObjectNode thinking = root.putObject("thinking");
            thinking.put("type", "enabled");
            root.put("reasoning_effort", "max");
        } else if ("kimi-k3".equals(model)) {
            // Kimi K3 官方推荐显式传入 reasoning_effort="max" 以获得最强推理
            root.put("reasoning_effort", "max");
        } else if (isGlmForcedThinkingModel(model)) {
            // GLM-5.3 / GLM-5.3-Flash 官方规范：thinking.type 仅支持 enabled（思考不可关闭），
            // 推理强度由 reasoning_effort 控制（low/high/max，官方推荐 max）。
            // clear_thinking=false 开启保留式思考——thinking 块已在消息历史中
            // 完整回传（reasoning_content），Coding/Agent 场景官方推荐；
            // 流式调用官方建议 stream 与 tool_stream 同时开启。
            ObjectNode thinking = root.putObject("thinking");
            thinking.put("type", "enabled");
            thinking.put("clear_thinking", false);
            root.put("reasoning_effort", "max");
            root.put("tool_stream", true);
        } else if (isQwenThinkingModel(model) && thinkingConfig.requiresThinkingSupport()) {
            root.put("enable_thinking", true);
        }

        return root;
    }

    /** Builds the stateless ZenMux Responses request from internal message maps. */
    private ObjectNode buildResponsesRequest(
            String model,
            List<Map<String, Object>> messages,
            String systemPrompt,
            List<Map<String, Object>> tools,
            int maxTokens,
            ThinkingConfig thinkingConfig) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("stream", true);
        root.put("store", false);
        root.put("parallel_tool_calls", true);
        root.put("max_output_tokens", maxTokens);
        root.putArray("include").add("reasoning.encrypted_content");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            root.put("instructions", systemPrompt);
        }

        ObjectNode reasoning = root.putObject("reasoning");
        if (thinkingConfig == null || thinkingConfig instanceof ThinkingConfig.Disabled) {
            reasoning.put("effort", "none");
        } else {
            reasoning.put("effort", model.startsWith("openai/") ? "xhigh" : "high");
            reasoning.put("summary", "auto");
        }

        ArrayNode input = root.putArray("input");
        for (Map<String, Object> message : messages) {
            String role = Objects.toString(message.get("role"), "user");
            Object content = message.get("content");
            if (!(content instanceof List<?> blocks)) {
                appendResponsesTextMessage(input, role, content == null ? "" : content.toString());
                continue;
            }

            if ("assistant".equals(role) && appendMatchingProviderState(input, blocks, model)) {
                continue;
            }

            if ("user".equals(role)) {
                for (Object block : blocks) {
                    if (block instanceof Map<?, ?> map
                            && "tool_result".equals(map.get("type"))) {
                        ObjectNode output = input.addObject();
                        output.put("type", "function_call_output");
                        output.put("call_id", Objects.toString(map.get("tool_use_id"), ""));
                        output.put("output", Objects.toString(map.get("content"), ""));
                    }
                }
            }

            ArrayNode contentParts = objectMapper.createArrayNode();
            for (Object block : blocks) {
                if (!(block instanceof Map<?, ?> map)) continue;
                String type = Objects.toString(map.get("type"), "");
                if ("text".equals(type)) {
                    ObjectNode part = contentParts.addObject();
                    // Responses API: assistant 消息的 content 只允许 output_text/refusal，
                    // 历史轮次的 assistant 文本若编码为 input_text 会被 OpenAI 后端 400 拒绝
                    // (Invalid value: 'input_text')，Gemini 后端则因 parts 为空报错。
                    part.put("type", "assistant".equals(role) ? "output_text" : "input_text");
                    part.put("text", Objects.toString(map.get("text"), ""));
                } else if ("image".equals(type) && "user".equals(role)) {
                    Object source = map.get("source");
                    if (source instanceof Map<?, ?> sourceMap) {
                        String imageUrl = resolveResponseImageUrl(sourceMap);
                        if (imageUrl != null) {
                            ObjectNode part = contentParts.addObject();
                            part.put("type", "input_image");
                            part.put("detail", "auto");
                            part.put("image_url", imageUrl);
                        }
                    }
                }
            }
            if (!contentParts.isEmpty()) {
                ObjectNode inputMessage = input.addObject();
                inputMessage.put("type", "message");
                inputMessage.put("role", role);
                inputMessage.set("content", contentParts);
            }

            if ("assistant".equals(role)) {
                for (Object block : blocks) {
                    if (!(block instanceof Map<?, ?> map)
                            || !"tool_use".equals(map.get("type"))) continue;
                    ObjectNode call = input.addObject();
                    call.put("type", "function_call");
                    call.put("call_id", Objects.toString(map.get("id"), ""));
                    call.put("name", Objects.toString(map.get("name"), ""));
                    try {
                        call.put("arguments", objectMapper.writeValueAsString(
                                map.get("input") != null ? map.get("input") : Map.of()));
                    } catch (Exception invalid) {
                        throw new LlmApiException("INVALID_STORED_TOOL_INPUT", invalid, false);
                    }
                }
            }
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode responseTools = root.putArray("tools");
            for (Map<String, Object> tool : tools) {
                Map<?, ?> function = tool.get("function") instanceof Map<?, ?> nested
                        ? nested : tool;
                ObjectNode encoded = responseTools.addObject();
                encoded.put("type", "function");
                encoded.put("name", Objects.toString(function.get("name"), ""));
                Object description = function.get("description");
                if (description != null) encoded.put("description", description.toString());
                Object parameters = function.containsKey("parameters")
                        ? function.get("parameters") : function.get("input_schema");
                encoded.set("parameters", objectMapper.valueToTree(
                        parameters != null ? parameters : Map.of("type", "object")));
                encoded.put("strict", false);
            }
        }
        return root;
    }

    private boolean appendMatchingProviderState(ArrayNode input, List<?> blocks, String model) {
        for (Object block : blocks) {
            if (!(block instanceof Map<?, ?> map)
                    || !"provider_response_state".equals(map.get("type"))
                    || !providerName.equals(map.get("provider"))
                    || !model.equals(map.get("model"))) continue;
            JsonNode output = objectMapper.valueToTree(map.get("output"));
            if (!output.isArray() || output.isEmpty()) return false;
            output.forEach(item -> input.add(item.deepCopy()));
            return true;
        }
        return false;
    }

    private static void appendResponsesTextMessage(ArrayNode input, String role, String text) {
        ObjectNode message = input.addObject();
        message.put("type", "message");
        message.put("role", role);
        ArrayNode content = message.putArray("content");
        ObjectNode part = content.addObject();
        part.put("type", "assistant".equals(role) ? "output_text" : "input_text");
        part.put("text", text);
    }

    private static String resolveResponseImageUrl(Map<?, ?> source) {
        Object url = source.get("url");
        if (url != null && !url.toString().isBlank()) return url.toString();
        Object mediaType = source.get("media_type");
        Object data = source.get("data");
        return mediaType == null || data == null
                ? null : "data:" + mediaType + ";base64," + data;
    }

    private void processResponsesEvent(JsonNode event, ResponsesStreamState state,
                                       StreamChatCallback callback) {
        String type = event.path("type").asText("");
        switch (type) {
            case "response.output_text.delta" -> emitTextDelta(event, callback);
            case "response.refusal.delta" -> emitTextDelta(event, callback);
            case "response.reasoning_summary_text.delta" -> {
                String delta = event.path("delta").asText("");
                if (!delta.isEmpty()) {
                    state.reasoningSummary.append(delta);
                    callback.onEvent(new LlmStreamEvent.ThinkingDelta(delta));
                }
            }
            case "response.output_item.added", "response.output_item.done",
                 "response.function_call_arguments.delta", "response.function_call_arguments.done",
                 "response.reasoning_text.delta", "response.reasoning_text.done",
                 "response.reasoning_summary_part.added", "response.reasoning_summary_part.done",
                 "response.reasoning_summary_text.done" -> markResponsesProgress(state, callback);
            case "response.completed" -> completeResponses(event.path("response"), false, state, callback);
            case "response.incomplete" -> completeResponses(event.path("response"), true, state, callback);
            case "response.failed" -> failResponses(event.path("response").path("error"), state, callback);
            case "error" -> failResponses(event, state, callback);
            default -> { /* forward-compatible: ignore lifecycle and unknown events */ }
        }
    }

    private static void emitTextDelta(JsonNode event, StreamChatCallback callback) {
        String delta = event.path("delta").asText("");
        if (!delta.isEmpty()) callback.onEvent(new LlmStreamEvent.TextDelta(delta));
    }

    private static void markResponsesProgress(ResponsesStreamState state,
                                              StreamChatCallback callback) {
        if (!state.progressEmitted) {
            state.progressEmitted = true;
            callback.onEvent(new LlmStreamEvent.ProviderProgress());
        }
    }

    private void completeResponses(JsonNode response, boolean incomplete,
                                   ResponsesStreamState state,
                                   StreamChatCallback callback) {
        JsonNode output = response.path("output");
        if (!output.isArray()) {
            failResponsesText("ZENMUX_RESPONSES_OUTPUT_MISSING", state, callback);
            return;
        }
        List<JsonNode> outputItems = new ArrayList<>();
        boolean hasFunctionCall = false;
        for (JsonNode item : output) {
            outputItems.add(item.deepCopy());
            if ("function_call".equals(item.path("type").asText())) hasFunctionCall = true;
        }

        if (incomplete) {
            String reason = response.path("incomplete_details").path("reason").asText("");
            if (!"max_output_tokens".equals(reason) || hasFunctionCall) {
                failResponsesText("ZENMUX_RESPONSES_INCOMPLETE: " + reason, state, callback);
                return;
            }
        }

        if (state.reasoningSummary.isEmpty()) {
            String summary = extractResponsesSummary(output);
            if (!summary.isBlank()) {
                state.reasoningSummary.append(summary);
                callback.onEvent(new LlmStreamEvent.ThinkingDelta(summary));
            }
        }

        List<ResponsesFunctionCall> functionCalls = new ArrayList<>();
        if (!incomplete) {
            int outputIndex = 0;
            Set<String> callIds = new HashSet<>();
            for (JsonNode item : output) {
                if ("function_call".equals(item.path("type").asText())) {
                    String callId = item.path("call_id").asText("");
                    String name = item.path("name").asText("");
                    String arguments = item.path("arguments").asText("");
                    if (callId.isBlank() || name.isBlank() || arguments.isBlank()
                            || !callIds.add(callId)) {
                        failResponsesText("INVALID_RESPONSES_FUNCTION_CALL", state, callback);
                        return;
                    }
                    try {
                        objectMapper.readTree(arguments);
                    } catch (Exception invalid) {
                        failResponsesText("INVALID_RESPONSES_FUNCTION_ARGUMENTS", state, callback);
                        return;
                    }
                    functionCalls.add(new ResponsesFunctionCall(
                            outputIndex, callId, name, arguments));
                }
                outputIndex++;
            }
        }
        callback.onEvent(new LlmStreamEvent.ProviderResponseState(outputItems));
        for (ResponsesFunctionCall call : functionCalls) {
            callback.onEvent(new LlmStreamEvent.ToolUseStart(call.callId(), call.name()));
            callback.onEvent(new LlmStreamEvent.ToolInputDelta(call.callId(), call.arguments()));
            callback.onEvent(new LlmStreamEvent.BlockStop(call.outputIndex()));
        }
        callback.onEvent(new LlmStreamEvent.MessageDelta(
                parseResponsesUsage(response.path("usage")),
                incomplete ? "max_tokens" : hasFunctionCall ? "tool_use" : "end_turn"));
        state.terminal = true;
        callback.onComplete();
    }

    private void failResponses(JsonNode error, ResponsesStreamState state,
                               StreamChatCallback callback) {
        String code = error.path("code").asText(error.path("type").asText("responses_error"));
        String message = error.path("message").asText(code);
        boolean retryable = code.contains("rate_limit") || code.contains("overloaded")
                || code.contains("server") || code.contains("internal");
        state.terminal = true;
        callback.onError(new LlmApiException(message, retryable, 0, code, 0));
    }

    private static void failResponsesText(String message, ResponsesStreamState state,
                                          StreamChatCallback callback) {
        state.terminal = true;
        callback.onError(new LlmApiException(message, false));
    }

    private Usage parseResponsesUsage(JsonNode usage) {
        return new Usage(
                usage.path("input_tokens").asInt(0),
                usage.path("output_tokens").asInt(0),
                usage.path("input_tokens_details").path("cached_tokens").asInt(0),
                0);
    }

    private static String extractResponsesSummary(JsonNode output) {
        StringBuilder text = new StringBuilder();
        for (JsonNode item : output) {
            if (!"reasoning".equals(item.path("type").asText())) continue;
            for (JsonNode part : item.path("summary")) {
                String value = part.path("text").asText("");
                if (value.isBlank()) continue;
                if (!text.isEmpty()) text.append('\n');
                text.append(value);
            }
        }
        return text.toString();
    }

    /** 判断是否为 DeepSeek V4/V4.1 系列模型（直连及百炼日期版本均使用 thinking + max） */
    private static boolean isDeepSeekV4Model(String model) {
        return model != null
                && ("deepseek-flash".equals(model)
                    || model.startsWith("deepseek-v4-"));
    }

    /** 判断是否为支持思考模式的 Qwen 模型（qwen3.8-max/flash 官方均支持思考模式） */
    private static boolean isQwenThinkingModel(String model) {
        return model != null && (model.startsWith("qwen3.8-") || model.startsWith("qwen3.7-") || model.startsWith("qwen3.6-"));
    }

    /**
     * 判断是否为 GLM 强制思考模型（GLM-5.3 / GLM-5.3-Flash）。
     * 官方 API 规范：thinking.type 仅支持 enabled，不支持 disabled，
     * 由 reasoning_effort（low/high/max）控制思考强度。
     */
    private static boolean isGlmForcedThinkingModel(String model) {
        return "glm-5.3".equals(model) || "glm-5.3-flash".equals(model);
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
        // Kimi 系列模型使用 max_completion_tokens 参数名（官方要求）
        String maxTokensKey = model.startsWith("kimi-") ? "max_completion_tokens" : "max_tokens";
        root.put(maxTokensKey, maxTokens);

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
                    // tool_result 后的文本或图片仍是用户内容，必须紧随 tool 消息保留。
                    boolean hasRemainingUserContent = blocks.stream().anyMatch(b ->
                            b instanceof Map<?,?> m
                                    && ("text".equals(m.get("type"))
                                        || "image".equals(m.get("type"))));
                    if (hasRemainingUserContent) {
                        ObjectNode userMsg = messagesArray.addObject();
                        userMsg.put("role", "user");
                        ArrayNode contentArray = userMsg.putArray("content");
                        for (Object block : blocks) {
                            if (!(block instanceof Map<?,?> b)) continue;
                            if ("text".equals(b.get("type"))) {
                                Object text = b.get("text");
                                if (text == null) continue;
                                ObjectNode textPart = contentArray.addObject();
                                textPart.put("type", "text");
                                textPart.put("text", text.toString());
                            } else if ("image".equals(b.get("type"))) {
                                Object srcObj = b.get("source");
                                if (!(srcObj instanceof Map<?,?> src)) continue;
                                appendImageUrlPart(contentArray, src);
                            }
                        }
                        if (contentArray.isEmpty()) {
                            messagesArray.remove(messagesArray.size() - 1);
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
                // user 消息若包含 image 块，则改用 OpenAI 多模态 content 数组格式
                boolean hasImage = "user".equals(role) && blocks.stream().anyMatch(b ->
                        b instanceof Map<?,?> m && "image".equals(m.get("type")));
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
                if (hasImage) {
                    // OpenAI 多模态: content 为数组，包含 text 与 image_url 项
                    ArrayNode contentArray = msgNode.putArray("content");
                    if (!textContent.isEmpty()) {
                        ObjectNode textPart = contentArray.addObject();
                        textPart.put("type", "text");
                        textPart.put("text", textContent.toString());
                    }
                    for (Object block : blocks) {
                        if (block instanceof Map<?,?> b && "image".equals(b.get("type"))) {
                            Object srcObj = b.get("source");
                            if (!(srcObj instanceof Map<?,?> src)) continue;
                            appendImageUrlPart(contentArray, src);
                        }
                    }
                } else {
                    msgNode.put("content", textContent.toString());
                }
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

    /** Converts either an Anthropic-style base64 or URL source to OpenAI image_url. */
    private static void appendImageUrlPart(ArrayNode contentArray, Map<?, ?> source) {
        Object remoteUrl = source.get("url");
        String resolvedUrl;
        if (remoteUrl != null && !remoteUrl.toString().isBlank()) {
            resolvedUrl = remoteUrl.toString();
        } else {
            Object mediaType = source.get("media_type");
            Object data = source.get("data");
            if (mediaType == null || data == null) return;
            resolvedUrl = "data:" + mediaType + ";base64," + data;
        }
        ObjectNode imagePart = contentArray.addObject();
        imagePart.put("type", "image_url");
        imagePart.putObject("image_url").put("url", resolvedUrl);
    }

    // ═══════════════════════════════════════════
    // 同步调用（分类器等低延迟场景）
    // ═══════════════════════════════════════════

    /**
     * 同步调用 LLM — 用于需要非流式响应的低延迟场景。
     * <p>
     * 使用非流式请求（stream:false），支持 stopSequences。
     * HTTP 429 时内部执行一次指数退避重试；该同步路径不使用 ApiRetryService，
     * 429 异常由调用方按普通提供商异常处理。
     *
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
                    int errorCode = response.code();
                    String errorType = "";
                    String errorBody = "";
                    try {
                        ResponseBody errorResponseBody = response.body();
                        if (errorResponseBody != null) {
                            // body 只能消费一次：同一次读取同时用于冷却判定与异常消息
                            errorBody = errorResponseBody.string();
                            JsonNode errorJson = objectMapper.readTree(errorBody);
                            // error.type 优先、缺失时回退 error.code（双匹配）
                            errorType = errorJson.path("error").path("type")
                                    .asText(errorJson.path("error").path("code").asText(""));
                        }
                    } catch (Exception ignored) {
                        // body 缺失或非 JSON 时不影响错误上抛
                    }
                    // 429 最终失败时也冷却实际使用的 Key（与流式路径一致）
                    if (errorCode == 429 && keyRotationManager.getKeyCount() > 1) {
                        markUsedKeyCooldown(response, null);
                    }
                    // ZenMux 订阅错误（402 quote_exceeded / 404 model_not_available）：
                    // 冷却实际使用的 Key，后续 chatSync 调用自动切换到其他 Key
                    applySubscriptionKeyCooldown(response, errorCode, errorType);
                    // 异常消息截断：超长 body 只保留前 500 字符
                    String detail = errorBody.isBlank() ? ""
                            : (errorBody.length() > 500
                                    ? ": " + errorBody.substring(0, 500) + "…"
                                    : ": " + errorBody);
                    throw new LlmApiException(
                            "chatSync HTTP " + errorCode + detail,
                            errorCode >= 500, errorCode);
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
                // 保留 cause 链，使 ConnectException 能被重试层/ProviderErrorClassifier 识别
                throw new LlmApiException("chatSync IO error: " + e.getMessage(), e, true);
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
                    // 只补 usage，不覆盖前一个非空 finish_reason。
                    callback.onEvent(new LlmStreamEvent.MessageDelta(usage, null));
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
                            String id = tc.get("id").isNull()
                                    ? "" : tc.get("id").asText("");
                            if (id.isBlank() && acc.id == null) {
                                throw invalidToolCallStream(
                                        index, "empty id before identity");
                            }
                            if (!id.isBlank() && acc.id == null) acc.id = id;
                        }
                        JsonNode fn = null;
                        if (tc.has("function")) {
                            fn = tc.get("function");
                            if (fn.has("name")) {
                                String name = fn.get("name").isNull()
                                        ? "" : fn.get("name").asText("");
                                if (name.isBlank() && acc.name == null) {
                                    throw invalidToolCallStream(
                                            index, "empty name before identity");
                                }
                                if (!name.isBlank() && acc.name == null) {
                                    acc.name = name;
                                }
                            }
                        }
                        if (!acc.startEmitted && acc.id != null && acc.name != null) {
                            callback.onEvent(new LlmStreamEvent.ToolUseStart(
                                    acc.id, acc.name));
                            acc.startEmitted = true;
                        }
                        if (fn != null) {
                            if (fn.has("arguments")) {
                                if (!acc.startEmitted) {
                                    throw invalidToolCallStream(
                                            index, "arguments before identity");
                                }
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
                // ★ 修复：当流结束时，先为所有累积的 tool call 发送 BlockStop 事件，
                // 确保 QueryEngine 在收到 MessageDelta 之前已 flush 完所有工具块。
                // 这解决了 Qwen 等模型将 finish_reason 和最后一批 arguments
                // 放在同一 chunk 时的时序问题。
                for (Map.Entry<Integer, ToolCallAccumulator> entry : accumulators.entrySet()) {
                    if (entry.getValue().startEmitted
                            && !entry.getValue().stopEmitted) {
                        callback.onEvent(new LlmStreamEvent.BlockStop(entry.getKey()));
                        entry.getValue().stopEmitted = true;
                    }
                }
                Usage usage = chunk.has("usage")
                        ? parseUsage(chunk.get("usage"))
                        : Usage.zero();
                callback.onEvent(new LlmStreamEvent.MessageDelta(usage, finishReason));
            }

        } catch (LlmApiException e) {
            throw e;
        } catch (Exception e) {
            callback.onError(new LlmApiException(
                    "Failed to parse OpenAI chunk: " + e.getMessage(), false));
        }
    }

    private static LlmApiException invalidToolCallStream(
            int index, String detail) {
        return new LlmApiException(
                "INVALID_TOOL_CALL_STREAM: index=" + index + ", " + detail,
                false);
    }

    // ═══════════════════════════════════════════
    // 错误处理
    // ═══════════════════════════════════════════

    private void handleErrorResponse(Response response, StreamChatCallback callback) {
        int code = response.code();
        boolean retryable = code == 429 || code >= 500;
        String errorMsg;
        String errorType = "";
        try {
            ResponseBody body = response.body();
            if (body != null) {
                JsonNode bodyJson = objectMapper.readTree(body.string());
                errorMsg = bodyJson.path("error").path("message").asText("Unknown error");
                // error.type 优先、缺失时回退 error.code（双匹配）
                errorType = bodyJson.path("error").path("type")
                        .asText(bodyJson.path("error").path("code").asText(""));
            } else {
                errorMsg = "HTTP " + code;
            }
        } catch (Exception e) {
            errorMsg = "HTTP " + code;
        }

        // P1-12: 429 限流时标记 Key 冷却 — 从 Authorization header 提取实际使用的 Key
        // （getNextKey() 已前移轮换索引，getCurrentKey() 会指向未使用的下一把 Key）。
        // Retry-After 解析失败/缺失时传 null，markRateLimited 内部回退默认冷却（60s）。
        if (code == 429 && keyRotationManager.getKeyCount() > 1) {
            String retryAfter = response.header("Retry-After");
            java.time.Duration cooldown = null;
            if (retryAfter != null) {
                try {
                    cooldown = java.time.Duration.ofSeconds(Long.parseLong(retryAfter));
                } catch (NumberFormatException ignored) {}
            }
            markUsedKeyCooldown(response, cooldown);
        }

        // ZenMux 订阅 Key 专属错误（402/404）→ 冷却实际使用的 Key，后续请求切换
        applySubscriptionKeyCooldown(response, code, errorType);

        callback.onError(new LlmApiException(errorMsg, retryable, code));
    }

    /**
     * ZenMux 订阅 Key 专属错误冷却 — 402 quote_exceeded（订阅配额耗尽）/
     * 404 model_not_available（模型不在订阅计划内）：同 key 重试必然再次失败，
     * 标记实际使用的 Key 长冷却（15 分钟），后续请求自动切换到其他 Key（如按量 key 兜底）。
     * 流式（handleErrorResponse）与同步（chatSync）路径共用；仅多 Key 时冷却才有意义。
     */
    private void applySubscriptionKeyCooldown(Response response, int code, String errorType) {
        if (keyRotationManager.getKeyCount() > 1
                && isSubscriptionQuotaError(code, errorType)) {
            markUsedKeyCooldown(response, SUBSCRIPTION_QUOTA_COOLDOWN);
        }
    }

    /**
     * ZenMux 订阅 Key 专属错误：402 quote_exceeded（订阅配额耗尽）或
     * 404 model_not_available（模型不在订阅计划内）。两者均不可重试。
     */
    private static boolean isSubscriptionQuotaError(int code, String errorType) {
        return (code == 402 && "quote_exceeded".equals(errorType))
                || (code == 404 && "model_not_available".equals(errorType));
    }

    /**
     * 标记本次请求实际使用的 API Key 进入冷却。
     * <p>
     * 从响应对应请求的 Authorization header 提取 key（而非 getCurrentKey()：
     * getNextKey() 调用后轮换索引已前移，getCurrentKey() 指向的是下一把 key）。
     */
    private void markUsedKeyCooldown(Response response, Duration cooldown) {
        Request request = response.request();
        String authorization = request == null ? null : request.header("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return;
        }
        String usedKey = authorization.substring("Bearer ".length()).trim();
        if (!usedKey.isEmpty()) {
            keyRotationManager.markRateLimited(usedKey, cooldown);
        }
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
        boolean startEmitted;
        boolean stopEmitted;
        final StringBuilder arguments = new StringBuilder();
    }

    /** Per-request Responses SSE lifecycle state. */
    private static final class ResponsesStreamState {
        boolean progressEmitted;
        boolean terminal;
        final StringBuilder reasoningSummary = new StringBuilder();
    }

    private record ResponsesFunctionCall(
            int outputIndex, String callId, String name, String arguments) { }
}
