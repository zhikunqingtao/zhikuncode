package com.aicodeassistant.engine;

import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.llm.*;
import com.aicodeassistant.model.*;
import com.aicodeassistant.permission.PermissionPipeline;
import com.aicodeassistant.permission.PermissionRuleRepository;
import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * QueryEngine 单元测试 — 8步循环 + 错误恢复路径。
 */
@ExtendWith(MockitoExtension.class)
class QueryEngineUnitTest {

    @Mock LlmProviderRegistry providerRegistry;
    @Mock CompactService compactService;
    @Mock ApiRetryService apiRetryService;
    @Mock PermissionPipeline permissionPipeline;
    @Mock PermissionRuleRepository permissionRuleRepository;
    @Mock TokenCounter tokenCounter;
    @Mock StreamingToolExecutor streamingToolExecutor;
    @Mock MessageNormalizer messageNormalizer;
    @Mock HookService hookService;

    @Mock SnipService snipService;
    @Mock MicroCompactService microCompactService;

    private ObjectMapper objectMapper;
    private QueryEngine queryEngine;
    private TestHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        queryEngine = new QueryEngine(
                providerRegistry, compactService, apiRetryService,
                permissionPipeline, permissionRuleRepository, tokenCounter,
                objectMapper, streamingToolExecutor, messageNormalizer, hookService,
                snipService, microCompactService);
        handler = new TestHandler();

        // 默认 Snip/MicroCompact mock: 直接返回原消息列表
        lenient().when(snipService.snipToolResults(anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(microCompactService.compactMessages(anyList(), anyInt()))
                .thenReturn(new MicroCompactService.MicroCompactResult(List.of(), 0));
    }

    // ═══════════════ 8步循环测试 ═══════════════

    @Nested
    @DisplayName("核心循环步骤")
    class CoreLoopTests {

        @Test
        @DisplayName("Step 3: streamChat 调用参数正确传递")
        void streamChatParametersPassed() {
            // 配置 mock
            LlmProvider mockProvider = mock(LlmProvider.class);
            when(providerRegistry.getProvider(anyString())).thenReturn(mockProvider);
            when(messageNormalizer.normalize(anyList())).thenReturn(List.of());

            // 配置 session mock
            StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession()).thenReturn(session);

            // 配置 apiRetryService 让它直接执行传入的 supplier
            when(apiRetryService.executeWithRetry(any(), anyString())).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                var supplier = invocation.getArgument(0, Supplier.class);
                return supplier.get();
            });

            // 让 streamChat 模拟返回 end_turn
            doAnswer(inv -> {
                StreamChatCallback callback = inv.getArgument(6);
                callback.onEvent(new LlmStreamEvent.TextDelta("Hello"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(
                        new Usage(10, 5, 0, 0), "end_turn"));
                callback.onComplete();
                return null;
            }).when(mockProvider).streamChat(
                    anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(StreamChatCallback.class));

            // stop hooks
            when(hookService.executeStopHooks(anyList(), anyString()))
                    .thenReturn(HookRegistry.StopHookResult.ok());

            QueryConfig config = buildConfig();
            QueryLoopState state = buildState("test input");

            QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

            // 验证 streamChat 被调用
            verify(mockProvider).streamChat(
                    eq("mock-model"), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(StreamChatCallback.class));
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Step 7: end_turn 无工具调用 → 终止循环")
        void endTurnStopsLoop() {
            LlmProvider mockProvider = mock(LlmProvider.class);
            when(providerRegistry.getProvider(anyString())).thenReturn(mockProvider);
            when(messageNormalizer.normalize(anyList())).thenReturn(List.of());

            StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession()).thenReturn(session);

            when(apiRetryService.executeWithRetry(any(), anyString())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                var supplier = inv.getArgument(0, Supplier.class);
                return supplier.get();
            });

            doAnswer(inv -> {
                StreamChatCallback callback = inv.getArgument(6);
                callback.onEvent(new LlmStreamEvent.TextDelta("Done"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(
                        new Usage(10, 5, 0, 0), "end_turn"));
                callback.onComplete();
                return null;
            }).when(mockProvider).streamChat(
                    anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(StreamChatCallback.class));

            when(hookService.executeStopHooks(anyList(), anyString()))
                    .thenReturn(HookRegistry.StopHookResult.ok());

            QueryConfig config = buildConfig();
            QueryLoopState state = buildState("question");

            QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.turnCount()).isEqualTo(1);
            assertThat(handler.textDeltas).contains("Done");
        }
    }

    // ═══════════════ 错误恢复路径 ═══════════════

    @Nested
    @DisplayName("错误恢复路径")
    class ErrorRecoveryTests {

        @Test
        @DisplayName("LLM API 异常 → 错误被传递到 handler")
        void llmApiException() {
            when(providerRegistry.getProvider(anyString())).thenThrow(
                    new IllegalArgumentException("No provider found"));

            StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession()).thenReturn(session);

            QueryConfig config = buildConfig();
            QueryLoopState state = buildState("test");

            QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.stopReason()).isEqualTo("error");
            assertThat(handler.errors).hasSize(1);
        }

        @Test
        @DisplayName("stopHook blocking → 继续循环")
        void stopHookBlocking() {
            AtomicInteger callCount = new AtomicInteger(0);
            LlmProvider mockProvider = mock(LlmProvider.class);
            when(providerRegistry.getProvider(anyString())).thenReturn(mockProvider);
            when(messageNormalizer.normalize(anyList())).thenReturn(List.of());

            StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession()).thenReturn(session);

            when(apiRetryService.executeWithRetry(any(), anyString())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                var supplier = inv.getArgument(0, Supplier.class);
                return supplier.get();
            });

            doAnswer(inv -> {
                StreamChatCallback callback = inv.getArgument(6);
                callback.onEvent(new LlmStreamEvent.TextDelta("response " + callCount.incrementAndGet()));
                callback.onEvent(new LlmStreamEvent.MessageDelta(
                        new Usage(10, 5, 0, 0), "end_turn"));
                callback.onComplete();
                return null;
            }).when(mockProvider).streamChat(
                    anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(StreamChatCallback.class));

            // 第一次: blocking error; 第二次: ok
            when(hookService.executeStopHooks(anyList(), anyString()))
                    .thenReturn(HookRegistry.StopHookResult.blocking(List.of("Fix the error")))
                    .thenReturn(HookRegistry.StopHookResult.ok());

            QueryConfig config = buildConfig();
            QueryLoopState state = buildState("question");

            QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.turnCount()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("stopHook preventContinuation → 终止循环")
        void stopHookPreventContinuation() {
            LlmProvider mockProvider = mock(LlmProvider.class);
            when(providerRegistry.getProvider(anyString())).thenReturn(mockProvider);
            when(messageNormalizer.normalize(anyList())).thenReturn(List.of());

            StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession()).thenReturn(session);

            when(apiRetryService.executeWithRetry(any(), anyString())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                var supplier = inv.getArgument(0, Supplier.class);
                return supplier.get();
            });

            doAnswer(inv -> {
                StreamChatCallback callback = inv.getArgument(6);
                callback.onEvent(new LlmStreamEvent.TextDelta("Done"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(
                        new Usage(10, 5, 0, 0), "end_turn"));
                callback.onComplete();
                return null;
            }).when(mockProvider).streamChat(
                    anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(StreamChatCallback.class));

            when(hookService.executeStopHooks(anyList(), anyString()))
                    .thenReturn(HookRegistry.StopHookResult.preventContinuation("Stop now"));

            QueryConfig config = buildConfig();
            QueryLoopState state = buildState("question");

            QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

            assertThat(result.turnCount()).isEqualTo(1);
        }
    }

    // ═══════════════ QueryResult ═══════════════

    @Nested
    @DisplayName("QueryResult")
    class QueryResultTests {

        @Test
        @DisplayName("成功结果包含消息和 usage")
        void successResult() {
            QueryEngine.QueryResult result = new QueryEngine.QueryResult(
                    List.of(), new Usage(100, 50, 0, 0), "end_turn", null, 3);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.stopReason()).isEqualTo("end_turn");
            assertThat(result.totalUsage().inputTokens()).isEqualTo(100);
            assertThat(result.turnCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("错误结果")
        void errorResult() {
            QueryEngine.QueryResult result = new QueryEngine.QueryResult(
                    List.of(), Usage.zero(), "error", "something went wrong", 1);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).isEqualTo("something went wrong");
        }
    }

    // ═══════════════ 辅助 ═══════════════

    private QueryConfig buildConfig() {
        return QueryConfig.withDefaults(
                "mock-model", "You are a helpful assistant.",
                List.of(), List.of(),
                8192, 200000,
                new ThinkingConfig.Disabled(), 10, "test"
        );
    }

    private QueryLoopState buildState(String userInput) {
        return new QueryLoopState(
                List.of(new Message.UserMessage(
                        UUID.randomUUID().toString(), Instant.now(),
                        List.of(new ContentBlock.TextBlock(userInput)), null, null)),
                ToolUseContext.of("/tmp", "test-session")
        );
    }

    static class TestHandler implements QueryMessageHandler {
        final List<String> textDeltas = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        @Override public void onTextDelta(String text) { textDeltas.add(text); }
        @Override public void onToolUseStart(String id, String name) {}
        @Override public void onToolUseComplete(String id, ContentBlock.ToolUseBlock toolUse) {}
        @Override public void onToolResult(String id, ContentBlock.ToolResultBlock result) {}
        @Override public void onAssistantMessage(Message.AssistantMessage message) {}
        @Override public void onError(Throwable error) { errors.add(error); }
    }
}
