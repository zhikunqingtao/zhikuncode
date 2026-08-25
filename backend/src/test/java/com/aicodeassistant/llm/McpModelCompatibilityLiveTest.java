package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.aicodeassistant.mcp.schema.SchemaCompressor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Live compatibility matrix for OpenAI-compatible models and the Zhipu WebSearch
 * MCP function shape used by ZhikunCode.
 *
 * <p>The test exposes the target Zhipu function plus a minimal reproduction of
 * a non-standard MCP schema to each model. This separates model/provider
 * function-calling compatibility from MCP transport and verifies the schema
 * normalization needed by strict providers. It also sends a synthetic tool
 * result back to the model, exercising the same assistant/tool continuation
 * serialization used by {@link OpenAiCompatibleProvider}.
 *
 * <p>Enable explicitly with {@code RUN_MCP_MODEL_COMPATIBILITY_LIVE_TESTS=true}.
 * To limit a diagnostic run, set {@code MCP_COMPAT_MODELS} to a comma-separated
 * list such as {@code kimi-k3,qwen3.8-max}. No credential value is logged.
 */
@EnabledIfEnvironmentVariable(
        named = "RUN_MCP_MODEL_COMPATIBILITY_LIVE_TESTS", matches = "(?i)true")
class McpModelCompatibilityLiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOOL_NAME = "mcp__zhipu-websearch__webSearchPro";

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void configuredModelsCanCallAndConsumeZhipuMcpFunction() {
        Set<String> selectedModels = csv(System.getenv("MCP_COMPAT_MODELS"), "").stream()
                .collect(Collectors.toSet());
        List<ModelEndpoint> endpoints = configuredEndpoints().stream()
                .filter(endpoint -> selectedModels.isEmpty()
                        || selectedModels.contains(endpoint.model()))
                .toList();
        if (endpoints.isEmpty()) {
            fail("No configured provider/model matched MCP_COMPAT_MODELS");
        }

        List<String> failures = new ArrayList<>();
        for (ModelEndpoint endpoint : endpoints) {
            try {
                runRoundTrip(endpoint);
                System.out.printf("MCP_MODEL_MATRIX provider=%s model=%s result=PASS%n",
                        endpoint.provider(), endpoint.model());
            } catch (Throwable error) {
                String safeMessage = error.getMessage() == null
                        ? error.getClass().getSimpleName()
                        : error.getClass().getSimpleName() + ": " + error.getMessage();
                failures.add(endpoint.provider() + "/" + endpoint.model() + " -> " + safeMessage);
                System.out.printf("MCP_MODEL_MATRIX provider=%s model=%s result=FAIL error=%s%n",
                        endpoint.provider(), endpoint.model(), singleLine(safeMessage));
            }
        }

        if (!failures.isEmpty()) {
            fail("MCP model compatibility failures:\n" + String.join("\n", failures));
        }
    }

    private static void runRoundTrip(ModelEndpoint endpoint) throws Exception {
        LlmHttpProperties http = new LlmHttpProperties(
                new LlmHttpProperties.PoolProperties(2, 30), 20, 30, true);
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                endpoint.provider(), MAPPER, http,
                new ApiKeyRotationManager(endpoint.apiKey()), endpoint.apiKey(),
                endpoint.baseUrl(), endpoint.model(), List.of(endpoint.model()));

        Capture first = new Capture();
        provider.streamChat(
                endpoint.model(),
                List.of(Map.of(
                        "role", "user",
                        "content", "必须调用当前提供的唯一搜索工具，搜索‘ZhikunCode 项目’，不要自行回答。")),
                "You are testing function calling. You must call the Zhipu web-search function exactly once.",
                compatibilityToolDefinitions(),
                outputBudget(endpoint.model()),
                new ThinkingConfig.Disabled(),
                LlmCallContext.unscoped(),
                first);
        first.requireCompleted("first turn");
        if (first.toolId == null || first.toolName == null) {
            throw new AssertionError("first turn returned no tool call; stop=" + first.stopReason
                    + ", textLength=" + first.text.length()
                    + ", thinkingLength=" + first.thinking.length());
        }
        if (!TOOL_NAME.equals(first.toolName)) {
            throw new AssertionError("unexpected tool name: " + first.toolName);
        }
        JsonNode arguments = MAPPER.readTree(first.arguments.toString());
        if (!arguments.hasNonNull("search_query")
                || arguments.path("search_query").asText().isBlank()) {
            throw new AssertionError("tool arguments have no search_query: " + arguments);
        }

        List<Map<String, Object>> assistantBlocks = new ArrayList<>();
        if (!first.thinking.isEmpty()) {
            assistantBlocks.add(Map.of("type", "thinking", "thinking", first.thinking.toString()));
        }
        if (!first.text.isEmpty()) {
            assistantBlocks.add(Map.of("type", "text", "text", first.text.toString()));
        }
        assistantBlocks.add(Map.of(
                "type", "tool_use",
                "id", first.toolId,
                "name", first.toolName,
                "input", arguments));

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content",
                        "必须调用当前提供的唯一搜索工具，搜索‘ZhikunCode 项目’，不要自行回答。"),
                Map.of("role", "assistant", "content", assistantBlocks),
                Map.of("role", "user", "content", List.of(Map.of(
                        "type", "tool_result",
                        "tool_use_id", first.toolId,
                        "content", "{\"results\":[{\"title\":\"ZhikunCode\",\"content\":\"AI coding assistant project\"}]}"))));

        Capture second = new Capture();
        provider.streamChat(
                endpoint.model(), messages,
                "You are testing function calling. Summarize the supplied tool result in one sentence.",
                compatibilityToolDefinitions(),
                outputBudget(endpoint.model()),
                new ThinkingConfig.Disabled(),
                LlmCallContext.unscoped(),
                second);
        second.requireCompleted("tool-result continuation");
        if (second.text.isEmpty() && second.toolId == null) {
            throw new AssertionError("continuation returned neither text nor tool call; stop="
                    + second.stopReason + ", thinkingLength=" + second.thinking.length());
        }
    }

    private static Map<String, Object> zhipuToolDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("search_query", Map.of(
                "type", "string",
                "description", "需要进行搜索的内容，建议搜索 query 不超过 70 个字符"));
        properties.put("content_size", Map.of(
                "type", "string",
                "enum", List.of("medium", "high")));
        properties.put("count", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", 50));
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", TOOL_NAME,
                        "description", "网络搜索Pro服务，搜索网络信息并返回网页标题、URL和摘要。",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", properties,
                                "required", List.of("search_query"))));
    }

    /**
     * Includes a minimal reproduction of the WanVideo schema that caused Moonshot
     * to reject the entire tool catalog ({@code type:"bool"}). Production MCP
     * adapters use the same SchemaCompressor normalization path.
     */
    private static List<Map<String, Object>> compatibilityToolDefinitions() {
        Map<String, Object> rawWanSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "prompt", Map.of("type", "string"),
                        "prompt_extend", Map.of("type", "bool")),
                "required", List.of("prompt"));
        Map<String, Object> normalizedWanSchema = new SchemaCompressor().compress(rawWanSchema);
        Map<String, Object> wanTool = Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "mcp__WanVideo__modelstudio_text_to_video_submit_task",
                        "description", "Submit a text-to-video task.",
                        "parameters", normalizedWanSchema));
        return List.of(zhipuToolDefinition(), wanTool);
    }

    private static int outputBudget(String model) {
        return model.startsWith("kimi-") ? 16_384 : 4_096;
    }

    private static List<ModelEndpoint> configuredEndpoints() {
        List<ModelEndpoint> endpoints = new ArrayList<>();
        addProvider(endpoints, "dashscope-token-plan",
                "LLM_PROVIDER_DASHSCOPE_TOKEN_PLAN_API_KEY",
                env("LLM_PROVIDER_DASHSCOPE_TOKEN_PLAN_BASE_URL",
                        "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"),
                "LLM_PROVIDER_DASHSCOPE_TOKEN_PLAN_MODELS",
                "qwen3.8-max,deepseek-v4-pro-0813,deepseek-v4-flash-0731");
        addProvider(endpoints, "deepseek", "LLM_PROVIDER_DEEPSEEK_API_KEY",
                env("LLM_PROVIDER_DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1"),
                "LLM_PROVIDER_DEEPSEEK_MODELS",
                "deepseek-v4-pro,deepseek-v4-flash,deepseek-v4-flash-vision-exp");
        addProvider(endpoints, "moonshot", "LLM_PROVIDER_MOONSHOT_API_KEY",
                env("LLM_PROVIDER_MOONSHOT_BASE_URL", "https://api.moonshot.cn/v1"),
                "LLM_PROVIDER_MOONSHOT_MODELS", "kimi-k3,kimi-k2.7-code,moonshot-v1-128k");
        addProvider(endpoints, "zhipu", "LLM_PROVIDER_ZHIPU_API_KEY",
                env("LLM_PROVIDER_ZHIPU_BASE_URL", "https://open.bigmodel.cn/api/paas/v4"),
                "LLM_PROVIDER_ZHIPU_MODELS", "glm-5.3,glm-5v-turbo");
        addProvider(endpoints, "minimax", "LLM_PROVIDER_MINIMAX_API_KEY",
                env("LLM_PROVIDER_MINIMAX_BASE_URL", "https://api.minimax.chat/v1"),
                "LLM_PROVIDER_MINIMAX_MODELS", "MiniMax-M3");
        addProvider(endpoints, "zenmux", "LLM_PROVIDER_ZENMUX_API_KEY",
                env("LLM_PROVIDER_ZENMUX_BASE_URL", "https://zenmux.ai/api/v1"),
                "LLM_PROVIDER_ZENMUX_MODELS",
                "anthropic/claude-opus-4.8,anthropic/claude-fable-5,openai/gpt-5.6-sol,google/gemini-3.5-flash");
        addProvider(endpoints, "dashscope", "LLM_PROVIDER_DASHSCOPE_API_KEY",
                env("LLM_PROVIDER_DASHSCOPE_BASE_URL",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1"),
                "LLM_PROVIDER_DASHSCOPE_MODELS", "qwen3.7-max,qwen3.7-plus");
        return endpoints;
    }

    private static void addProvider(
            List<ModelEndpoint> endpoints,
            String provider,
            String apiKeyVariable,
            String baseUrl,
            String modelsVariable,
            String defaultModels) {
        String apiKey = System.getenv(apiKeyVariable);
        if (apiKey == null || apiKey.isBlank()) return;
        for (String model : csv(System.getenv(modelsVariable), defaultModels)) {
            endpoints.add(new ModelEndpoint(provider, apiKey, baseUrl, model));
        }
    }

    private static List<String> csv(String raw, String fallback) {
        String value = raw == null || raw.isBlank() ? fallback : raw;
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String singleLine(String value) {
        return value.replaceAll("\\s+", " ");
    }

    private record ModelEndpoint(String provider, String apiKey, String baseUrl, String model) {}

    private static final class Capture implements StreamChatCallback {
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
        private String toolId;
        private String toolName;
        private String stopReason;
        private Throwable error;
        private boolean completed;

        @Override
        public void onEvent(LlmStreamEvent event) {
            switch (event) {
                case LlmStreamEvent.TextDelta delta -> text.append(delta.text());
                case LlmStreamEvent.ThinkingDelta delta -> thinking.append(delta.thinking());
                case LlmStreamEvent.ToolUseStart start -> {
                    toolId = start.id();
                    toolName = start.name();
                }
                case LlmStreamEvent.ToolInputDelta delta -> arguments.append(delta.jsonDelta());
                case LlmStreamEvent.MessageDelta delta -> {
                    if (delta.stopReason() != null) stopReason = delta.stopReason();
                }
                default -> { }
            }
        }

        @Override
        public void onComplete() {
            completed = true;
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        private void requireCompleted(String stage) {
            if (error != null) {
                throw new AssertionError(stage + " failed: " + error.getMessage(), error);
            }
            if (!completed) {
                throw new AssertionError(stage + " did not complete");
            }
        }
    }
}
