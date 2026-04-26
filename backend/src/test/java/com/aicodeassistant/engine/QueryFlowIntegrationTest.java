package com.aicodeassistant.engine;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.engine.ContextCascade;
import com.aicodeassistant.history.FileHistoryService;
import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.llm.*;
import com.aicodeassistant.model.*;
import com.aicodeassistant.permission.AutoModeClassifier;
import com.aicodeassistant.permission.PermissionPipeline;
import com.aicodeassistant.permission.PermissionRuleMatcher;
import com.aicodeassistant.permission.PermissionRuleRepository;
import com.aicodeassistant.permission.PluginSettingsSource;
import com.aicodeassistant.permission.PolicySettingsSource;
import com.aicodeassistant.sandbox.SandboxManager;
import com.aicodeassistant.security.CommandBlacklistService;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.security.SensitiveDataFilter;
import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.bash.BashCommandClassifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * QueryEngine 集成测试 — 验证端到端消息流 + 工具调用循环 + 错误场景。
 * <p>
 * 使用 Mock LlmProvider 模拟 LLM 响应:
 * <ol>
 *   <li>纯文本流式应答 → 流式输出无丢失</li>
 *   <li>工具调用 → 权限 → 执行 → 结果返回 → LLM 再次应答</li>
 *   <li>LLM API 异常 → 错误处理</li>
 * </ol>
 */
class QueryFlowIntegrationTest {

    private LlmProviderRegistry providerRegistry;
    private PermissionPipeline permissionPipeline;
    private QueryEngine queryEngine;
    private ObjectMapper objectMapper;
    private RecordingHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        providerRegistry = new LlmProviderRegistry(List.of(), null);
        PermissionRuleRepository ruleRepo = new PermissionRuleRepository(
                        new PolicySettingsSource(objectMapper), new PluginSettingsSource());
        PermissionRuleMatcher ruleMatcher = new PermissionRuleMatcher();
        AutoModeClassifier autoModeClassifier = new AutoModeClassifier(providerRegistry);
        permissionPipeline = new PermissionPipeline(ruleMatcher, ruleRepo, autoModeClassifier,
                mock(HookService.class), mock(SandboxManager.class),
                mock(PathSecurityService.class), mock(BashCommandClassifier.class),
                mock(FeatureFlagService.class), mock(CommandBlacklistService.class));

        TokenCounter tokenCounter = new TokenCounter();
        CompactService compactService = new CompactService(tokenCounter, providerRegistry, null, null);
        ModelTierService modelTierService = new ModelTierService();
        ApiRetryService apiRetryService = new ApiRetryService(modelTierService);
        HookService hookService = new HookService(new HookRegistry(), null);
        SensitiveDataFilter sensitiveDataFilter = new SensitiveDataFilter();
        StreamingToolExecutor streamingToolExecutor = new StreamingToolExecutor(
                new ToolExecutionPipeline(hookService, objectMapper, permissionPipeline, ruleRepo, sensitiveDataFilter, null), new SimpleMeterRegistry());
        MessageNormalizer messageNormalizer = new MessageNormalizer();
        SnipService snipService = new SnipService();
        MicroCompactService microCompactService = new MicroCompactService(tokenCounter);

        ContextCascade contextCascade = mock(ContextCascade.class);
        when(contextCascade.executePreApiCascade(any(), anyString(), any())).thenAnswer(inv -> {
            List<Message> msgs = inv.getArgument(0);
            int tokens = tokenCounter.estimateTokens(msgs);
            return new ContextCascade.CascadeResult(msgs, tokens, tokens, false, 0, false, 0, false, 0, false, false, null);
        });

        queryEngine = new QueryEngine(
                providerRegistry, compactService, apiRetryService,
                permissionPipeline, ruleRepo, tokenCounter, objectMapper,
                streamingToolExecutor, messageNormalizer, hookService,
                snipService, microCompactService, null, null, modelTierService, mock(FileHistoryService.class), mock(ToolResultSummarizer.class),
                contextCascade, mock(CompactMetrics.class)
        );

        handler = new RecordingHandler();
    }

    // ═══════════════ 1. 纯文本流式应答 ═══════════════

    @Test
    @DisplayName("纯文本流式应答 — 流式输出无丢失")
    void textStreamResponse_shouldDeliverAllDeltas() {
        // 注册 Mock Provider: 返回 3 段文本增量
        providerRegistry.register(new MockLlmProvider(callback -> {
            callback.onEvent(new LlmStreamEvent.TextDelta("Hello"));
            callback.onEvent(new LlmStreamEvent.TextDelta(" World"));
            callback.onEvent(new LlmStreamEvent.TextDelta("!"));
            callback.onEvent(new LlmStreamEvent.MessageDelta(
                    new Usage(10, 5, 0, 0), "end_turn"));
            callback.onComplete();
        }));

        // 执行
        QueryConfig config = buildConfig();
        QueryLoopState state = new QueryLoopState(
                List.of(userMessage("Hi")), ToolUseContext.of("/tmp", "test-session"));

        QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

        // 验证: 结果成功
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.stopReason()).isEqualTo("end_turn");

        // 验证: 流式增量完整
        assertThat(handler.textDeltas).containsExactly("Hello", " World", "!");

        // 验证: 消息包含完整文本
        assertThat(result.messages()).hasSizeGreaterThanOrEqualTo(2);

        // 验证: usage 统计
        assertThat(result.totalUsage().inputTokens()).isEqualTo(10);
        assertThat(result.totalUsage().outputTokens()).isEqualTo(5);
    }

    // ═══════════════ 2. 工具调用循环 ═══════════════

    @Test
    @DisplayName("工具调用循环 — 调用工具 → 执行 → 结果回传 → LLM 再应答")
    void toolCallLoop_shouldCompleteFullCycle() {
        AtomicInteger callCount = new AtomicInteger(0);

        // 注册 Mock Provider: 第 1 轮返回 tool_use，第 2 轮返回文本
        providerRegistry.register(new MockLlmProvider(callback -> {
            int call = callCount.incrementAndGet();
            if (call == 1) {
                // 第 1 轮: LLM 请求调用 tool
                callback.onEvent(new LlmStreamEvent.ToolUseStart("tool-1", "EchoTool"));
                callback.onEvent(new LlmStreamEvent.ToolInputDelta("tool-1",
                        "{\"message\":\"test\"}"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(
                        new Usage(10, 5, 0, 0), "tool_use"));
                callback.onComplete();
            } else {
                // 第 2 轮: LLM 收到工具结果后返回文本
                callback.onEvent(new LlmStreamEvent.TextDelta("Tool result received."));
                callback.onEvent(new LlmStreamEvent.MessageDelta(
                        new Usage(20, 10, 0, 0), "end_turn"));
                callback.onComplete();
            }
        }));

        // 构建 config
        QueryConfig config = buildConfigWithTools(List.of(new EchoTool()));
        QueryLoopState state = new QueryLoopState(
                List.of(userMessage("call echo")), ToolUseContext.of("/tmp", "test-session"));

        QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

        // 验证: 成功完成
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.turnCount()).isEqualTo(2);

        // 验证: 工具被调用
        assertThat(handler.toolUseStarts).hasSize(1);
        assertThat(handler.toolUseStarts.getFirst()).isEqualTo("EchoTool");

        // 验证: 工具结果已注入消息
        assertThat(handler.toolResults).hasSize(1);

        // 验证: 最终文本
        assertThat(handler.textDeltas).contains("Tool result received.");
    }

    // ═══════════════ 3. LLM 异常错误处理 ═══════════════

    @Test
    @DisplayName("LLM API 异常 — 错误被捕获并传递到 handler")
    void llmApiException_shouldHandleGracefully() {
        // 注册 Mock Provider: 抛出异常
        providerRegistry.register(new MockLlmProvider(callback -> {
            throw new LlmApiException("API timeout", false, 504);
        }));

        QueryConfig config = buildConfig();
        QueryLoopState state = new QueryLoopState(
                List.of(userMessage("test")), ToolUseContext.of("/tmp", "test-session"));

        QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

        // 验证: 结果为错误
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("API timeout");
        assertThat(result.stopReason()).isEqualTo("error");

        // 验证: handler 收到错误事件
        assertThat(handler.errors).hasSize(1);
    }

    // ═══════════════ 4. 工具执行失败 ═══════════════

    @Test
    @DisplayName("工具执行失败 — 错误结果回传给 LLM，LLM 继续应答")
    void toolExecutionFailure_shouldReturnErrorToLlm() {
        AtomicInteger callCount = new AtomicInteger(0);

        providerRegistry.register(new MockLlmProvider(callback -> {
            int call = callCount.incrementAndGet();
            if (call == 1) {
                callback.onEvent(new LlmStreamEvent.ToolUseStart("tool-1", "FailTool"));
                callback.onEvent(new LlmStreamEvent.ToolInputDelta("tool-1", "{}"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(
                        new Usage(10, 5, 0, 0), "tool_use"));
                callback.onComplete();
            } else {
                callback.onEvent(new LlmStreamEvent.TextDelta("Tool failed, I'll try another approach."));
                callback.onEvent(new LlmStreamEvent.MessageDelta(
                        new Usage(15, 10, 0, 0), "end_turn"));
                callback.onComplete();
            }
        }));

        // FailTool: 执行时返回错误
        Tool failTool = new EchoTool() {
            @Override public String getName() { return "FailTool"; }
            @Override public ToolResult call(ToolInput input, ToolUseContext context) {
                return ToolResult.error("Simulated tool failure");
            }
        };

        QueryConfig config = buildConfigWithTools(List.of(failTool));
        QueryLoopState state = new QueryLoopState(
                List.of(userMessage("do something")), ToolUseContext.of("/tmp", "test-session"));

        QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

        // 工具失败但整体流程应继续
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.turnCount()).isEqualTo(2);
        assertThat(handler.textDeltas).contains("Tool failed, I'll try another approach.");
    }

    // ═══════════════ 辅助: Mock LlmProvider ═══════════════

    @FunctionalInterface
    interface StreamBehavior {
        void perform(StreamChatCallback callback);
    }

    static class MockLlmProvider implements LlmProvider {
        private final StreamBehavior behavior;

        MockLlmProvider(StreamBehavior behavior) {
            this.behavior = behavior;
        }

        @Override public String getProviderName() { return "mock"; }
        @Override public List<String> getSupportedModels() { return List.of("mock-model"); }
        @Override public String getDefaultModel() { return "mock-model"; }
        @Override public ModelCapabilities getModelCapabilities(String model) {
            return new ModelCapabilities(model, "mock", 8192, 200000,
                    true, true, true, true, 0.0, 0.0);
        }

        @Override
        public void streamChat(String model, List<Map<String, Object>> messages,
                                String systemPrompt, List<Map<String, Object>> tools,
                                int maxTokens, ThinkingConfig thinkingConfig,
                                StreamChatCallback callback) {
            behavior.perform(callback);
        }

        @Override public void abort() {}
    }

    // ═══════════════ 辅助: EchoTool ═══════════════

    static class EchoTool implements Tool {
        @Override public String getName() { return "EchoTool"; }
        @Override public String getDescription() { return "Echoes input back"; }
        @Override public Map<String, Object> getInputSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "message", Map.of("type", "string")
            ));
        }
        @Override public ToolResult call(ToolInput input, ToolUseContext context) {
            String msg = input.getString("message", "echo");
            return ToolResult.success("Echo: " + msg);
        }
    }

    // ═══════════════ 辅助: RecordingHandler ═══════════════

    static class RecordingHandler implements QueryMessageHandler {
        final List<String> textDeltas = new CopyOnWriteArrayList<>();
        final List<String> toolUseStarts = new CopyOnWriteArrayList<>();
        final List<String> toolResults = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();

        @Override public void onTextDelta(String text) { textDeltas.add(text); }
        @Override public void onToolUseStart(String id, String name) { toolUseStarts.add(name); }
        @Override public void onToolUseComplete(String id, ContentBlock.ToolUseBlock toolUse) {}
        @Override public void onToolResult(String id, ContentBlock.ToolResultBlock result) {
            toolResults.add(result.content());
        }
        @Override public void onAssistantMessage(Message.AssistantMessage message) {}
        @Override public void onError(Throwable error) { errors.add(error); }
    }

    // ═══════════════ 辅助: 构建方法 ═══════════════

    private QueryConfig buildConfig() {
        return QueryConfig.withDefaults(
                "mock-model", "You are a helpful assistant.",
                List.of(), List.of(),
                8192, 200000,
                new ThinkingConfig.Disabled(), 10, "test"
        );
    }

    private QueryConfig buildConfigWithTools(List<Tool> tools) {
        List<Map<String, Object>> defs = tools.stream()
                .map(Tool::toToolDefinition).toList();
        return QueryConfig.withDefaults(
                "mock-model", "You are a helpful assistant.",
                tools, defs,
                8192, 200000,
                new ThinkingConfig.Disabled(), 10, "test"
        );
    }

    private Message.UserMessage userMessage(String text) {
        return new Message.UserMessage(
                UUID.randomUUID().toString(), Instant.now(),
                List.of(new ContentBlock.TextBlock(text)), null, null
        );
    }
}
