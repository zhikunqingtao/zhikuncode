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


import java.io.EOFException;
import java.io.IOException;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
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
            "google/gemini-4-argon",
            "x-ai/grok-4.7",
            "meta/muse-wonder-1.0");
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
            // 百炼：4 张图片为应用保守上限；价格为北京忙时按量参考，非订阅实际账单。
            Map.entry("deepseek-v4.1-flash", new ModelCapabilities("deepseek-v4.1-flash", "DeepSeek V4.1 Flash（百炼）", 393216, 1000000, true, true, true, 4, true, 0.002, 0.008)),
            // 阿里云百炼 - 通义千问模型（qwen3.8-max-0902/qwen-turbo 已迁移至 ModelRegistry.BUILTIN_MODELS）
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
        this.defaultModel = localModelId(defaultModel);
        this.supportedModels = isOpenRouter() || isBailianTokenPlan()
                ? supportedModels.stream().map(this::localModelId).distinct().toList() : supportedModels;
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
    public boolean supportsSummary(String model, SummaryRequest.ThinkingMode mode) {
        return SummaryTransportClient.supportsEndpoint(baseUrl) && supportedModels.contains(model)
                && SummaryRequest.supports(model, mode);
    }

    @Override
    public SummaryResult summarize(SummaryRequest request, LlmCallContext context) {
        if (!supportsSummary(request.model(), request.thinkingMode())) return SummaryResult.failed("unsupported_summary");
        if (request.maxCompletionTokens() <= 0) return SummaryResult.failed("invalid_summary_configuration");
        String key = keyRotationManager.getKeyCount() > 0 ? keyRotationManager.getNextKey() : apiKey;
        return SummaryTransportClient.execute(httpClient, objectMapper, baseUrl, key, activeCalls, request, context);
    }

    @Override
    public String getDefaultModel() { return defaultModel; }

    @Override
    public String getFastModel() {
        // 优先使用轻量级模型用于摘要/分类等低延迟场景
        for (String candidate : List.of("deepseek-v4.1-flash", "qwen-turbo")) {
            if (supportedModels.contains(candidate)) {
                return candidate;
            }
        }
        return defaultModel;
    }

    private boolean isOpenRouter() { return "openrouter".equalsIgnoreCase(providerName); }

    private boolean isBailianTokenPlan() { return "dashscope-token-plan".equalsIgnoreCase(providerName); }

    private String localModelId(String model) {
        if (isOpenRouter()) return OpenRouterModels.localId(model);
        if (isBailianTokenPlan()) return BailianTokenPlanModels.localId(model);
        return model;
    }

    private String upstreamModelId(String model) {
        if (isOpenRouter()) return OpenRouterModels.upstreamId(model);
        if (isBailianTokenPlan()) return BailianTokenPlanModels.upstreamId(model);
        return model;
    }

    private boolean isBailianGlm(String model) {
        return isBailianTokenPlan() && BailianTokenPlanModels.GLM_53.equals(model);
    }

    private boolean isKimiCodeModel(String model) {
        return "kimi-code".equalsIgnoreCase(providerName)
                && ("k3".equals(model) || "kimi-for-coding".equals(model));
    }

    @Override
    public ModelCapabilities getModelCapabilities(String model) {
        if (isBailianGlm(model)) return BailianTokenPlanModels.capabilities(model);
        if (isOpenRouter() && OpenRouterModels.capabilities(model) != null)
            return OpenRouterModels.capabilities(model);
        ModelCapabilities caps = MODEL_CAPABILITIES.get(model);
        if (caps != null) return caps;
        // 未匹配时抛异常，让 ModelRegistry.getCapabilities() Level 2 的 catch(Exception)
        // 捕获后 fallback 到 Level 3（BUILTIN_MODELS），确保千问模型走正确的查询路径
        throw new IllegalArgumentException("Model not in provider capabilities: " + model);
    }

    /**
     * 思考能力快捷判定 — 覆盖 LlmProvider 默认实现以避免对 MODEL_CAPABILITIES 之外的模型
     * （例如 qwen3.8-max-0902，已迁移到 ModelRegistry.BUILTIN_MODELS）
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
        if (isBailianGlm(model)) return true;
        if (isKimiCodeModel(model)) return true;
        if (isOpenRouter() && OpenRouterModels.capabilities(model) != null) return true;
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

        StreamTransportAttempt attempt = new StreamTransportAttempt(callContext.requestId());
        callback = attempt.callback(callback);
        boolean claude = isZenMuxClaude(model);
        StreamProgressCallback claudeCallback = claude ? new StreamProgressCallback(callback) : null;
        if (claude) callback = claudeCallback;
        ObjectNode requestBody = buildOpenAiRequest(model, messages, systemPrompt, tools, maxTokens, thinkingConfig);
        if(payloadGuard!=null && !claude)payloadGuard.validate("openai",model,requestBody,maxTokens);
        log.debug("Starting model stream: model={}, messages={}", model, messages.size());

        // P1-12: 使用 Key 轮换管理器获取 API Key
        String effectiveApiKey = keyRotationManager.getKeyCount() > 0 ? keyRotationManager.getNextKey() : apiKey;

        ChatReasoningContinuation claudeReasoning = claude
                ? ChatReasoningContinuation.prepare(objectMapper, requestBody, messages, model,
                        baseUrl + "/chat/completions", effectiveApiKey) : null;
        if (claude && payloadGuard != null) {
            try {
                payloadGuard.validate("openai", model, requestBody, maxTokens);
            } catch (LlmApiException failure) {
                boolean hasReasoning = false;
                for (JsonNode message : requestBody.path("messages")) {
                    if (!message.path("reasoning_details").isEmpty()) hasReasoning = true;
                }
                if (!"CONTEXT_BUDGET_EXCEEDED".equals(failure.getErrorType()) || !hasReasoning
                        || !ChatReasoningContinuation.RESET_MODELS.contains(model)) throw failure;
                // Optional private continuation must not force compaction of public history.
                // Reset only if the same public request fits; both checks precede the sole HTTP call.
                ObjectNode resetRequest = requestBody.deepCopy();
                ChatReasoningContinuation reset = ChatReasoningContinuation.prepareReset(objectMapper,
                        resetRequest, messages, model, baseUrl + "/chat/completions", effectiveApiKey);
                payloadGuard.validate("openai", model, resetRequest, maxTokens);
                requestBody = resetRequest;
                claudeReasoning = reset;
            }
        }

        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + effectiveApiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), JSON_MEDIA))
                .build();

        String callId = callContext.requestId();
        log.debug("Sending model HTTP request: callId={}", callId);
        Call call = attempt.newCall(httpClient, request);
        // 工具调用累积器 — OpenAI 的工具调用通过多个 delta 增量拼接
        Map<Integer, ToolCallAccumulator> toolCallAccumulators = new HashMap<>();
        java.util.concurrent.atomic.AtomicBoolean sawFinishReason =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        StreamDiagnostics diagnostics = new StreamDiagnostics();
        OpenRouterReasoning routerReasoning = isOpenRouter() ? new OpenRouterReasoning() : null;

        try (AutoCloseable ignored = LlmCallRegistration.register(activeCalls, callId, call,
                     callContext.cancellation(), Call::cancel);
             Response response = call.execute()) {
            attempt.response(response);
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
                callback.onError(attempt.interrupted("Empty response body", null));
                return;
            }

            BufferedSource source = attempt.source(body);
            while (!source.exhausted()) {
                String line;
                try {
                    if (claude) {
                        String data = readClaudeSseData(source);
                        if (data == null) break;
                        line = "data: " + data;
                    } else {
                        line = source.readUtf8LineStrict();
                    }
                } catch (EOFException truncated) {
                    // readUtf8LineStrict throws EOFException for an unterminated final line.
                    // Diagnose a missing-finish stream only if no finish was seen and the caller did not
                    // cancel. A truncated tail after a valid finish still follows the same error path.
                    if (!call.isCanceled() && !sawFinishReason.get()) {
                        logIncompleteStream(callId, response, "eof", diagnostics);
                    }
                    throw truncated;
                }
                diagnostics.observe(line);
                if (line.startsWith("data:")) attempt.frame();

                if (line.isEmpty()) continue;

                if ("data: [DONE]".equals(line)) {
                    if (!sawFinishReason.get()) {
                        if (!call.isCanceled()) {
                            logIncompleteStream(callId, response, "done", diagnostics);
                        }
                        callback.onError(attempt.interrupted(
                                "OPENAI_COMPATIBLE_INCOMPLETE_STREAM: missing finish_reason", null));
                        return;
                    }
                    if (routerReasoning != null) routerReasoning.complete(objectMapper, callback);
                    if (claude && call.isCanceled()) throw new LlmApiException("LLM_CALL_CANCELLED", false);
                    if (claudeReasoning != null) claudeReasoning.complete(callback);
                    logObservedTailMarker(callId, response, "done", diagnostics);
                    callback.onComplete();
                    return;
                }

                if (line.startsWith("data: ")) {
                    String json = line.substring(6);
                    if (routerReasoning != null) routerReasoning.accept(objectMapper.readTree(json), callback);
                    if (claudeReasoning != null) {
                        if (json.isBlank()) continue;
                        JsonNode chunk;
                        try {
                            chunk = objectMapper.readTree(json);
                        } catch (com.fasterxml.jackson.core.JsonProcessingException invalidJson) {
                            throw attempt.interrupted("Invalid ZenMux Claude SSE JSON", invalidJson);
                        }
                        if (chunk == null || !chunk.isObject()) {
                            throw attempt.interrupted("Invalid ZenMux Claude SSE object", null);
                        }
                        throwClaudeStreamError(chunk, response);
                        claudeReasoning.accept(chunk, callback);
                        // The dedicated collector displays reasoning once, including gateways
                        // that return both reasoning_details and reasoning_content.
                        JsonNode delta = chunk.path("choices").path(0).path("delta");
                        if (delta instanceof ObjectNode object) object.remove("reasoning_content");
                        json = chunk.toString();
                    }
                    processChunk(json, toolCallAccumulators, callback, sawFinishReason, diagnostics, model);
                    if (attempt.terminal()) return;
                    if (claude && call.isCanceled()) throw new LlmApiException("LLM_CALL_CANCELLED", false);
                }
            }
            if (routerReasoning != null) {
                callback.onError(attempt.interrupted("OpenRouter stream ended before [DONE]", null));
                return;
            }
            if (!sawFinishReason.get()) {
                if (!call.isCanceled()) {
                    logIncompleteStream(callId, response, "eof", diagnostics);
                }
                callback.onError(attempt.interrupted(
                        "OPENAI_COMPATIBLE_INCOMPLETE_STREAM: missing finish_reason", null));
                return;
            }
            // A normal compatible endpoint may end at EOF after a valid finish_reason.
            logObservedTailMarker(callId, response, "eof", diagnostics);
            if (claude && call.isCanceled()) throw new LlmApiException("LLM_CALL_CANCELLED", false);
            if (claudeReasoning != null) claudeReasoning.complete(callback);
            callback.onComplete();

        } catch (IOException e) {
            if (call.isCanceled()) {
                callback.onError(new LlmApiException(
                        "LLM_CALL_CANCELLED", e, false));
            } else {
                callback.onError(attempt.transportFailure(e));
            }
        } catch (Exception e) {
            callback.onError(e instanceof LlmApiException ? e : new LlmApiException("Failed to process model stream", e, false));
        } finally {
            attempt.logFinished();
        }
    }

    /** SSE dispatches at a blank line; a truncated, unterminated event is never accepted. */
    private static String readClaudeSseData(BufferedSource source) throws IOException {
        StringBuilder data = new StringBuilder();
        boolean hasData = false;
        while (!source.exhausted()) {
            String line = source.readUtf8LineStrict();
            if (line.isEmpty()) {
                if (hasData) return data.toString();
                continue;
            }
            if (!line.startsWith("data:")) continue;
            String value = line.substring(5);
            if (value.startsWith(" ")) value = value.substring(1);
            if (hasData) data.append('\n');
            data.append(value);
            hasData = true;
        }
        if (hasData) throw new EOFException("Incomplete Claude SSE event");
        return null;
    }

    private void throwClaudeStreamError(JsonNode chunk, Response response) {
        if (!chunk.hasNonNull("error")) return;
        JsonNode error = chunk.get("error");
        String type = error.path("type").asText(error.path("code").asText(""));
        int status = error.path("code").asInt(error.path("status").asInt(0));
        if (status == 0) status = switch (type) {
            case "rate_limit_error", "rate_limit_exceeded" -> 429;
            case "overloaded_error" -> 529;
            case "api_error", "server_error" -> 500;
            case "invalid_request_error" -> 400;
            case "authentication_error" -> 401;
            case "permission_error" -> 403;
            default -> 0;
        };
        applyStreamingErrorKeyCooldown(response, status, type);
        throw new LlmApiException("ZenMux Claude stream error: " + error.path("message").asText("Unknown error"),
                status == 429 || status >= 500, status, type, 0);
    }

    /** A failed stream cannot also complete or trigger transparent replay after progress. */
    private static final class StreamProgressCallback implements StreamChatCallback {
        private final StreamChatCallback delegate;
        private boolean progress;
        private boolean terminal;
        StreamProgressCallback(StreamChatCallback delegate) { this.delegate = delegate; }
        @Override public void onEvent(LlmStreamEvent event) {
            if (terminal) return;
            if (event instanceof LlmStreamEvent.TextDelta || event instanceof LlmStreamEvent.ThinkingDelta
                    || event instanceof LlmStreamEvent.ToolUseStart || event instanceof LlmStreamEvent.ToolInputDelta
                    || event instanceof LlmStreamEvent.ProviderProgress) progress = true;
            delegate.onEvent(event);
        }
        @Override public void onComplete() {
            if (terminal) return;
            terminal = true;
            delegate.onComplete();
        }
        @Override public void onError(Throwable error) {
            if (terminal) return;
            terminal = true;
            if (progress && error instanceof LlmApiException apiError) error = apiError.withRetryable(false);
            delegate.onError(error);
        }
    }

    private void logIncompleteStream(String callId, Response response, String end,
                                     StreamDiagnostics diagnostics) {
        try {
            String upstreamId = response.header("x-request-id");
            if (upstreamId == null) upstreamId = response.header("x-trace-id");
            if (upstreamId != null) {
                upstreamId = upstreamId.replaceAll("[^A-Za-z0-9._-]", "_");
                upstreamId = upstreamId.substring(0, Math.min(upstreamId.length(), 128));
            }
            log.warn("OpenAI stream missing finish_reason: callId={}, end={}, upstreamRequestId={}, "
                            + "ignoredDataFrames={}, firstIssue={}, lastFrame={}, lastIgnoredFrame={}",
                    callId, end, upstreamId, diagnostics.ignoredDataFrames,
                    diagnostics.firstIssueForLog(),
                    diagnostics.describe(objectMapper, diagnostics.lastFrame,
                            diagnostics.lastFrameTruncated),
                    diagnostics.describe(objectMapper, diagnostics.lastIgnoredFrame,
                            diagnostics.lastIgnoredFrameTruncated));
        } catch (RuntimeException diagnosticFailure) {
            // Diagnostics must never replace the original incomplete-stream error.
        }
    }

    private void logObservedTailMarker(String callId, Response response, String end,
                                    StreamDiagnostics diagnostics) {
        try {
            if (!log.isWarnEnabled() || !diagnostics.hasObservedTailMarker()) return;
            String upstreamId = response.header("x-request-id");
            if (upstreamId == null) upstreamId = response.header("x-trace-id");
            if (upstreamId != null) {
                upstreamId = upstreamId.substring(0, Math.min(upstreamId.length(), 128));
                upstreamId = upstreamId.replaceAll("[^A-Za-z0-9._-]", "_");
            }
            log.warn("OpenAI stream tail marker observed: callId={}, textChars={}, "
                            + "rawFinishReason={}, end={}, upstreamRequestId={}",
                    callId, diagnostics.textChars, diagnostics.rawFinishReason, end, upstreamId);
        } catch (RuntimeException diagnosticFailure) {
            // This observation must never alter output or stream completion.
        }
    }

    /** Retains bounded frame/tail state; logs metadata, never message content. */
    private static final class StreamDiagnostics {
        private static final int MAX_FRAME_CHARS = 16_384;
        private String lastFrame;
        private boolean lastFrameTruncated;
        private String lastIgnoredFrame;
        private boolean lastIgnoredFrameTruncated;
        private int ignoredDataFrames;
        private String firstIssue;
        private static final int OUTPUT_TAIL_CHARS = 64;
        private boolean inspectOutput;
        private StringBuilder outputTail;
        private StringBuilder pendingWhitespace;
        private long textChars;
        private String rawFinishReason = "none";

        StreamDiagnostics() {
            try {
                inspectOutput = log.isWarnEnabled();
                if (inspectOutput) {
                    outputTail = new StringBuilder(OUTPUT_TAIL_CHARS);
                    pendingWhitespace = new StringBuilder(OUTPUT_TAIL_CHARS);
                }
            } catch (RuntimeException diagnosticFailure) {
                inspectOutput = false;
            }
        }

        void observeFinishReason(String finishReason) {
            try {
                if (!inspectOutput || finishReason == null || finishReason.isBlank()) return;
                rawFinishReason = switch (finishReason) {
                    case "stop", "end_turn", "tool_calls", "length", "content_filter", "function_call" -> finishReason;
                    default -> "other";
                };
            } catch (RuntimeException diagnosticFailure) {
                inspectOutput = false;
            }
        }

        /** Inspect only a bounded suffix, apart from skipping trailing whitespace. */
        void observeText(String text) {
            try {
                if (!inspectOutput) return;
                textChars += text.length();
                int end = text.length();
                while (end > 0 && isOutputWhitespace(text.charAt(end - 1))) end--;
                if (end > 0) {
                    appendOutputTail(outputTail, pendingWhitespace, 0, pendingWhitespace.length());
                    pendingWhitespace.setLength(0);
                    appendOutputTail(outputTail, text, 0, end);
                }
                // Preserve trailing blanks until more text arrives, so internal blanks are never removed.
                appendOutputTail(pendingWhitespace, text, end, text.length());
            } catch (RuntimeException diagnosticFailure) {
                inspectOutput = false;
            }
        }

        boolean hasObservedTailMarker() {
            return inspectOutput && ("stop".equals(rawFinishReason) || "end_turn".equals(rawFinishReason))
                    && outputTail.toString().endsWith("...(content truncated)");
        }

        private static boolean isOutputWhitespace(char c) {
            return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f' || c == 0x0b;
        }

        private static void appendOutputTail(StringBuilder target, CharSequence text, int start, int end) {
            int size = end - start;
            if (size >= OUTPUT_TAIL_CHARS) {
                target.setLength(0);
                target.append(text, end - OUTPUT_TAIL_CHARS, end);
            } else {
                int excess = target.length() + size - OUTPUT_TAIL_CHARS;
                if (excess > 0) target.delete(0, excess);
                target.append(text, start, end);
            }
        }

        void recordIssue(String issue) {
            if (firstIssue == null) firstIssue = issue;
        }

        /** Normalized for logs: an absent first issue is reported as "none", not a literal null. */
        String firstIssueForLog() {
            return firstIssue == null ? "none" : firstIssue;
        }

        void observe(String line) {
            try {
                if (!line.startsWith("data:") || "data: [DONE]".equals(line)
                        || "data:[DONE]".equals(line)) return;
                boolean truncated = line.length() > MAX_FRAME_CHARS;
                String bounded = truncated ? line.substring(0, MAX_FRAME_CHARS) : line;
                lastFrame = bounded;
                lastFrameTruncated = truncated;
                if (!line.startsWith("data: ")) {
                    ignoredDataFrames++;
                    lastIgnoredFrame = bounded;
                    lastIgnoredFrameTruncated = truncated;
                }
            } catch (RuntimeException diagnosticFailure) {
                // Observing a frame must not change stream processing.
            }
        }

        String describe(ObjectMapper mapper, String line, boolean truncated) {
            if (line == null) return "none";
            boolean recognized = line.startsWith("data: ");
            String shape = recognized ? "standard" : "no_space";
            if (truncated) return shape + ":truncated";
            try {
                JsonNode chunk = mapper.readTree(line.substring(recognized ? 6 : 5));
                JsonNode choices = chunk.path("choices");
                String reason = choices.isArray() && !choices.isEmpty()
                        ? choices.get(0).path("finish_reason").asText("") : "";
                String safeReason = switch (reason) {
                    case "stop", "tool_calls", "length", "content_filter", "function_call" -> reason;
                    case "" -> "none";
                    default -> "other";
                };
                return shape + ":choices=" + (choices.isArray() ? choices.size() : -1)
                        + ",finish=" + safeReason + ",error=" + chunk.has("error");
            } catch (Exception parseFailure) {
                return shape + ":unparseable";
            }
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
        StreamTransportAttempt attempt = new StreamTransportAttempt(callContext.requestId());
        callback = attempt.callback(callback);
        Request request = new Request.Builder()
                .url(baseUrl + "/responses")
                .header("Authorization", "Bearer " + effectiveApiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), JSON_MEDIA))
                .build();
        String callId = callContext.requestId();
        Call call = attempt.newCall(httpClient, request);
        ResponsesStreamState streamState = new ResponsesStreamState();

        try (AutoCloseable ignored = LlmCallRegistration.register(activeCalls, callId, call,
                     callContext.cancellation(), Call::cancel);
             Response response = call.execute()) {
            attempt.response(response);
            if (!response.isSuccessful()) {
                handleErrorResponse(response, callback);
                return;
            }
            ResponseBody body = response.body();
            if (body == null) {
                callback.onError(attempt.interrupted("Empty Responses body", null));
                return;
            }
            BufferedSource source = attempt.source(body);
            while (!source.exhausted()) {
                String line = source.readUtf8LineStrict();
                if (line.isBlank() || line.startsWith(":")) continue;
                if (!line.startsWith("data:")) continue;
                attempt.frame();
                String json = line.substring(5).trim();
                if (json.isEmpty() || "[DONE]".equals(json)) continue;
                JsonNode event = objectMapper.readTree(json);
                if (event == null || !event.isObject()) {
                    throw attempt.interrupted("Invalid Responses SSE object", null);
                }
                processResponsesEvent(event, streamState, callback);
                if (streamState.terminal) return;
            }
            callback.onError(attempt.interrupted("ZENMUX_RESPONSES_INCOMPLETE_STREAM", null));
        } catch (IOException e) {
            if (call.isCanceled()) {
                callback.onError(new LlmApiException("LLM_CALL_CANCELLED", e, false));
            } else {
                callback.onError(attempt.transportFailure(e));
            }
        } catch (Exception e) {
            callback.onError(e instanceof LlmApiException
                    ? e : new LlmApiException("Failed to process model response", e, false));
        } finally {
            attempt.logFinished();
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
        if (isZenMuxClaude(model)) addZenMuxClaudeCacheBreakpoints(root);
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
        if (usage == null || !usage.isObject()
                || (!usage.hasNonNull("input_tokens") && !usage.hasNonNull("output_tokens"))) {
            return null;
        }
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
                    || "deepseek-v4.1-flash".equals(model)
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
        root.put("model", upstreamModelId(model));
        if (isBailianGlm(model)) {
            // 百炼托管 GLM 使用顶层参数；与智谱直连的 thinking 对象协议分别处理。
            // 同步/流式均强制最强推理，并保留工具续轮的历史思考。
            root.put("enable_thinking", true);
            root.put("reasoning_effort", "max");
            root.put("clear_thinking", false);
        }
        if (isKimiCodeModel(model)) {
            // 订阅 K3 / K2.8 Preview 统一使用最强推理；关闭思考会让 K3 改路由到 K2.8。
            // 放在共享请求构建处，保证流式对话与 chatSync 都显式启用 max。
            root.putObject("thinking").put("type", "enabled");
            root.put("reasoning_effort", "max");
        }
        if (isOpenRouter() && OpenRouterModels.capabilities(model) != null) {
            // The model mandates reasoning; strongest confirmed effort as of 2026-09-17.
            root.putObject("reasoning").put("effort", "max").put("exclude", false);
            root.putObject("provider").put("require_parameters", true);
        }
        // Moonshot 使用 max_completion_tokens；Kimi Code 订阅接口使用 max_tokens。
        String maxTokensKey = model.startsWith("kimi-") && !isKimiCodeModel(model)
                ? "max_completion_tokens" : "max_tokens";
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
                    if (!isOpenRouter() && !thinkingContent.isEmpty()) {
                        msgNode.put("reasoning_content", thinkingContent.toString());
                    }
                    if (isOpenRouter()) OpenRouterReasoning.replay(objectMapper, msgNode, blocks, model);
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
                if (isOpenRouter() && "assistant".equals(role))
                    OpenRouterReasoning.replay(objectMapper, msgNode, blocks, model);
                if (!isOpenRouter() && "assistant".equals(role) && !thinkingContent.isEmpty()) {
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

    private boolean isZenMuxClaude(String model) {
        return "zenmux".equalsIgnoreCase(providerName)
                && model != null && model.startsWith("anthropic/claude-");
    }

    /** Only mutates the outgoing JSON. All nonblank text stays in the same block form
     * across turns, so moving a cache boundary does not change the historical prefix.
     * Rolling history boundaries sit on real user text turns: marker positions on
     * tool/assistant turns are not reused by the gateway and would waste the slots. */
    private static void addZenMuxClaudeCacheBreakpoints(ObjectNode request) {
        JsonNode tools = request.path("tools");
        for (JsonNode tool : tools) {
            if (tool instanceof ObjectNode object) object.remove("cache_control");
        }
        if (!tools.isEmpty() && tools.get(tools.size() - 1) instanceof ObjectNode lastTool) {
            lastTool.putObject("cache_control").put("type", "ephemeral");
        }
        List<ObjectNode> userBoundaries = new java.util.ArrayList<>();
        ObjectNode system = null;
        for (JsonNode node : request.path("messages")) {
            if (!(node instanceof ObjectNode message)) continue;
            JsonNode content = message.path("content");
            if (content.isTextual() && !content.asText().isBlank()) {
                ObjectNode text = message.putArray("content").addObject();
                text.put("type", "text").put("text", content.asText());
                content = message.get("content");
            }
            ObjectNode boundary = null;
            if (content.isArray()) {
                for (JsonNode part : content) {
                    if (part instanceof ObjectNode text && "text".equals(text.path("type").asText())
                            && !text.path("text").asText().isBlank()) boundary = text;
                }
            }
            if (boundary == null) continue; // Keep empty tool results and image-only turns intact.
            String role = message.path("role").asText();
            if ("system".equals(role)) system = boundary;
            else if ("user".equals(role)) userBoundaries.add(boundary);
        }
        if (system != null) system.putObject("cache_control").put("type", "ephemeral");
        // One tools boundary + one system boundary + two rolling user boundaries <= 4.
        for (int i = Math.max(0, userBoundaries.size() - 2); i < userBoundaries.size(); i++) {
            userBoundaries.get(i).putObject("cache_control").put("type", "ephemeral");
        }
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
        // 百炼摘要调用与该模型流式请求使用相同的已验证推理参数。
        if ("deepseek-v4.1-flash".equals(model)) {
            requestBody.putObject("thinking").put("type", "enabled");
            requestBody.put("reasoning_effort", "max");
        }

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

    private void processChunk(String json, Map<Integer, ToolCallAccumulator> accumulators,
                              StreamChatCallback callback,
                              java.util.concurrent.atomic.AtomicBoolean sawFinishReason) {
        try {
            processChunk(json, accumulators, callback, sawFinishReason, null, null);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalidJson) {
            callback.onError(new LlmApiException("Failed to parse OpenAI chunk", invalidJson, false));
        }
    }

    private void processChunk(String json,
                              Map<Integer, ToolCallAccumulator> accumulators,
                              StreamChatCallback callback,
                              java.util.concurrent.atomic.AtomicBoolean sawFinishReason,
                              StreamDiagnostics diagnostics,
                              String model) throws com.fasterxml.jackson.core.JsonProcessingException {
        try {
            JsonNode chunk = objectMapper.readTree(json);
            if (chunk == null || !chunk.isObject()) {
                throw new com.fasterxml.jackson.core.JsonParseException(null, "Invalid OpenAI SSE object");
            }
            JsonNode choices = chunk.get("choices");

            if (choices == null || choices.isEmpty()) {
                if (diagnostics != null && chunk.has("error")) diagnostics.recordIssue("provider_error_frame");
                // usage-only chunk
                if (chunk.has("usage")) {
                    Usage usage = parseUsage(chunk.get("usage"), model);
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
            if (diagnostics != null) diagnostics.observeFinishReason(rawFinishReason);

            if (delta != null) {
                // DeepSeek reasoning_content 思考增量
                if (!isOpenRouter() && delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()) {
                    String thinking = delta.get("reasoning_content").asText();
                    if (!thinking.isEmpty()) {
                        callback.onEvent(new LlmStreamEvent.ThinkingDelta(thinking));
                    }
                }

                // 文本增量
                if (delta.has("content") && !delta.get("content").isNull()) {
                    String text = delta.get("content").asText();
                    if (!text.isEmpty()) {
                        if (diagnostics != null) diagnostics.observeText(text);
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
                            if (!id.isBlank() && acc.id != null && !acc.id.equals(id)) {
                                throw invalidToolCallStream(index, "conflicting id");
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
                                if (!name.isBlank() && acc.name != null
                                        && !acc.name.equals(name)) {
                                    throw invalidToolCallStream(index, "conflicting name");
                                }
                                if (!name.isBlank() && acc.name == null) acc.name = name;
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
            if (finishReason != null && !finishReason.isBlank()) {
                sawFinishReason.set(true);
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
                Usage usage = parseUsage(chunk.get("usage"), model);
                callback.onEvent(new LlmStreamEvent.MessageDelta(usage, finishReason));
            }

        } catch (LlmApiException e) {
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalidJson) {
            if (diagnostics != null) diagnostics.recordIssue("chunk_processing_" + invalidJson.getClass().getSimpleName());
            throw invalidJson;
        } catch (Exception e) {
            if (diagnostics != null) diagnostics.recordIssue("chunk_processing_" + e.getClass().getSimpleName());
            callback.onError(new LlmApiException(
                    "Failed to parse OpenAI chunk", e, false));
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

        applyStreamingErrorKeyCooldown(response, code, errorType);
        callback.onError(new LlmApiException(errorMsg, retryable, code));
    }

    /** HTTP errors and Claude SSE error frames share the existing key-failover policy. */
    private void applyStreamingErrorKeyCooldown(Response response, int code, String errorType) {
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

    private Usage parseUsage(JsonNode usageNode, String model) {
        if (usageNode == null || !usageNode.isObject()
                || (!usageNode.hasNonNull("prompt_tokens") && !usageNode.hasNonNull("completion_tokens"))) {
            return null;
        }
        int prompt = usageNode.path("prompt_tokens").asInt(0);
        int completion = usageNode.path("completion_tokens").asInt(0);
        if (isZenMuxClaude(model)) {
            // Anthropic 语义：prompt_tokens 只含未缓存输入，缓存读写是并列明细；
            // inputTokens 上报完整输入，缓存读写作为其中明细，避免总量漏计。
            JsonNode details = usageNode.path("prompt_tokens_details");
            int cached = details.path("cached_tokens").asInt(0);
            int created = details.path("cacheCreationInputTokens").asInt(
                    details.path("cache_creation_input_tokens").asInt(0));
            long fullInput = (long) prompt + cached + created;
            return new Usage((int) Math.min(Integer.MAX_VALUE, fullInput), completion, cached, created);
        }
        return new Usage(
                prompt,
                completion,
                0, // cache_read — OpenAI 标准 API 无此字段
                0  // cache_write
        );
    }

    /** ZenMux Chat Completions reasoning continuation. Never stores a public-message snapshot. */
    static final class ChatReasoningContinuation {
        private static final String TYPE = "zenmux_claude_reasoning";
        private static final Set<String> DELTA_FIELDS = Set.of("text", "signature", "summary", "data");
        // Only these configured models have a documented preserved-thinking reset contract.
        private static final Set<String> RESET_MODELS = Set.of(
                "anthropic/claude-fable-5.1", "anthropic/claude-opus-5.5",
                "anthropic/claude-sonnet-5.5", "anthropic/claude-haiku-5.5");
        private final ObjectMapper mapper;
        private final String prefix;
        private final String parent;
        private final boolean hasHistory;
        private final Map<Integer, Detail> details = new TreeMap<>();
        private final Map<Integer, Tool> tools = new HashMap<>();
        private final List<Tool> startedTools = new ArrayList<>();
        private final List<String> textParts = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private String finishReason;
        private boolean completed;

        private ChatReasoningContinuation(ObjectMapper mapper, String prefix, String parent, boolean hasHistory) {
            this.mapper = mapper;
            this.prefix = prefix;
            this.parent = parent;
            this.hasHistory = hasHistory;
        }

        /** Called after ordinary wire construction and cache markers, using the selected HTTP key. */
        static ChatReasoningContinuation prepare(ObjectMapper mapper, ObjectNode request,
                                                 List<Map<String, Object>> sourceMessages, String model,
                                                 String endpoint, String credential) {
            List<JsonNode> sources = new ArrayList<>();
            for (Map<String, Object> source : sourceMessages) {
                if ("assistant".equals(source.get("role"))) sources.add(mapper.valueToTree(source));
            }
            long assistantCount = 0;
            for (JsonNode message : request.path("messages")) {
                if ("assistant".equals(message.path("role").asText())) assistantCount++;
            }
            boolean hasHistory = sources.stream().anyMatch(source -> hasStoredReasoning(source, false));
            Set<String> lineage = sources.size() == assistantCount ? lineage(sources) : Set.of();
            boolean reset = hasHistory && lineage.isEmpty();
            boolean discardsPayload = sources.stream().anyMatch(source -> {
                JsonNode state = state(source, null);
                return hasStoredReasoning(source, true) && (lineage.isEmpty()
                        || state != null && lineage.contains(state.path("replay").path("id").asText()));
            });
            Prefix hash = basePrefix(request, model, endpoint, credential);
            String parent = "";
            int assistantIndex = 0;
            for (JsonNode message : request.path("messages")) {
                if (!(message instanceof ObjectNode wire)) continue;
                if ("assistant".equals(wire.path("role").asText())) {
                    JsonNode source = assistantIndex < sources.size() ? sources.get(assistantIndex) : null;
                    assistantIndex++;
                    JsonNode stored = state(source, null);
                    JsonNode state = state(source, model);
                    // Display-only thinking is not a signed continuation.
                    wire.remove(List.of("reasoning", "reasoning_content", "reasoning_details"));
                    if (stored != null && lineage.contains(stored.path("replay").path("id").asText())) {
                        if (binding(state)
                                && hash.value().equals(state.path("replay").path("prefix").asText())
                                && parent.equals(state.path("replay").path("parent").asText())
                                && publicHash(mapper, wire).equals(state.path("replay").path("public").asText())) {
                            ArrayNode saved = (ArrayNode) state.path("reasoning_details");
                            if (!saved.isEmpty()) wire.set("reasoning_details", saved.deepCopy());
                            parent = state.path("replay").path("id").asText();
                        } else {
                            reset = true;
                        }
                    }
                }
                hash.add(canonical(prefixMessage(mapper, wire)).toString());
            }
            if (reset) {
                // A changed image/history can invalidate a later tool turn while earlier blocks
                // still match. Never send that partial chain: reset every reasoning block together.
                // Recompute the next binding from the exact reset request, including public tool
                // calls/results, so a successful response starts a new lineage that can be replayed.
                hash = resetPrefix(mapper, request, model, endpoint, credential);
                parent = "";
                if (discardsPayload && !RESET_MODELS.contains(model)) {
                    throw invalid("reasoning reset is not supported for model " + model);
                }
            }
            return new ChatReasoningContinuation(mapper, hash.value(), parent, hasHistory);
        }

        /** A final budget fallback: discard only optional reasoning, then validate the full wire again. */
        static ChatReasoningContinuation prepareReset(ObjectMapper mapper, ObjectNode request,
                                                      List<Map<String, Object>> sourceMessages, String model,
                                                      String endpoint, String credential) {
            if (!RESET_MODELS.contains(model)) {
                throw invalid("reasoning reset is not supported for model " + model);
            }
            boolean hasHistory = sourceMessages.stream()
                    .filter(source -> "assistant".equals(source.get("role")))
                    .anyMatch(source -> hasStoredReasoning(mapper.valueToTree(source), false));
            for (JsonNode message : request.path("messages")) {
                if ("assistant".equals(message.path("role").asText())
                        && (message.path("reasoning_details").isArray() && !message.path("reasoning_details").isEmpty()
                        || !message.path("reasoning").asText("").isEmpty()
                        || !message.path("reasoning_content").asText("").isEmpty())) {
                    hasHistory = true;
                }
            }
            Prefix hash = resetPrefix(mapper, request, model, endpoint, credential);
            return new ChatReasoningContinuation(mapper, hash.value(), "", hasHistory);
        }

        private static Prefix resetPrefix(ObjectMapper mapper, ObjectNode request, String model,
                                           String endpoint, String credential) {
            Prefix hash = basePrefix(request, model, endpoint, credential);
            for (JsonNode message : request.path("messages")) {
                if (!(message instanceof ObjectNode wire)) continue;
                if ("assistant".equals(wire.path("role").asText())) {
                    wire.remove(List.of("reasoning", "reasoning_content", "reasoning_details"));
                }
                hash.add(canonical(prefixMessage(mapper, wire)).toString());
            }
            return hash;
        }

        private static Prefix basePrefix(ObjectNode request, String model, String endpoint, String credential) {
            Prefix hash = new Prefix();
            hash.add("zenmux-claude-chat-replay-v1");
            hash.add(model);
            hash.add(endpoint);
            hash.add(credential == null ? "" : credential);
            for (JsonNode tool : request.path("tools")) hash.add(canonical(withoutMarker(tool)).toString());
            return hash;
        }

        /** Receives each complete JSON chunk once. Other text/tool events remain the provider's job. */
        void accept(JsonNode chunk, StreamChatCallback callback) {
            if (completed) throw invalid("chunk after completion");
            JsonNode choice = chunk.path("choices").path(0);
            JsonNode delta = choice.path("delta");
            StringBuilder visible = new StringBuilder();
            JsonNode fragments = delta.path("reasoning_details");
            if (!fragments.isMissingNode() && !fragments.isNull() && !fragments.isArray()) {
                throw invalid("reasoning_details must be an array");
            }
            for (JsonNode fragment : fragments) {
                if (!fragment.isObject()) throw invalid("reasoning detail must be an object");
                int index = detailIndex(fragment);
                Detail detail = details.computeIfAbsent(index, ignored -> new Detail(mapper));
                detail.append(fragment);
                if ("reasoning.text".equals(detail.fields.path("type").asText())) visible.append(fragment.path("text").asText(""));
                if ("reasoning.summary".equals(detail.fields.path("type").asText())) visible.append(fragment.path("summary").asText(""));
            }
            if (!fragments.isEmpty()) callback.onEvent(new LlmStreamEvent.ProviderProgress());
            String thinking = delta.hasNonNull("reasoning") ? delta.path("reasoning").asText()
                    : delta.hasNonNull("reasoning_content") ? delta.path("reasoning_content").asText() : visible.toString();
            if (!thinking.isEmpty()) callback.onEvent(new LlmStreamEvent.ThinkingDelta(thinking));
            if (delta.hasNonNull("content")) text.append(delta.path("content").asText());
            for (JsonNode fragment : delta.path("tool_calls")) {
                int index = fragment.path("index").asInt(-1);
                if (index < 0) throw invalid("tool index is missing");
                Tool tool = tools.computeIfAbsent(index, ignored -> new Tool());
                tool.id = identity(tool.id, fragment.get("id"));
                tool.name = identity(tool.name, fragment.path("function").get("name"));
                if (!tool.started && !tool.id.isEmpty() && !tool.name.isEmpty()) {
                    flushText();
                    tool.started = true;
                    startedTools.add(tool);
                }
                if (fragment.path("function").hasNonNull("arguments")) {
                    tool.arguments.append(fragment.path("function").path("arguments").asText());
                }
            }
            if (choice.hasNonNull("finish_reason") && !choice.path("finish_reason").asText().isBlank()) {
                finishReason = choice.path("finish_reason").asText();
                flushText();
            } else if ((chunk.path("choices").isMissingNode() || chunk.path("choices").isEmpty()) && chunk.has("usage")) {
                // StreamCollector flushes visible text for every MessageDelta, including usage-only chunks.
                flushText();
            }
        }

        /** Call only after the provider has accepted a normal terminal marker/EOF, never on failure. */
        void complete(StreamChatCallback callback) {
            if (completed) throw invalid("duplicate completion");
            if (finishReason == null) throw invalid("missing finish_reason");
            completed = true;
            if (details.isEmpty() && !hasHistory) return;
            // A tool call cut off by the output limit must surface its real stop reason
            // (the collector reports the same code) instead of an internal state error.
            if (!tools.isEmpty() && ("length".equals(finishReason) || "max_tokens".equals(finishReason))) {
                throw new LlmApiException("TRUNCATED_TOOL_CALLS: " + finishReason, false, 0,
                        "TRUNCATED_TOOL_CALLS", 0);
            }
            flushText();
            ObjectNode publicMessage = mapper.createObjectNode().put("content", String.join("\n", textParts));
            if (!tools.isEmpty()) {
                ArrayNode calls = publicMessage.putArray("tool_calls");
                if (startedTools.size() != tools.size()) throw invalid("incomplete tool identity");
                for (Tool tool : startedTools) {
                    if (!tool.started) throw invalid("incomplete tool identity");
                    ObjectNode call = calls.addObject().put("id", tool.id).put("type", "function");
                    call.putObject("function").put("name", tool.name).put("arguments", tool.arguments.toString());
                }
            }
            ObjectNode state = mapper.createObjectNode().put("type", TYPE);
            ArrayNode saved = state.putArray("reasoning_details");
            for (Detail detail : details.values()) saved.add(detail.finish());
            state.putObject("replay").put("version", 1).put("id", UUID.randomUUID().toString())
                    .put("parent", parent).put("prefix", prefix).put("public", publicHash(mapper, publicMessage));
            // An empty details array is a small lineage barrier: dropped signatures cannot
            // revive after a successful response that happened to contain no reasoning.
            callback.onEvent(new LlmStreamEvent.ProviderResponseState(List.of(state)));
        }

        private void flushText() {
            if (!text.isEmpty()) {
                textParts.add(text.toString());
                text.setLength(0);
            }
        }

        private static int detailIndex(JsonNode fragment) {
            JsonNode index = fragment.get("index");
            if (index == null || index.isNull()) return 0; // Single-block gateways omit index.
            try {
                int value = Integer.parseInt(index.asText());
                if (value >= 0) return value;
            } catch (NumberFormatException ignored) { }
            throw invalid("invalid reasoning index");
        }

        private static String identity(String current, JsonNode incoming) {
            if (incoming == null || incoming.isNull() || incoming.asText().isEmpty()) return current;
            if (!current.isEmpty() && !current.equals(incoming.asText())) throw invalid("conflicting tool identity");
            return incoming.asText();
        }

        private static final class Tool {
            String id = "";
            String name = "";
            boolean started;
            final StringBuilder arguments = new StringBuilder();
        }

        private static final class Detail {
            final ObjectNode fields;
            final Map<String, StringBuilder> deltas = new LinkedHashMap<>();
            Detail(ObjectMapper mapper) { fields = mapper.createObjectNode(); }
            void append(JsonNode fragment) {
                fragment.fields().forEachRemaining(entry -> {
                    String name = entry.getKey();
                    JsonNode value = entry.getValue();
                    if (DELTA_FIELDS.contains(name)) {
                        if (!value.isTextual()) throw invalid("non-text reasoning delta: " + name);
                        deltas.computeIfAbsent(name, ignored -> new StringBuilder()).append(value.asText());
                    } else if (!fields.has(name)) {
                        fields.set(name, value.deepCopy());
                    } else if (!fields.get(name).equals(value)) {
                        // Unknown fields are opaque: guessing replacement/concatenation can corrupt signatures.
                        throw invalid("conflicting reasoning metadata: " + name);
                    }
                });
            }
            ObjectNode finish() {
                deltas.forEach((field, value) -> fields.put(field, value.toString()));
                if (!fields.path("type").isTextual() || fields.path("type").asText().isBlank()) {
                    throw invalid("missing reasoning type");
                }
                if ("reasoning.text".equals(fields.path("type").asText())
                        && fields.path("signature").asText().isBlank()) throw invalid("missing reasoning signature");
                return fields;
            }
        }

        private static JsonNode state(JsonNode source, String model) {
            if (source == null) return null;
            JsonNode found = null;
            for (JsonNode block : source.path("content")) {
                if (!"provider_response_state".equals(block.path("type").asText())
                        || (model != null && (!"zenmux".equalsIgnoreCase(block.path("provider").asText())
                        || !model.equals(block.path("model").asText())))) continue;
                for (JsonNode item : block.path("output")) {
                    if (TYPE.equals(item.path("type").asText())) {
                        if (found != null) return null;
                        found = item;
                    }
                }
            }
            return found;
        }

        private static boolean binding(JsonNode state) {
            if (state == null || !state.path("reasoning_details").isArray()) return false;
            JsonNode replay = state.path("replay");
            return replay.path("version").asInt() == 1 && replay.path("id").isTextual()
                    && !replay.path("id").asText().isEmpty() && replay.path("parent").isTextual()
                    && replay.path("prefix").asText().matches("[0-9a-f]{64}")
                    && replay.path("public").asText().matches("[0-9a-f]{64}");
        }

        private static boolean hasStoredReasoning(JsonNode source, boolean requirePayload) {
            for (JsonNode block : source.path("content")) {
                if (!"provider_response_state".equals(block.path("type").asText())) continue;
                for (JsonNode item : block.path("output")) {
                    if (!TYPE.equals(item.path("type").asText())) continue;
                    JsonNode details = item.path("reasoning_details");
                    if (!requirePayload || !details.isMissingNode() && !details.isNull()
                            && (!details.isArray() || !details.isEmpty())) return true;
                }
            }
            return false;
        }

        private static Set<String> lineage(List<JsonNode> sources) {
            Map<String, JsonNode> byId = new HashMap<>();
            Set<String> duplicates = new HashSet<>();
            JsonNode latest = null;
            for (JsonNode source : sources) {
                latest = state(source, null);
                if (binding(latest)) {
                    String id = latest.path("replay").path("id").asText();
                    if (byId.put(id, latest) != null) duplicates.add(id);
                }
            }
            Set<String> result = new HashSet<>();
            while (latest != null) {
                if (!binding(latest)) return Set.of();
                String id = latest.path("replay").path("id").asText();
                if (duplicates.contains(id) || !result.add(id)) return Set.of();
                String parent = latest.path("replay").path("parent").asText();
                if (parent.isEmpty()) return result;
                latest = byId.get(parent);
                if (latest == null) return Set.of();
            }
            return result;
        }

        private static String publicHash(ObjectMapper mapper, JsonNode message) {
            ObjectNode visible = mapper.createObjectNode().put("content", publicText(message.path("content")));
            ArrayNode calls = visible.putArray("tool_calls");
            for (JsonNode call : message.path("tool_calls")) {
                ObjectNode copy = calls.addObject().put("id", call.path("id").asText())
                        .put("name", call.path("function").path("name").asText());
                copy.set("input", arguments(mapper, call.path("function").path("arguments").asText()));
            }
            Prefix digest = new Prefix();
            digest.add(canonical(visible).toString());
            return digest.value();
        }

        private static String publicText(JsonNode content) {
            if (content.isTextual()) return content.asText();
            StringBuilder text = new StringBuilder();
            for (JsonNode part : content) if ("text".equals(part.path("type").asText())) text.append(part.path("text").asText());
            return text.toString();
        }

        private static JsonNode prefixMessage(ObjectMapper mapper, JsonNode message) {
            ObjectNode copy = (ObjectNode) withoutMarker(message);
            JsonNode content = copy.path("content");
            if (content.isArray()) {
                ArrayNode clean = mapper.createArrayNode();
                for (JsonNode part : content) clean.add(withoutMarker(part));
                copy.set("content", clean);
            } else if (content.isTextual() && !content.asText().isBlank()) {
                copy.putArray("content").addObject().put("type", "text").put("text", content.asText());
            }
            for (JsonNode call : copy.path("tool_calls")) {
                if (call.path("function") instanceof ObjectNode function && function.has("arguments")) {
                    function.set("arguments", arguments(mapper, function.path("arguments").asText()));
                }
            }
            return copy;
        }

        private static JsonNode arguments(ObjectMapper mapper, String json) {
            try {
                JsonNode input = mapper.readTree(json.isBlank() ? "{}" : json);
                if (input != null && input.isObject()) return input;
            } catch (Exception ignored) { }
            // Keep the collector's accurate validation reason instead of masking it as
            // an internal reasoning-state error; the caller still refuses to execute or resend.
            throw new LlmApiException("INVALID_TOOL_INPUT_JSON", false, 0, "INVALID_TOOL_INPUT_JSON", 0);
        }

        private static JsonNode withoutMarker(JsonNode node) {
            JsonNode copy = node.deepCopy();
            if (copy instanceof ObjectNode object) object.remove("cache_control");
            return copy;
        }

        private static JsonNode canonical(JsonNode value) {
            if (value.isObject()) {
                ObjectNode sorted = ((ObjectNode) value).objectNode();
                Map<String, JsonNode> fields = new TreeMap<>();
                value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
                fields.forEach((key, child) -> sorted.set(key, canonical(child)));
                return sorted;
            }
            if (value.isArray()) {
                ArrayNode result = ((ArrayNode) value).arrayNode();
                value.forEach(child -> result.add(canonical(child)));
                return result;
            }
            return value;
        }

        private static final class Prefix {
            final MessageDigest digest;
            byte[] current = new byte[0];
            Prefix() {
                try { digest = MessageDigest.getInstance("SHA-256"); }
                catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            }
            void add(String value) {
                digest.update(current);
                current = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            }
            String value() { return HexFormat.of().formatHex(current); }
        }

        private static LlmApiException invalid(String detail) {
            return new LlmApiException("INVALID_ZENMUX_CLAUDE_REASONING: " + detail, false);
        }
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
