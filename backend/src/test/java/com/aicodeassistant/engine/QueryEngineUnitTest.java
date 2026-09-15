package com.aicodeassistant.engine;

import com.aicodeassistant.config.AgentTimeoutConfig;
import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.engine.ContextCascade;
import com.aicodeassistant.engine.scheduling.ToolPriorityScheduler;
import com.aicodeassistant.engine.strategy.DefaultTerminationStrategy;
import com.aicodeassistant.history.FileHistoryService;
import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.llm.*;
import com.aicodeassistant.model.*;
import com.aicodeassistant.run.RunEnvelope;
import com.aicodeassistant.run.RunExecutionRegistry;
import com.aicodeassistant.run.RunTracker;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
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
    @Mock TokenCounter tokenCounter;
    @Mock StreamingToolExecutor streamingToolExecutor;
    @Mock MessageNormalizer messageNormalizer;
    @Mock HookService hookService;

    @Mock SnipService snipService;
    @Mock MicroCompactService microCompactService;
    @Mock ModelRegistry modelRegistry;
    @Mock ThinkingBudgetCalculator thinkingBudgetCalculator;
    @Mock ModelTierService modelTierService;
    @Mock FileHistoryService fileHistoryService;
    @Mock ToolResultSummarizer toolResultSummarizer;
    @Mock ContextCascade contextCascade;
    @Mock CompactMetrics compactMetrics;
    @Mock FeatureFlagService featureFlagService;
    @Mock TokenBudgetGuard tokenBudgetGuard;
    @Mock ImageRefInjector imageRefInjector;
    @Mock RunTracker runTracker;

    private ObjectMapper objectMapper;
    private QueryEngine queryEngine;
    private TestHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        queryEngine = new QueryEngine(
                providerRegistry, compactService, apiRetryService,
                tokenCounter,
                objectMapper, streamingToolExecutor, messageNormalizer, hookService,
                snipService, microCompactService, modelRegistry,
                thinkingBudgetCalculator, modelTierService, fileHistoryService,
                toolResultSummarizer, contextCascade, compactMetrics,
                null, null,  // incrementalCollapseManager, visualizationAutoRouter (both @Nullable)
                null, featureFlagService,  // backgroundAgentTracker (@Nullable), featureFlagService
                new DefaultTerminationStrategy(), new ToolPriorityScheduler(), null, new AgentTimeoutConfig(), tokenBudgetGuard, imageRefInjector, null, null);  // Run authority is covered by integration tests
        handler = new TestHandler();

        // 默认 Snip/MicroCompact mock: 直接返回原消息列表
        lenient().when(snipService.snipToolResults(anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(microCompactService.compactMessages(anyList(), anyInt()))
                .thenReturn(new MicroCompactService.MicroCompactResult(List.of(), 0));
        // 默认 ModelTierService mock: 返回原模型（无降级）
        lenient().when(modelTierService.resolveModel(anyString(), anyList())).thenAnswer(inv -> inv.getArgument(0));
        // 默认 ModelRegistry mock: 返回合理的 contextWindow
        lenient().when(modelRegistry.getContextWindowForModel(anyString())).thenReturn(200000);
        lenient().when(modelRegistry.getTokenCharRatio(anyString())).thenReturn(3.5);
        lenient().when(modelRegistry.getCapabilities(anyString())).thenReturn(testModelCapabilities());
        // 默认 TokenBudgetGuard mock: 直接放行
        lenient().when(tokenBudgetGuard.enforcePhase1(anyList(), anyInt()))
                .thenAnswer(inv -> new TokenBudgetGuard.GuardResult(inv.getArgument(0), false, 0, 0));
        lenient().when(tokenBudgetGuard.enforcePhase1(anyList(), anyInt(), anyDouble()))
                .thenAnswer(inv -> new TokenBudgetGuard.GuardResult(inv.getArgument(0), false, 0, 0));
        lenient().when(tokenBudgetGuard.enforcePhase2(anyList(), anyInt()))
                .thenAnswer(inv -> new TokenBudgetGuard.FinalBudgetResult(inv.getArgument(0), Set.of(), 0, inv.getArgument(1), true, ""));
        lenient().when(tokenBudgetGuard.enforcePhase2(anyList(), anyInt(), anySet(), anyDouble()))
                .thenAnswer(inv -> new TokenBudgetGuard.FinalBudgetResult(inv.getArgument(0), Set.of(), 0, inv.getArgument(1), true, ""));
        // 默认 ImageRefInjector mock: 直接返回原消息
        lenient().when(imageRefInjector.injectForApiCall(
                        anyList(), anyInt(), anyInt(), anySet(), anyMap(), nullable(String.class), anyInt()))
                .thenAnswer(inv -> new ImageRefInjector.InjectResult(inv.getArgument(0), Set.of()));
        // 默认 ContextCascade mock: 直接返回原消息列表（无压缩）
        lenient().when(contextCascade.executePreApiCascade(anyList(), anyString(), any()))
                .thenAnswer(inv -> {
                    List<Message> msgs = inv.getArgument(0);
                    int tokens = msgs.size() * 100;
                    return new ContextCascade.CascadeResult(
                            msgs, tokens, tokens, false, 0, false, 0, false, 0, false, false, null);
                });
    }

    private static ModelCapabilities testModelCapabilities() {
        return new ModelCapabilities("mock-model", "Mock Model", 8192, 200000,
                true, false, true, 5, true, 0.0, 0.0);
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
            when(messageNormalizer.normalizeTyped(anyList())).thenReturn(List.of());

            // 配置 session mock
            StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession(any())).thenReturn(session);

            // 配置 apiRetryService 让它直接执行传入的 supplier
            when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any())).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                var supplier = invocation.getArgument(0, Supplier.class);
                return supplier.get();
            });

            // 让 streamChat 模拟返回 end_turn
            doAnswer(inv -> {
                StreamChatCallback callback = inv.getArgument(7);
                callback.onEvent(new LlmStreamEvent.TextDelta("Hello"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(
                        new Usage(10, 5, 0, 0), "end_turn"));
                callback.onComplete();
                return null;
            }).when(mockProvider).streamChat(
                    anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));

            // stop hooks
            when(hookService.executeStopHooks(anyList(), anyString()))
                    .thenReturn(HookRegistry.StopHookResult.ok());

            QueryConfig config = buildConfig();
            QueryLoopState state = buildState("test input");

            QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

            // 验证 streamChat 被调用
            verify(mockProvider).streamChat(
                    eq("mock-model"), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Step 7: end_turn 无工具调用 → 终止循环")
        void endTurnStopsLoop() {
            LlmProvider mockProvider = mock(LlmProvider.class);
            when(providerRegistry.getProvider(anyString())).thenReturn(mockProvider);
            when(messageNormalizer.normalizeTyped(anyList())).thenReturn(List.of());

            StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession(any())).thenReturn(session);

            when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                var supplier = inv.getArgument(0, Supplier.class);
                return supplier.get();
            });

            doAnswer(inv -> {
                StreamChatCallback callback = inv.getArgument(7);
                callback.onEvent(new LlmStreamEvent.TextDelta("Done"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(
                        new Usage(10, 5, 0, 0), "end_turn"));
                callback.onComplete();
                return null;
            }).when(mockProvider).streamChat(
                    anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));

            when(hookService.executeStopHooks(anyList(), anyString()))
                    .thenReturn(HookRegistry.StopHookResult.ok());

            QueryConfig config = buildConfig();
            QueryLoopState state = buildState("question");

            QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.turnCount()).isEqualTo(1);
            assertThat(handler.textDeltas).contains("Done");
        }

        @Test
        @DisplayName("end_turn 仅 thinking → 补请求后有正文时成功")
        void emptyFinalResponse_retriesOnceAndSucceeds() {
            AtomicInteger callCount = new AtomicInteger();
            LlmProvider mockProvider = mock(LlmProvider.class);
            when(providerRegistry.getProvider(anyString())).thenReturn(mockProvider);
            when(messageNormalizer.normalizeTyped(anyList())).thenReturn(List.of());

            StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession(any())).thenReturn(session);
            when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                var supplier = inv.getArgument(0, Supplier.class);
                return supplier.get();
            });

            doAnswer(inv -> {
                StreamChatCallback callback = inv.getArgument(7);
                if (callCount.incrementAndGet() == 1) {
                    callback.onEvent(new LlmStreamEvent.ThinkingDelta("thinking only"));
                } else {
                    callback.onEvent(new LlmStreamEvent.TextDelta("Recovered answer"));
                }
                callback.onEvent(new LlmStreamEvent.MessageDelta(
                        new Usage(10, 5, 0, 0), "end_turn"));
                callback.onComplete();
                return null;
            }).when(mockProvider).streamChat(
                    anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));

            when(hookService.executeStopHooks(anyList(), anyString()))
                    .thenReturn(HookRegistry.StopHookResult.ok());

            QueryEngine.QueryResult result = queryEngine.execute(
                    buildConfig(), buildState("question"), handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(callCount.get()).isEqualTo(2);
            assertThat(handler.textDeltas).contains("Recovered answer");
        }

        @Test
        @DisplayName("连续两次 end_turn 无正文 → 返回错误")
        void emptyFinalResponse_failsAfterOneRetry() {
            AtomicInteger callCount = new AtomicInteger();
            LlmProvider mockProvider = mock(LlmProvider.class);
            when(providerRegistry.getProvider(anyString())).thenReturn(mockProvider);
            when(messageNormalizer.normalizeTyped(anyList())).thenReturn(List.of());

            StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession(any())).thenReturn(session);
            when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                var supplier = inv.getArgument(0, Supplier.class);
                return supplier.get();
            });

            doAnswer(inv -> {
                StreamChatCallback callback = inv.getArgument(7);
                callCount.incrementAndGet();
                callback.onEvent(new LlmStreamEvent.ThinkingDelta("thinking only"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(
                        new Usage(10, 5, 0, 0), "end_turn"));
                callback.onComplete();
                return null;
            }).when(mockProvider).streamChat(
                    anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));

            QueryEngine.QueryResult result = queryEngine.execute(
                    buildConfig(), buildState("question"), handler);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).isEqualTo("EMPTY_FINAL_RESPONSE");
            assertThat(callCount.get()).isEqualTo(2);
            assertThat(handler.errors).hasSize(1);
        }
    }

    @Nested
    @DisplayName("空最终正文修复的行为回归")
    class EmptyFinalResponseReviewTests {
        private RunExecutionRegistry executions;
        private RunEnvelope run;
        private StreamingToolExecutor.ExecutionSession toolSession;
        private final List<List<Map<String, Object>>> requests = new ArrayList<>();

        @BeforeEach
        void useRealNormalizationAndRunAdmission() {
            executions = new RunExecutionRegistry();
            run = RunEnvelope.start("test-session", null, "query", "mock-model");
            when(runTracker.startRun("test-session", null, "query", "mock-model"))
                    .thenReturn(run);
            queryEngine = new QueryEngine(
                    providerRegistry, compactService, apiRetryService, tokenCounter,
                    objectMapper, streamingToolExecutor, new MessageNormalizer(), hookService,
                    snipService, microCompactService, modelRegistry,
                    thinkingBudgetCalculator, modelTierService, fileHistoryService,
                    toolResultSummarizer, contextCascade, compactMetrics,
                    null, null, null, featureFlagService,
                    new DefaultTerminationStrategy(), new ToolPriorityScheduler(),
                    null, new AgentTimeoutConfig(), tokenBudgetGuard, imageRefInjector,
                    runTracker, executions);
            toolSession = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession(any())).thenReturn(toolSession);
            when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any()))
                    .thenAnswer(inv -> inv.getArgument(0, Supplier.class).get());
            lenient().when(hookService.executeStopHooks(anyList(), anyString()))
                    .thenReturn(HookRegistry.StopHookResult.ok());
        }

        @Test
        @DisplayName("最后允许的一轮没有正文时必须失败，不能把未执行的补请求当成功")
        void emptyFinalAtTurnLimitFailsWithoutAnotherModelCall() {
            script((call, callback) -> finish(callback, "end_turn",
                    new LlmStreamEvent.ThinkingDelta("thinking only")));

            QueryEngine.QueryResult result = queryEngine.execute(
                    withMaxTurns(1), buildState("question"), handler);

            assertThat(requests).hasSize(1);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).isEqualTo("EMPTY_FINAL_RESPONSE");
            verify(runTracker).failRun(run.id(), "EMPTY_FINAL_RESPONSE");
            verify(runTracker, never()).completeRun(anyString(), anyInt(), anyDouble(), anyInt(), anyInt());
            assertRunAdmissionClosed();
        }

        @Test
        @DisplayName("停止钩子禁止继续时，空正文不能触发额外模型请求")
        void emptyFinalHonorsStopHookPreventContinuation() {
            when(hookService.executeStopHooks(anyList(), eq("test-session")))
                    .thenReturn(HookRegistry.StopHookResult.preventContinuation("stop now"));
            script((call, callback) -> finish(callback, "end_turn",
                    new LlmStreamEvent.ThinkingDelta("thinking only")));

            QueryEngine.QueryResult result = queryEngine.execute(
                    buildConfig(), buildState("question"), handler);

            assertThat(requests).hasSize(1);
            verify(hookService).executeStopHooks(anyList(), eq("test-session"));
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).isEqualTo("EMPTY_FINAL_RESPONSE");
            verify(runTracker).failRun(run.id(), "EMPTY_FINAL_RESPONSE");
            verify(runTracker, never()).completeRun(anyString(), anyInt(), anyDouble(), anyInt(), anyInt());
            assertRunAdmissionClosed();
        }

        @Test
        @DisplayName("空正文被 blocking 钩子要求继续但已达轮次上限时必须失败")
        void emptyFinalBlockedByHookAtTurnLimitFails() {
            when(hookService.executeStopHooks(anyList(), eq("test-session")))
                    .thenReturn(HookRegistry.StopHookResult.blocking(List.of("Address the remaining issue.")));
            script((call, callback) -> finish(callback, "end_turn",
                    new LlmStreamEvent.ThinkingDelta("thinking only")));

            QueryEngine.QueryResult result = queryEngine.execute(
                    withMaxTurns(1), buildState("question"), handler);

            assertThat(requests).hasSize(1);
            verify(hookService).executeStopHooks(anyList(), eq("test-session"));
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).isEqualTo("EMPTY_FINAL_RESPONSE");
            verify(runTracker).failRun(run.id(), "EMPTY_FINAL_RESPONSE");
            verify(runTracker, never()).completeRun(anyString(), anyInt(), anyDouble(), anyInt(), anyInt());
            assertRunAdmissionClosed();
        }

        @Test
        @DisplayName("空正文耗尽 Token 预算时不再请求模型，并明确失败")
        void emptyFinalAtTokenBudgetLimitFailsWithoutRetry() {
            script((call, callback) -> finish(callback, "end_turn",
                    new LlmStreamEvent.ThinkingDelta("thinking only")));
            QueryConfig config = new QueryConfig(
                    "mock-model", null, "You are a helpful assistant.",
                    List.of(), List.of(), 8192, 200000,
                    new ThinkingConfig.Disabled(), 10, "test", 1, List.of());

            QueryEngine.QueryResult result = queryEngine.execute(config, buildState("question"), handler);

            assertThat(requests).hasSize(1);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).isEqualTo("EMPTY_FINAL_RESPONSE");
            verify(runTracker).failRun(run.id(), "EMPTY_FINAL_RESPONSE");
            verify(runTracker, never()).completeRun(anyString(), anyInt(), anyDouble(), anyInt(), anyInt());
            assertRunAdmissionClosed();
        }

        @Test
        @DisplayName("blocking 钩子已要求继续时复用该请求恢复正文，不重复注入补正文提示")
        void blockingHookCanRecoverEmptyFinalWithoutAnAdditionalRecoveryPrompt() {
            String blockingInstruction = "Address the remaining issue.";
            when(hookService.executeStopHooks(anyList(), eq("test-session")))
                    .thenReturn(HookRegistry.StopHookResult.blocking(List.of(blockingInstruction)));
            script((call, callback) -> {
                if (call == 1) {
                    finish(callback, "end_turn", new LlmStreamEvent.ThinkingDelta("thinking only"));
                } else {
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("Hook requirement addressed"));
                }
            });

            QueryEngine.QueryResult result = queryEngine.execute(
                    buildConfig(), buildState("question"), handler);

            assertThat(requests).hasSize(2);
            assertThat(userTexts(requests.get(1))).anyMatch(text -> text.contains(blockingInstruction));
            assertThat(userTexts(requests.get(1))).noneMatch(text -> text.contains("final answer"));
            assertThat(result.isSuccess()).isTrue();
            assertThat(handler.errors).isEmpty();
            assertThat(handler.textDeltas).contains("Hook requirement addressed");
            verify(hookService).executeStopHooks(anyList(), eq("test-session"));
            verify(runTracker).completeRun(eq(run.id()), anyInt(), anyDouble(), anyInt(), eq(2));
            verify(runTracker, never()).failRun(anyString(), anyString());
            assertRunAdmissionClosed();
        }

        @Test
        @DisplayName("thinking 加内部 final 标记清洗后为空，补请求确实送到模型并恢复正文")
        void sanitizedMarkerOnlyFinalRecovers() {
            script((call, callback) -> {
                if (call == 1) {
                    finish(callback, "end_turn",
                            new LlmStreamEvent.ThinkingDelta("internal reasoning"),
                            new LlmStreamEvent.TextDelta("[final]"));
                } else {
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("Recovered answer"));
                }
            });

            QueryEngine.QueryResult result = queryEngine.execute(
                    buildConfig(), buildState("question"), handler);

            assertRecovered(result);
            Message.AssistantMessage first = result.messages().stream()
                    .filter(Message.AssistantMessage.class::isInstance)
                    .map(Message.AssistantMessage.class::cast).findFirst().orElseThrow();
            assertThat(first.content()).noneMatch(ContentBlock.TextBlock.class::isInstance);
        }

        @Test
        @DisplayName("完全空的 end_turn 内容可以补请求恢复")
        void emptyContentRecovers() {
            script((call, callback) -> {
                if (call == 1) finish(callback, "end_turn");
                else finish(callback, "end_turn", new LlmStreamEvent.TextDelta("Recovered answer"));
            });

            assertRecovered(queryEngine.execute(buildConfig(), buildState("question"), handler));
        }

        @Test
        @DisplayName("仅空白正文的 end_turn 可以补请求恢复")
        void blankTextRecovers() {
            script((call, callback) -> finish(callback, "end_turn",
                    new LlmStreamEvent.TextDelta(call == 1 ? " \n\t " : "Recovered answer")));

            assertRecovered(queryEngine.execute(buildConfig(), buildState("question"), handler));
        }

        @Test
        @DisplayName("补请求仍无正文时仅调用两次并进入 Run 失败与清理路径")
        void repeatedEmptyFinalFailsRunAndCleansAdmission() {
            script((call, callback) -> finish(callback, "end_turn",
                    new LlmStreamEvent.ThinkingDelta("still no answer")));

            QueryEngine.QueryResult result = queryEngine.execute(
                    buildConfig(), buildState("question"), handler);

            assertThat(requests).hasSize(2);
            assertThat(userTexts(requests.get(1))).anyMatch(text -> text.contains("final answer"));
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).isEqualTo("EMPTY_FINAL_RESPONSE");
            assertThat(handler.errors).hasSize(1);
            verify(runTracker).failRun(run.id(), "EMPTY_FINAL_RESPONSE");
            verify(runTracker, never()).completeRun(anyString(), anyInt(), anyDouble(), anyInt(), anyInt());
            assertRunAdmissionClosed();
        }

        @Test
        @DisplayName("已有可见正文的普通回答只调用一次")
        void visibleTextDoesNotRetry() {
            script((call, callback) -> finish(callback, "end_turn",
                    new LlmStreamEvent.ThinkingDelta("reasoning"),
                    new LlmStreamEvent.TextDelta("Normal answer")));

            QueryEngine.QueryResult result = queryEngine.execute(
                    buildConfig(), buildState("question"), handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(requests).hasSize(1);
            verify(runTracker).completeRun(eq(run.id()), anyInt(), anyDouble(), anyInt(), eq(1));
            verify(runTracker, never()).failRun(anyString(), anyString());
            assertRunAdmissionClosed();
        }

        @Test
        @DisplayName("最后一轮有正文的正常回答仍可成功")
        void visibleFinalAtTurnLimitStillSucceeds() {
            script((call, callback) -> finish(callback, "end_turn",
                    new LlmStreamEvent.TextDelta("Normal answer")));

            QueryEngine.QueryResult result = queryEngine.execute(
                    withMaxTurns(1), buildState("question"), handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(requests).hasSize(1);
            verify(runTracker).completeRun(eq(run.id()), anyInt(), anyDouble(), anyInt(), eq(1));
            assertRunAdmissionClosed();
        }

        @Test
        @DisplayName("正常无正文 tool_use 轮次继续消费工具，不注入补最终回答提示")
        void toolUseWithoutTextDoesNotTriggerEmptyFinalRecovery() {
            completedToolResult();
            when(toolResultSummarizer.processToolResults(anyList(), anyInt()))
                    .thenAnswer(inv -> inv.getArgument(0));
            script((call, callback) -> {
                if (call == 1) {
                    finish(callback, "tool_use",
                            new LlmStreamEvent.ToolUseStart("review-tool-1", "Bash"),
                            new LlmStreamEvent.ToolInputDelta("review-tool-1", "{}"));
                } else {
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("Tool completed"));
                }
            });

            QueryEngine.QueryResult result = queryEngine.execute(
                    buildConfig(), buildState("question"), handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(requests).hasSize(2);
            assertThat(userTexts(requests.get(1))).containsExactly("question");
            assertThat(objectMapper.valueToTree(requests.get(1)).findValuesAsText("tool_use_id"))
                    .contains("review-tool-1");
            assertRunAdmissionClosed();
        }

        @Test
        @DisplayName("空正文补请求转为 tool_use 后耗尽轮次，仍不能把缺少最终正文的恢复标记成功")
        void recoveryToolUseAtTurnLimitDoesNotCompleteAnUnrecoveredRun() {
            completedToolResult();
            script((call, callback) -> {
                if (call == 1) {
                    finish(callback, "end_turn", new LlmStreamEvent.ThinkingDelta("thinking only"));
                } else {
                    finish(callback, "tool_use",
                            new LlmStreamEvent.ToolUseStart("review-tool-1", "Bash"),
                            new LlmStreamEvent.ToolInputDelta("review-tool-1", "{}"));
                }
            });

            QueryEngine.QueryResult result = queryEngine.execute(
                    withMaxTurns(2), buildState("question"), handler);

            assertThat(requests).hasSize(2);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).isEqualTo("EMPTY_FINAL_RESPONSE");
            verify(runTracker).failRun(run.id(), "EMPTY_FINAL_RESPONSE");
            verify(runTracker, never()).completeRun(anyString(), anyInt(), anyDouble(), anyInt(), anyInt());
            assertRunAdmissionClosed();
        }

        @Test
        @DisplayName("空正文补请求经过工具调用后生成最终正文，可以正常成功")
        void recoveryThroughToolUseCanStillSucceed() {
            completedToolResult();
            when(toolResultSummarizer.processToolResults(anyList(), anyInt()))
                    .thenAnswer(inv -> inv.getArgument(0));
            script((call, callback) -> {
                if (call == 1) {
                    finish(callback, "end_turn", new LlmStreamEvent.ThinkingDelta("thinking only"));
                } else if (call == 2) {
                    finish(callback, "tool_use",
                            new LlmStreamEvent.ToolUseStart("review-tool-1", "Bash"),
                            new LlmStreamEvent.ToolInputDelta("review-tool-1", "{}"));
                } else {
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("Answer after tool completion"));
                }
            });

            QueryEngine.QueryResult result = queryEngine.execute(
                    buildConfig(), buildState("question"), handler);

            assertThat(requests).hasSize(3);
            assertThat(userTexts(requests.get(1))).anyMatch(text -> text.contains("final answer"));
            assertThat(objectMapper.valueToTree(requests.get(2)).findValuesAsText("tool_use_id"))
                    .contains("review-tool-1");
            assertThat(result.isSuccess()).isTrue();
            assertThat(handler.textDeltas).contains("Answer after tool completion");
            verify(runTracker).completeRun(eq(run.id()), anyInt(), anyDouble(), anyInt(), eq(3));
            verify(runTracker, never()).failRun(anyString(), anyString());
            assertRunAdmissionClosed();
        }

        @Test
        @DisplayName("补正文期间用户明确停止应取消 Run，不得变成空正文失败")
        void explicitCancellationDuringRecoveryRemainsCancellation() {
            script((call, callback) -> {
                if (call == 2) queryEngine.abort("test-session", AbortReason.USER_INTERRUPT);
                finish(callback, "end_turn", new LlmStreamEvent.ThinkingDelta("thinking only"));
            });

            QueryEngine.QueryResult result = queryEngine.execute(
                    buildConfig(), buildState("question"), handler);

            assertThat(requests).hasSize(2);
            assertThat(result.error()).isNotEqualTo("EMPTY_FINAL_RESPONSE");
            assertThat(handler.errors).isEmpty();
            verify(runTracker).abortRun(eq(run.id()), eq(AbortReason.USER_INTERRUPT), anyString());
            verify(runTracker, never()).failRun(anyString(), anyString());
            verify(runTracker, never()).completeRun(anyString(), anyInt(), anyDouble(), anyInt(), anyInt());
            assertRunAdmissionClosed();
        }

        @Test
        @DisplayName("补正文请求期间仍接受追加指令，并送入下一轮模型请求")
        void steeringAcceptedDuringRecoveryAndAppliedBeforeCompletion() {
            AtomicReference<RunExecutionRegistry.InputOfferResult> offer = new AtomicReference<>();
            String requestId = UUID.randomUUID().toString();
            String steering = "Include the added requirement in the answer.";
            script((call, callback) -> {
                if (call == 1) {
                    finish(callback, "end_turn", new LlmStreamEvent.ThinkingDelta("thinking only"));
                } else if (call == 2) {
                    offer.set(executions.offerInputForSession("test-session", requestId, steering));
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("Recovered answer"));
                } else {
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("Added requirement addressed"));
                }
            });

            QueryEngine.QueryResult result = queryEngine.execute(
                    buildConfig(), buildState("question"), handler);

            assertThat(offer.get()).isNotNull();
            assertThat(offer.get().accepted()).isTrue();
            assertThat(requests).hasSize(3);
            assertThat(userTexts(requests.get(2))).contains(steering);
            assertThat(result.messages()).anyMatch(message -> message instanceof Message.UserMessage user
                    && requestId.equals(user.uuid()));
            assertThat(result.isSuccess()).isTrue();
            assertThat(handler.textDeltas).contains("Added requirement addressed");
            verify(runTracker).completeRun(eq(run.id()), anyInt(), anyDouble(), anyInt(), eq(3));
            verify(runTracker, never()).failRun(anyString(), anyString());
            assertRunAdmissionClosed();
        }

        private void script(BiConsumer<Integer, StreamChatCallback> response) {
            LlmProvider provider = mock(LlmProvider.class);
            when(providerRegistry.getProvider(anyString())).thenReturn(provider);
            doAnswer(inv -> {
                List<Map<String, Object>> messages = inv.getArgument(1);
                requests.add(List.copyOf(messages));
                response.accept(requests.size(), inv.getArgument(7));
                return null;
            }).when(provider).streamChat(
                    anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));
        }

        private void finish(StreamChatCallback callback, String stopReason, LlmStreamEvent... events) {
            for (LlmStreamEvent event : events) callback.onEvent(event);
            callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(10, 5, 0, 0), stopReason));
            callback.onComplete();
        }

        private void completedToolResult() {
            when(toolSession.isAllCompleted()).thenReturn(true);
            StreamingToolExecutor.TrackedTool completedTool = mock(StreamingToolExecutor.TrackedTool.class);
            when(completedTool.getToolUseId()).thenReturn("review-tool-1");
            when(completedTool.getResult()).thenReturn(ToolResult.success("tool result"));
            when(toolSession.yieldCompleted()).thenReturn(List.of(completedTool));
        }

        private QueryConfig withMaxTurns(int maxTurns) {
            return QueryConfig.withDefaults("mock-model", "You are a helpful assistant.",
                    List.of(), List.of(), 8192, 200000,
                    new ThinkingConfig.Disabled(), maxTurns, "test");
        }

        private List<String> userTexts(List<Map<String, Object>> messages) {
            return messages.stream().filter(message -> "user".equals(message.get("role")))
                    .flatMap(message -> message.get("content") instanceof String text
                            ? java.util.stream.Stream.of(text)
                            : objectMapper.valueToTree(message.get("content")).findValuesAsText("text").stream())
                    .toList();
        }

        private void assertRecovered(QueryEngine.QueryResult result) {
            assertThat(result.isSuccess()).isTrue();
            assertThat(requests).hasSize(2);
            assertThat(userTexts(requests.get(1))).anyMatch(text -> text.contains("final answer"));
            assertThat(handler.textDeltas).contains("Recovered answer");
            verify(runTracker).completeRun(eq(run.id()), anyInt(), anyDouble(), anyInt(), eq(2));
            verify(runTracker, never()).failRun(anyString(), anyString());
            assertRunAdmissionClosed();
        }

        private void assertRunAdmissionClosed() {
            assertThat(executions.isRegistered(run.id())).isFalse();
            assertThat(executions.offerInputForSession(
                    "test-session", UUID.randomUUID().toString(), "late instruction")
                    .receipt().rejectionCode()).isEqualTo("NO_ACTIVE_RUN");
        }
    }

    // ═══════════════ 错误恢复路径 ═══════════════

    @Nested
    @DisplayName("错误恢复路径")
    class ErrorRecoveryTests {

        @Test
        @DisplayName("LLM API 异常 → 错误被传递到 handler")
        void llmApiException() {
            lenient().when(providerRegistry.getProvider(anyString())).thenThrow(
                    new IllegalArgumentException("No provider found"));

            StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
            lenient().when(streamingToolExecutor.newSession(any())).thenReturn(session);

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
            when(messageNormalizer.normalizeTyped(anyList())).thenReturn(List.of());

            StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession(any())).thenReturn(session);

            when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                var supplier = inv.getArgument(0, Supplier.class);
                return supplier.get();
            });

            doAnswer(inv -> {
                StreamChatCallback callback = inv.getArgument(7);
                callback.onEvent(new LlmStreamEvent.TextDelta("response " + callCount.incrementAndGet()));
                callback.onEvent(new LlmStreamEvent.MessageDelta(
                        new Usage(10, 5, 0, 0), "end_turn"));
                callback.onComplete();
                return null;
            }).when(mockProvider).streamChat(
                    anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));

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
            lenient().when(providerRegistry.getProvider(anyString())).thenReturn(mockProvider);
            lenient().when(messageNormalizer.normalizeTyped(anyList())).thenReturn(List.of());

            StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
            lenient().when(streamingToolExecutor.newSession(any())).thenReturn(session);

            lenient().when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                var supplier = inv.getArgument(0, Supplier.class);
                return supplier.get();
            });

            lenient().doAnswer(inv -> {
                StreamChatCallback callback = inv.getArgument(7);
                callback.onEvent(new LlmStreamEvent.TextDelta("Done"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(
                        new Usage(10, 5, 0, 0), "end_turn"));
                callback.onComplete();
                return null;
            }).when(mockProvider).streamChat(
                    anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));

            lenient().when(hookService.executeStopHooks(anyList(), anyString()))
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

    // ═══════════════ 新增: 7 个关键场景测试 ═══════════════

    @Nested
    @DisplayName("413/AutoCompact/MaxTurns/MaxTokens/Fallback/Abort 关键路径")
    class KeyScenarioTests {

        @Test
        @DisplayName("#1 413 prompt_too_long → reactive compact → 重试成功")
        void promptTooLong_triggersReactiveCompact_thenRetries() {
            AtomicInteger callCount = new AtomicInteger();
            LlmProvider mockProvider = mock(LlmProvider.class);
            when(providerRegistry.getProvider(anyString())).thenReturn(mockProvider);
            when(messageNormalizer.normalizeTyped(anyList())).thenReturn(List.of());

            StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession(any())).thenReturn(session);

            // First call: throw 413, second call: succeed
            when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                var supplier = inv.getArgument(0, Supplier.class);
                if (callCount.incrementAndGet() == 1) {
                    throw new LlmApiException("prompt_too_long", true, 413);
                }
                return supplier.get();
            });

            // Mock compact service for reactive compact
            when(compactService.reactiveCompact(anyList(), anyInt(), eq(false)))
                    .thenReturn(new CompactService.CompactResult(List.of(), 5000, 2000, 3, 0.4));

            doAnswer(inv -> {
                StreamChatCallback callback = inv.getArgument(7);
                callback.onEvent(new LlmStreamEvent.TextDelta("recovered"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(
                        new Usage(10, 5, 0, 0), "end_turn"));
                callback.onComplete();
                return null;
            }).when(mockProvider).streamChat(
                    anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));

            when(hookService.executeStopHooks(anyList(), anyString()))
                    .thenReturn(HookRegistry.StopHookResult.ok());

            QueryConfig config = buildConfig();
            QueryLoopState state = buildState("test input");

            QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(callCount.get()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("#3 maxTurns 达到上限 → 返回 max_turns")
        void maxTurns_reached_returnsMaxTurns() {
            LlmProvider mockProvider = mock(LlmProvider.class);
            when(providerRegistry.getProvider(anyString())).thenReturn(mockProvider);
            when(messageNormalizer.normalizeTyped(anyList())).thenReturn(List.of());

            StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession(any())).thenReturn(session);
            when(session.isAllCompleted()).thenReturn(true);
            when(session.yieldCompleted()).thenReturn(List.of());

            when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                var supplier = inv.getArgument(0, Supplier.class);
                return supplier.get();
            });

            // Always return tool_use to keep loop going
            doAnswer(inv -> {
                StreamChatCallback callback = inv.getArgument(7);
                callback.onEvent(new LlmStreamEvent.ToolUseStart("t-" + System.nanoTime(), "BashTool"));
                callback.onEvent(new LlmStreamEvent.ToolInputDelta("t-" + System.nanoTime(), "{}"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(
                        new Usage(10, 5, 0, 0), "tool_use"));
                callback.onComplete();
                return null;
            }).when(mockProvider).streamChat(
                    anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));

            // maxTurns = 2
            QueryConfig config = QueryConfig.withDefaults(
                    "mock-model", "You are helpful.",
                    List.of(), List.of(),
                    8192, 200000,
                    new ThinkingConfig.Disabled(), 2, "test"
            );
            QueryLoopState state = buildState("question");

            QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

            assertThat(result.stopReason()).isEqualTo("max_turns");
            assertThat(result.turnCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("#4 max_tokens → 注入 RECOVERY_MESSAGE → 续写")
        void maxTokens_recovery_injectsRecoveryMessage() {
            AtomicInteger callCount = new AtomicInteger();
            LlmProvider mockProvider = mock(LlmProvider.class);
            when(providerRegistry.getProvider(anyString())).thenReturn(mockProvider);
            when(messageNormalizer.normalizeTyped(anyList())).thenReturn(List.of());

            StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession(any())).thenReturn(session);

            when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                var supplier = inv.getArgument(0, Supplier.class);
                return supplier.get();
            });

            doAnswer(inv -> {
                StreamChatCallback callback = inv.getArgument(7);
                int count = callCount.incrementAndGet();
                if (count <= 2) {
                    // First two calls: max_tokens
                    callback.onEvent(new LlmStreamEvent.TextDelta("partial"));
                    callback.onEvent(new LlmStreamEvent.MessageDelta(
                            new Usage(10, 5, 0, 0), "max_tokens"));
                } else {
                    // Third call: end_turn
                    callback.onEvent(new LlmStreamEvent.TextDelta("done"));
                    callback.onEvent(new LlmStreamEvent.MessageDelta(
                            new Usage(10, 5, 0, 0), "end_turn"));
                }
                callback.onComplete();
                return null;
            }).when(mockProvider).streamChat(
                    anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));

            when(hookService.executeStopHooks(anyList(), anyString()))
                    .thenReturn(HookRegistry.StopHookResult.ok());

            QueryConfig config = buildConfig();
            QueryLoopState state = buildState("question");

            QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(callCount.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("#5 max_tokens escalate → ESCALATED_MAX_TOKENS")
        void maxTokens_escalate_usesEscalatedMaxTokens() {
            AtomicInteger callCount = new AtomicInteger();
            LlmProvider mockProvider = mock(LlmProvider.class);
            when(providerRegistry.getProvider(anyString())).thenReturn(mockProvider);
            when(messageNormalizer.normalizeTyped(anyList())).thenReturn(List.of());

            StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession(any())).thenReturn(session);

            when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                var supplier = inv.getArgument(0, Supplier.class);
                return supplier.get();
            });

            doAnswer(inv -> {
                StreamChatCallback callback = inv.getArgument(7);
                int count = callCount.incrementAndGet();
                if (count == 1) {
                    callback.onEvent(new LlmStreamEvent.TextDelta("partial"));
                    callback.onEvent(new LlmStreamEvent.MessageDelta(
                            new Usage(10, 5, 0, 0), "max_tokens"));
                } else {
                    callback.onEvent(new LlmStreamEvent.TextDelta("done"));
                    callback.onEvent(new LlmStreamEvent.MessageDelta(
                            new Usage(10, 5, 0, 0), "end_turn"));
                }
                callback.onComplete();
                return null;
            }).when(mockProvider).streamChat(
                    anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));

            when(hookService.executeStopHooks(anyList(), anyString()))
                    .thenReturn(HookRegistry.StopHookResult.ok());

            QueryConfig config = buildConfig();
            QueryLoopState state = buildState("question");

            QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

            assertThat(result.isSuccess()).isTrue();
            // After first max_tokens, maxTokensOverride should be set to ESCALATED_MAX_TOKENS
            assertThat(state.getEffectiveMaxTokens(8192)).isEqualTo(QueryConfig.ESCALATED_MAX_TOKENS);
        }

        @Test
        @DisplayName("#6 Fallback 模型降级 → thinking blocks 剥离")
        void fallbackModel_stripsThinkingBlocks() {
            AtomicInteger callCount = new AtomicInteger();
            LlmProvider mockProvider = mock(LlmProvider.class);
            LlmProvider fallbackProvider = mock(LlmProvider.class);
            when(providerRegistry.getProvider("mock-model")).thenReturn(mockProvider);
            when(providerRegistry.getProvider("fallback-model")).thenReturn(fallbackProvider);
            when(messageNormalizer.normalizeTyped(anyList())).thenReturn(List.of());

            StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession(any())).thenReturn(session);

            // First call: throw 529 overloaded (fallback trigger)
            when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                var supplier = inv.getArgument(0, Supplier.class);
                if (callCount.incrementAndGet() == 1) {
                    throw new LlmApiException("overloaded", true, 529);
                }
                return supplier.get();
            });

            doAnswer(inv -> {
                StreamChatCallback callback = inv.getArgument(7);
                callback.onEvent(new LlmStreamEvent.TextDelta("fallback response"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(
                        new Usage(10, 5, 0, 0), "end_turn"));
                callback.onComplete();
                return null;
            }).when(fallbackProvider).streamChat(
                    anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));

            when(hookService.executeStopHooks(anyList(), anyString()))
                    .thenReturn(HookRegistry.StopHookResult.ok());
            lenient().doNothing().when(hookService).executeNotification(anyString(), anyString());

            // Config with fallback model
            QueryConfig config = new QueryConfig(
                    "mock-model", "fallback-model", "You are helpful.",
                    List.of(), List.of(),
                    8192, 200000,
                    new ThinkingConfig.Disabled(), 10, "test", null, List.of()
            );
            QueryLoopState state = buildState("question");

            QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

            assertThat(result.isSuccess()).isTrue();
            // Verify fallback provider was used
            verify(fallbackProvider).streamChat(
                    eq("fallback-model"), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));
        }

        @Test
        @DisplayName("#7a Abort 中断标记保留 — 循环正常完成")
        void abort_generatesSyntheticResults() {
            LlmProvider mockProvider = mock(LlmProvider.class);
            when(providerRegistry.getProvider(anyString())).thenReturn(mockProvider);
            when(messageNormalizer.normalizeTyped(anyList())).thenReturn(List.of());

            StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession(any())).thenReturn(session);
            when(session.isAllCompleted()).thenReturn(true);
            when(session.yieldCompleted()).thenReturn(List.of());

            when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                var supplier = inv.getArgument(0, Supplier.class);
                return supplier.get();
            });

            // Simulate: stream returns a tool_use
            doAnswer(inv -> {
                StreamChatCallback callback = inv.getArgument(7);
                callback.onEvent(new LlmStreamEvent.ToolUseStart("tool-1", "BashTool"));
                callback.onEvent(new LlmStreamEvent.ToolInputDelta("tool-1", "{\"command\":\"ls\"}"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(
                        new Usage(10, 5, 0, 0), "tool_use"));
                callback.onComplete();
                return null;
            }).when(mockProvider).streamChat(
                    anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));

            // maxTurns = 1 — 循环一轮后因 tool_use 触发 maxTurns 检查
            QueryConfig config = QueryConfig.withDefaults(
                    "mock-model", "You are a helpful assistant.",
                    List.of(), List.of(),
                    8192, 200000,
                    new ThinkingConfig.Disabled(), 1, "test"
            );
            QueryLoopState state = buildState("question");
            state.setAbortReason(AbortReason.USER_INTERRUPT);

            QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

            // ContextCascade 引入后，循环正常执行， abort 标记保留在 state 上
            assertThat(result.stopReason()).isEqualTo("max_turns");
            assertThat(state.getAbortReason()).isEqualTo(AbortReason.USER_INTERRUPT);
        }

        @Test
        @DisplayName("#7b Submit-interrupt → 不注入中断消息")
        void submitInterrupt_doesNotInjectInterruptMessage() {
            LlmProvider mockProvider = mock(LlmProvider.class);
            lenient().when(providerRegistry.getProvider(anyString())).thenReturn(mockProvider);
            lenient().when(messageNormalizer.normalizeTyped(anyList())).thenReturn(List.of());

            StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
            lenient().when(streamingToolExecutor.newSession(any())).thenReturn(session);
            lenient().when(session.yieldCompleted()).thenReturn(List.of());

            lenient().when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                var supplier = inv.getArgument(0, Supplier.class);
                return supplier.get();
            });

            lenient().doAnswer(inv -> {
                StreamChatCallback callback = inv.getArgument(7);
                callback.onEvent(new LlmStreamEvent.TextDelta("partial"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(
                        new Usage(10, 5, 0, 0), "end_turn"));
                callback.onComplete();
                return null;
            }).when(mockProvider).streamChat(
                    anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));

            QueryConfig config = buildConfig();
            QueryLoopState state = buildState("question");
            state.setAbortReason(AbortReason.SUBMIT_INTERRUPT);

            lenient().when(hookService.executeStopHooks(anyList(), anyString()))
                    .thenReturn(HookRegistry.StopHookResult.ok());

            QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

            // Submit-interrupt should NOT inject "[User interrupted" message
            boolean hasInterruptMsg = state.getMessages().stream()
                    .filter(m -> m instanceof Message.UserMessage)
                    .map(m -> (Message.UserMessage) m)
                    .anyMatch(um -> um.content().stream()
                            .anyMatch(b -> b instanceof ContentBlock.TextBlock tb
                                    && tb.text().contains("[User interrupted")));
            assertThat(hasInterruptMsg).isFalse();
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
