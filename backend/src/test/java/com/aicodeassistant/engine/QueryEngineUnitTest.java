package com.aicodeassistant.engine;

import com.aicodeassistant.authorization.AuthorizationDiagnostic;
import com.aicodeassistant.authorization.AuthorizationService;
import com.aicodeassistant.authorization.AuthorizationSubject;
import com.aicodeassistant.authorization.AuthorizedOperation;
import com.aicodeassistant.authorization.EffectClass;
import com.aicodeassistant.authorization.FrozenToolInputFactory;
import com.aicodeassistant.authorization.OperationDescriptor;
import com.aicodeassistant.authorization.OperationAnalyzerRegistry;
import com.aicodeassistant.authorization.PreparedOperation;
import com.aicodeassistant.authorization.RiskClass;
import com.aicodeassistant.authorization.ToolExecutionGateway;
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
import com.aicodeassistant.security.SensitiveDataFilter;
import com.aicodeassistant.skill.SkillDefinition;
import com.aicodeassistant.skill.SkillExecutor;
import com.aicodeassistant.skill.SkillRegistry;
import com.aicodeassistant.skill.SkillStateService;
import com.aicodeassistant.skill.SkillTool;
import com.aicodeassistant.skill.SkillToolValidator;
import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.recovery.ToolRecoveryFramework;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    @Mock UserImageTranscoder userImageTranscoder;
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
                new DefaultTerminationStrategy(), new ToolPriorityScheduler(), null, new AgentTimeoutConfig(), tokenBudgetGuard, imageRefInjector, null, null, userImageTranscoder);  // Run authority is covered by integration tests
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
        lenient().when(tokenBudgetGuard.enforcePhase1(anyList(), anyInt(), anyDouble(), nullable(String.class)))
                .thenAnswer(inv -> new TokenBudgetGuard.GuardResult(inv.getArgument(0), false, 0, 0));
        lenient().when(tokenBudgetGuard.enforcePhase2(anyList(), anyInt()))
                .thenAnswer(inv -> new TokenBudgetGuard.FinalBudgetResult(inv.getArgument(0), Set.of(), 0, inv.getArgument(1), true, ""));
        lenient().when(tokenBudgetGuard.enforcePhase2(anyList(), anyInt(), anySet(), anyDouble()))
                .thenAnswer(inv -> new TokenBudgetGuard.FinalBudgetResult(inv.getArgument(0), Set.of(), 0, inv.getArgument(1), true, ""));
        // 默认 ImageRefInjector mock: 直接返回原消息
        lenient().when(imageRefInjector.injectForApiCall(
                        anyList(), anyInt(), anyInt(), anySet(), anyMap(), nullable(String.class), anyInt()))
                .thenAnswer(inv -> new ImageRefInjector.InjectResult(inv.getArgument(0), Set.of()));
        // 默认 UserImageTranscoder mock: 直接返回原消息
        lenient().when(userImageTranscoder.transcode(anyList(), any(), nullable(String.class), any(), anyInt()))
                .thenAnswer(inv -> new UserImageTranscoder.TranscodeResult(inv.getArgument(0), 0, List.of()));
        // 默认 ContextCascade mock: 直接返回原消息列表（无压缩）
        lenient().when(contextCascade.executePreApiCascade(anyList(), anyString(), any(), any()))
                .thenAnswer(inv -> {
                    List<Message> msgs = inv.getArgument(0);
                    int tokens = msgs.size() * 100;
                    return new ContextCascade.CascadeResult(
                            msgs, tokens, tokens, false, 0, false, 0, false, 0, false, false, null);
                });
    }

    private static RunEnvelope terminalSnapshot(RunEnvelope run, RunEnvelope.RunStatus status,
                                               RunEnvelope.RunExitReason reason, String error) {
        return new RunEnvelope(run.id(),run.sessionId(),null,status,run.agentType(),run.model(),null,
                run.startedAt(),Instant.now(),null,0,0,0,0,error,run.createdAt(),Instant.now(),1,reason,reason,
                RunEnvelope.VerificationStatus.NOT_REQUESTED,RunEnvelope.UsageStatus.UNKNOWN,Instant.now(),null);
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
        void editDiffReachesUiAndPersistedMessagesButNotNextModelRequest() throws Exception {
            var uiDiff = Map.<String, Object>of("schema", "edit-diff/v1", "filePath", "/tmp/edit.txt",
                    "diff", "--- file\n+++ file\n@@ -1 +1 @@\n-PRIVATE_OLD_LINE\n+PRIVATE_NEW_LINE",
                    "truncated", false);
            var completed = mock(StreamingToolExecutor.TrackedTool.class);
            when(completed.getToolUseId()).thenReturn("edit-1");
            when(completed.getResult()).thenReturn(ToolResult.successWithEffect("Edited: /tmp/edit.txt",
                    ToolResult.EffectState.APPLIED).withMetadata("structuredResult", uiDiff)
                    .withMetadata("internalSecret", "must-not-cross"));
            var session = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession(any())).thenReturn(session);
            when(session.isAllCompleted()).thenReturn(true);
            when(session.yieldCompleted()).thenReturn(List.of(completed), List.of());
            when(messageNormalizer.normalizeTyped(anyList()))
                    .thenAnswer(inv -> new MessageNormalizer().normalizeTyped(inv.getArgument(0)));
            when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any()))
                    .thenAnswer(inv -> inv.getArgument(0, Supplier.class).get());
            when(toolResultSummarizer.processToolResults(anyList(), anyInt()))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(hookService.executeStopHooks(anyList(), anyString())).thenReturn(HookRegistry.StopHookResult.ok());
            Tool edit = mock(Tool.class);
            when(edit.getName()).thenReturn("Edit");
            var config = QueryConfig.withDefaults("mock-model", "test", List.of(edit), List.of(),
                    8192, 200000, new ThinkingConfig.Disabled(), 3, "test");
            List<List<Map<String, Object>>> requests = new ArrayList<>();
            LlmProvider provider = mock(LlmProvider.class);
            when(providerRegistry.getProvider(anyString())).thenReturn(provider);
            doAnswer(inv -> {
                requests.add(List.copyOf(inv.getArgument(1)));
                StreamChatCallback callback = inv.getArgument(7);
                if (requests.size() == 1) {
                    callback.onEvent(new LlmStreamEvent.ToolUseStart("edit-1", "Edit"));
                    callback.onEvent(new LlmStreamEvent.ToolInputDelta("edit-1", "{}"));
                } else callback.onEvent(new LlmStreamEvent.TextDelta("done"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(10, 5, 0, 0),
                        requests.size() == 1 ? "tool_use" : "end_turn"));
                callback.onComplete();
                return null;
            }).when(provider).streamChat(anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));
            var state = buildState("edit the file");
            List<Message> persisted = new ArrayList<>();
            state.addMessageListener(persisted::add);
            assertThat(queryEngine.execute(config, state, handler).isSuccess()).isTrue();
            assertThat(handler.toolResults).hasSize(1);
            var result = handler.toolResults.getFirst();
            assertThat(result.metadata()).containsExactlyEntriesOf(Map.of("structuredResult", uiDiff));
            assertThat(persisted).anyMatch(message -> message instanceof Message.UserMessage user
                    && user.content().contains(result));
            var restored = objectMapper.readValue(objectMapper.writeValueAsString(result), ContentBlock.class);
            assertThat(restored).isEqualTo(result);
            assertThat(requests).hasSize(2);
            assertThat(objectMapper.writeValueAsString(requests.get(1)))
                    .contains("Edited: /tmp/edit.txt")
                    .doesNotContain("PRIVATE_OLD_LINE", "PRIVATE_NEW_LINE", "structuredResult", "must-not-cross");
        }

        @Test
        void resumesIncompleteHistoryWithoutReexecutingOrPersistingHistoricalTool() {
            LlmProvider provider = mock(LlmProvider.class);
            when(providerRegistry.getProvider(anyString())).thenReturn(provider);
            when(messageNormalizer.normalizeTyped(anyList()))
                    .thenAnswer(inv -> new MessageNormalizer().normalizeTyped(inv.getArgument(0)));
            var session = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession(any())).thenReturn(session);
            lenient().when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any()))
                    .thenAnswer(inv -> inv.getArgument(0, Supplier.class).get());
            var outbound = new AtomicReference<List<Map<String, Object>>>();
            doAnswer(inv -> {
                outbound.set(inv.getArgument(1));
                StreamChatCallback callback = inv.getArgument(7);
                callback.onEvent(new LlmStreamEvent.TextDelta("I will verify the current state before continuing."));
                callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(10, 5, 0, 0), "end_turn"));
                callback.onComplete();
                return null;
            }).when(provider).streamChat(anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));
            when(hookService.executeStopHooks(anyList(), anyString())).thenReturn(HookRegistry.StopHookResult.ok());

            var historicalCall = new Message.AssistantMessage("historical-assistant", Instant.EPOCH,
                    List.of(new ContentBlock.ToolUseBlock("historical-call", "Bash",
                            objectMapper.createObjectNode().put("command", "npm test"))), "tool_use", null);
            var latest = new Message.UserMessage("latest", Instant.EPOCH,
                    List.of(new ContentBlock.TextBlock("Continue")), null, null);
            var state = new QueryLoopState(List.of(historicalCall, latest), ToolUseContext.of("/tmp", "test-session"));
            List<Message> persistenceEvents = new ArrayList<>();
            state.addMessageListener(persistenceEvents::add);

            var result = queryEngine.execute(buildConfig(), state, handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(handler.errors).isEmpty();
            var results = objectMapper.valueToTree(outbound.get()).findParents("tool_use_id");
            assertThat(results).hasSize(1);
            assertThat(results.getFirst().path("tool_use_id").asText()).isEqualTo("historical-call");
            assertThat(results.getFirst().path("is_error").asBoolean()).isTrue();
            assertThat(results.getFirst().path("content").asText()).contains("execution outcome unknown", "verify before retrying");
            assertThat(state.getMessages()).contains(historicalCall, latest);
            assertThat(state.getMessages().stream().filter(Message.UserMessage.class::isInstance).toList()).containsExactly(latest);
            assertThat(persistenceEvents).hasSize(1).allMatch(Message.AssistantMessage.class::isInstance);
            verify(session, never()).addTool(any(), any(), anyString(), any());
            verify(streamingToolExecutor, never()).executeDetached(any(), any(), anyString(), any());
        }

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
            assertThat(result.error()).startsWith("OUTPUT_RECOVERY_EXHAUSTED:");
            assertThat(callCount.get()).isEqualTo(2);
            assertThat(handler.errors).hasSize(1);
        }
    }

    @Nested
    @DisplayName("空最终正文修复的行为回归")
    class EmptyFinalResponseReviewTests {
        private RunExecutionRegistry executions;
        private RunEnvelope run;
        private AtomicReference<RunEnvelope> authority;
        private StreamingToolExecutor.ExecutionSession toolSession;
        private Tool bashTool;
        private final List<List<Map<String, Object>>> requests = new ArrayList<>();
        private final com.aicodeassistant.tool.agent.BackgroundAgentTracker backgrounds =
                spy(new com.aicodeassistant.tool.agent.BackgroundAgentTracker(mock(org.springframework.messaging.simp.SimpMessagingTemplate.class)));
        private final AgentTimeoutConfig backgroundTimeouts = new AgentTimeoutConfig();
        @org.junit.jupiter.api.io.TempDir java.nio.file.Path backgroundOutputs;


        @BeforeEach
        void useRealNormalizationAndRunAdmission() {
            executions = new RunExecutionRegistry();
            run = RunEnvelope.start("test-session", null, "query", "mock-model");
            when(runTracker.startRun("test-session", null, "query", "mock-model"))
                    .thenReturn(run);
            authority = new AtomicReference<>(run);
            lenient().when(runTracker.getRun(run.id())).thenAnswer(inv -> java.util.Optional.of(authority.get()));
            lenient().doAnswer(inv -> { authority.set(terminalSnapshot(run, RunEnvelope.RunStatus.COMPLETED,
                    RunEnvelope.RunExitReason.MODEL_FINISHED, null)); return null; })
                    .when(runTracker).completeRun(eq(run.id()), anyInt(), anyDouble(), anyInt(), anyInt());
            lenient().doAnswer(inv -> { authority.set(terminalSnapshot(run, RunEnvelope.RunStatus.FAILED,
                    RunEnvelope.RunExitReason.INTERNAL_ERROR, inv.getArgument(1))); return null; })
                    .when(runTracker).failRun(eq(run.id()), anyString());
            lenient().doAnswer(inv -> { authority.set(terminalSnapshot(run, RunEnvelope.RunStatus.FAILED,
                    inv.getArgument(1), inv.getArgument(2))); return null; })
                    .when(runTracker).failRun(eq(run.id()), any(RunEnvelope.RunExitReason.class), anyString());
            lenient().doAnswer(inv -> { boolean timeout = inv.getArgument(1) == AbortReason.TIMEOUT;
                authority.set(terminalSnapshot(run, timeout ? RunEnvelope.RunStatus.FAILED : RunEnvelope.RunStatus.CANCELLED,
                    timeout ? RunEnvelope.RunExitReason.DEADLINE_EXCEEDED : RunEnvelope.RunExitReason.USER_CANCELLED, inv.getArgument(2))); return null; })
                    .when(runTracker).abortRun(eq(run.id()), any(), anyString());
            queryEngine = new QueryEngine(
                    providerRegistry, compactService, apiRetryService, tokenCounter,
                    objectMapper, streamingToolExecutor, new MessageNormalizer(), hookService,
                    snipService, microCompactService, modelRegistry,
                    thinkingBudgetCalculator, modelTierService, fileHistoryService,
                    toolResultSummarizer, contextCascade, compactMetrics,
                    null, null, backgrounds, featureFlagService,
                    new DefaultTerminationStrategy(), new ToolPriorityScheduler(),
                    null, backgroundTimeouts, tokenBudgetGuard, imageRefInjector,
                    runTracker, executions, userImageTranscoder);
            toolSession = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession(any())).thenReturn(toolSession);
            bashTool = mock(Tool.class);
            lenient().when(bashTool.getName()).thenReturn("Bash");
            lenient().when(bashTool.getAliases()).thenReturn(List.of());
            lenient().when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any()))
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
            assertThat(result.error()).startsWith("OUTPUT_RECOVERY_EXHAUSTED:");
            verify(runTracker).failRun(eq(run.id()), eq(RunEnvelope.RunExitReason.INCOMPLETE),
                    startsWith("OUTPUT_RECOVERY_EXHAUSTED:"));
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
            assertThat(result.error()).startsWith("HOOK_STOPPED:");
            verify(runTracker).failRun(eq(run.id()), eq(RunEnvelope.RunExitReason.INCOMPLETE),
                    startsWith("HOOK_STOPPED:"));
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
            assertThat(result.error()).startsWith("MAX_TURNS:");
            verify(runTracker).failRun(eq(run.id()), eq(RunEnvelope.RunExitReason.MAX_TURNS),
                    startsWith("MAX_TURNS:"));
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
            assertThat(result.error()).startsWith("TOKEN_BUDGET_EXHAUSTED:");
            verify(runTracker).failRun(eq(run.id()), eq(RunEnvelope.RunExitReason.TOKEN_BUDGET_EXHAUSTED),
                    startsWith("TOKEN_BUDGET_EXHAUSTED:"));
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
        @DisplayName("正文中的 final marker 原样保留并作为可见回答")
        void markerOnlyFinalIsPreserved() {
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

            assertThat(result.isSuccess()).isTrue();
            assertThat(requests).hasSize(1);
            Message.AssistantMessage first = result.messages().stream()
                    .filter(Message.AssistantMessage.class::isInstance)
                    .map(Message.AssistantMessage.class::cast).findFirst().orElseThrow();
            assertThat(first.content()).contains(new ContentBlock.TextBlock("[final]"));
        }

        @Test
        @DisplayName("尾部截断标记触发一次恢复，原文完整持久化并交付")
        void trailingTruncationEchoTriggersRecovery() {
            String original = "表格内容\n...[content truncated by system]";
            script((call, callback) -> finish(callback, "end_turn", new LlmStreamEvent.TextDelta(
                    call == 1 ? original : "完整表格内容")));
            QueryLoopState state = buildState("question");
            List<Message> persisted = new ArrayList<>();
            state.setPersistenceSink(persisted::add);

            QueryEngine.QueryResult result = queryEngine.execute(
                    buildConfig(), state, handler);

            assertThat(requests).hasSize(2);
            assertThat(result.isSuccess()).isTrue();
            List<Message.AssistantMessage> assistants = result.messages().stream()
                    .filter(Message.AssistantMessage.class::isInstance)
                    .map(Message.AssistantMessage.class::cast)
                    .toList();
            assertThat(assistants).hasSize(2);
            assertThat(assistants.get(0).content())
                    .containsExactly(new ContentBlock.TextBlock(original));
            assertThat(assistants.get(1).content())
                    .containsExactly(new ContentBlock.TextBlock("完整表格内容"));
            assertThat(persisted.stream().filter(Message.AssistantMessage.class::isInstance).toList())
                    .containsExactlyElementsOf(assistants);
            assertThat(handler.assistantMessages).containsExactlyElementsOf(assistants);
            assertThat(handler.textDeltas).containsExactly(original, "完整表格内容");
            assertThat(requests.get(1).toString()).contains(original);
            assertThat(state.getCurrentRunFinalMessageId()).isEqualTo(assistants.get(1).uuid());
        }

        @Test
        @DisplayName("尾部压缩标记触发一次恢复并保留原文")
        void trailingCompressionEchoTriggersRecovery() {
            script((call, callback) -> finish(callback, "end_turn", new LlmStreamEvent.TextDelta(
                    call == 1 ? "部分内容[content compressed by system]" : "完整内容")));

            QueryEngine.QueryResult result = queryEngine.execute(
                    buildConfig(), buildState("question"), handler);

            assertThat(requests).hasSize(2);
            assertThat(result.isSuccess()).isTrue();
            List<Message.AssistantMessage> assistants = result.messages().stream()
                    .filter(Message.AssistantMessage.class::isInstance)
                    .map(Message.AssistantMessage.class::cast)
                    .toList();
            assertThat(assistants).hasSize(2);
            assertThat(assistants.get(0).content())
                    .containsExactly(new ContentBlock.TextBlock("部分内容[content compressed by system]"));
            assertThat(assistants.get(1).content())
                    .containsExactly(new ContentBlock.TextBlock("完整内容"));
        }

        @Test
        @DisplayName("恢复重试仍返回尾部标记回声时明确失败")
        void recoveryExhaustedOnPersistentEcho() {
            script((call, callback) -> finish(callback, "end_turn", new LlmStreamEvent.TextDelta(
                    "第" + call + "段内容...[content truncated by system]")));
            QueryLoopState state = buildState("question");
            List<Message> persisted = new ArrayList<>();
            state.setPersistenceSink(persisted::add);

            QueryEngine.QueryResult result = queryEngine.execute(
                    buildConfig(), state, handler);

            assertThat(requests).hasSize(2);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).startsWith("OUTPUT_RECOVERY_EXHAUSTED:");
            assertThat(persisted.stream().filter(Message.AssistantMessage.class::isInstance)
                    .map(Message.AssistantMessage.class::cast).map(Message.AssistantMessage::content).toList())
                    .containsExactly(
                            List.of(new ContentBlock.TextBlock("第1段内容...[content truncated by system]")),
                            List.of(new ContentBlock.TextBlock("第2段内容...[content truncated by system]")));
            assertThat(state.getCurrentRunFinalMessageId()).isNull();
        }

        @Test
        @DisplayName("连续尾标记走完整补答流程且不会进入递归占位符匹配")
        void repeatedTrailingMarkersRecoverWithoutStackOverflow() {
            String original = "[content truncated by system]".repeat(2500);
            script((call, callback) -> finish(callback, "end_turn", new LlmStreamEvent.TextDelta(
                    call == 1 ? original : "Recovered answer")));

            QueryEngine.QueryResult result = queryEngine.execute(buildConfig(), buildState("question"), handler);

            assertThat(requests).hasSize(2);
            assertThat(result.isSuccess()).isTrue();
            assertThat(handler.assistantMessages.getFirst().content())
                    .containsExactly(new ContentBlock.TextBlock(original));
        }

        @Test
        @DisplayName("正文中间的方括号段名保持原样且不触发恢复")
        void midTextBracketPreserved() {
            String content = "配置如下:\n[server]\nhost=localhost\n[collapsed]\nkey=value";
            script((call, callback) -> finish(callback, "end_turn",
                    new LlmStreamEvent.TextDelta(content)));

            QueryEngine.QueryResult result = queryEngine.execute(
                    buildConfig(), buildState("question"), handler);

            assertThat(requests).hasSize(1);
            assertThat(result.isSuccess()).isTrue();
            Message.AssistantMessage response = result.messages().stream()
                    .filter(Message.AssistantMessage.class::isInstance)
                    .map(Message.AssistantMessage.class::cast)
                    .findFirst().orElseThrow();
            assertThat(response.content()).containsExactly(new ContentBlock.TextBlock(content));
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
        @DisplayName("整段正文仅为系统折叠占位符时按空正文恢复，而非判定成功")
        void collapseMarkerOnlyFinalTriggersRecovery() {
            script((call, callback) -> {
                if (call == 1) {
                    finish(callback, "end_turn",
                            new LlmStreamEvent.ThinkingDelta("real analysis"),
                            new LlmStreamEvent.TextDelta("[content compressed by system]"));
                } else {
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("Recovered answer"));
                }
            });

            assertRecovered(queryEngine.execute(buildConfig(), buildState("question"), handler));
        }

        @Test
        @DisplayName("多个折叠占位符组合独占正文同样按空正文恢复")
        void combinedMarkersOnlyAlsoRecovers() {
            script((call, callback) -> {
                if (call == 1) {
                    finish(callback, "end_turn",
                            new LlmStreamEvent.TextDelta("[collapsed]\n[skeleton]"));
                } else {
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("Recovered answer"));
                }
            });

            assertRecovered(queryEngine.execute(buildConfig(), buildState("question"), handler));
        }

        @Test
        @DisplayName("INI 段名与正文中引用的占位符保持原样，不触发恢复")
        void iniSectionNamesStayFidelity() {
            String ini = "[server]\nhost=example.com\n[collapsed]\nretry=3";
            script((call, callback) -> finish(callback, "end_turn",
                    new LlmStreamEvent.TextDelta(ini)));

            QueryEngine.QueryResult result = queryEngine.execute(
                    buildConfig(), buildState("question"), handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(requests).hasSize(1);
            Message.AssistantMessage first = result.messages().stream()
                    .filter(Message.AssistantMessage.class::isInstance)
                    .map(Message.AssistantMessage.class::cast).findFirst().orElseThrow();
            assertThat(first.content()).contains(new ContentBlock.TextBlock(ini));
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
            assertThat(result.error()).startsWith("OUTPUT_RECOVERY_EXHAUSTED:");
            assertThat(handler.errors).hasSize(1);
            verify(runTracker).failRun(eq(run.id()), eq(RunEnvelope.RunExitReason.INCOMPLETE),
                    startsWith("OUTPUT_RECOVERY_EXHAUSTED:"));
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
            assertThat(result.error()).startsWith("MAX_TURNS:");
            verify(runTracker).failRun(eq(run.id()), eq(RunEnvelope.RunExitReason.MAX_TURNS),
                    startsWith("MAX_TURNS:"));
            verify(runTracker, never()).completeRun(anyString(), anyInt(), anyDouble(), anyInt(), anyInt());
            assertRunAdmissionClosed();
        }

        @Test
        void maxTurnsProjectionIgnoresAbortCausedByTerminalTransition() {
            completedToolResult();
            script((call, callback) -> finish(callback, "tool_use",
                    new LlmStreamEvent.ToolUseStart("review-tool-1", "Bash"),
                    new LlmStreamEvent.ToolInputDelta("review-tool-1", "{}")));
            doAnswer(inv -> {
                authority.set(terminalSnapshot(run, RunEnvelope.RunStatus.FAILED,
                        RunEnvelope.RunExitReason.MAX_TURNS, inv.getArgument(2)));
                executions.abortRun(run.id(), AbortReason.ERROR);
                return null;
            }).when(runTracker).failRun(eq(run.id()), eq(RunEnvelope.RunExitReason.MAX_TURNS),
                    startsWith("MAX_TURNS:"));

            QueryEngine.QueryResult result = queryEngine.execute(
                    withMaxTurns(1), buildState("question"), handler);

            assertThat(result.stopReason()).isEqualTo("max_turns");
            assertThat(result.error()).startsWith("MAX_TURNS:");
            assertThat(result.isSuccess()).isFalse();
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

        @Test void gracefulDeadlineRetainsPartialUsageButNeverSuccess() {
            script((call, callback) -> {
                finish(callback,"end_turn",new LlmStreamEvent.TextDelta("partial result"));
                queryEngine.abort("test-session",AbortReason.TIMEOUT);
            });
            var result=queryEngine.execute(buildConfig(),buildState("question"),handler);
            assertThat(result.stopReason()).isEqualTo("timeout");
            assertThat(result.error()).isEqualTo("DEADLINE_EXCEEDED");
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.totalUsage().totalTokens()).isEqualTo(15);
            assertThat(result.messages().toString()).contains("partial result");
        }

        @Test void cancellationExceptionUsesSameTerminalProjection() {
            String original = "取消前正文...[content truncated by system]";
            script((call,callback) -> {
                callback.onEvent(new LlmStreamEvent.TextDelta(original));
                queryEngine.abort("test-session",AbortReason.USER_INTERRUPT);
                throw new java.util.concurrent.CancellationException("cancelled HTTP"); });
            var state = buildState("question");
            List<Message> persisted = new ArrayList<>();
            state.setPersistenceSink(persisted::add);
            var result=queryEngine.execute(buildConfig(),state,handler);
            assertThat(result.stopReason()).isEqualTo("cancelled");
            assertThat(requests).hasSize(1);
            assertThat(handler.assistantMessages).hasSize(1);
            assertThat(handler.assistantMessages.getFirst().content())
                    .containsExactly(new ContentBlock.TextBlock(original));
            assertThat(persisted).contains(handler.assistantMessages.getFirst());
            assertThat(result.error()).isEqualTo("USER_CANCELLED");
            assertThat(result.isSuccess()).isFalse();
        }

        @Test void terminalWriteFailureReadsCommittedWinnerAndDoesNotRewrite() {
            script((call,callback) -> finish(callback,"end_turn",new LlmStreamEvent.TextDelta("answer")));
            doAnswer(inv -> { authority.set(terminalSnapshot(run,RunEnvelope.RunStatus.FAILED,
                    RunEnvelope.RunExitReason.DEADLINE_EXCEEDED,"deadline"));
                throw new IllegalStateException("write lost CAS or acknowledgement"); })
                .when(runTracker).completeRun(anyString(),anyInt(),anyDouble(),anyInt(),anyInt());
            var result=queryEngine.execute(buildConfig(),buildState("question"),handler);
            assertThat(result.stopReason()).isEqualTo("timeout");
            verify(runTracker,never()).failRun(anyString(),anyString());
            verify(runTracker,times(1)).completeRun(anyString(),anyInt(),anyDouble(),anyInt(),anyInt());
        }

        @Test void unconfirmedWriteOrReadCannotReturnSuccess() {
            script((call,callback) -> finish(callback,"end_turn",new LlmStreamEvent.TextDelta("partial answer")));
            doThrow(new IllegalStateException("database unavailable")).when(runTracker)
                .completeRun(anyString(),anyInt(),anyDouble(),anyInt(),anyInt());
            var result=queryEngine.execute(buildConfig(),buildState("question"),handler);
            assertThat(result.error()).contains("RUN_TERMINATION_UNCONFIRMED");
            assertThat(result.stopReason()).isEqualTo("error");
            assertThat(handler.errors).hasSize(1);
        }

        @Test void completedCasWinnerRemainsCompletedAfterLateCancellation() {
            script((call,callback) -> { finish(callback,"end_turn",new LlmStreamEvent.TextDelta("answer"));
                queryEngine.abort("test-session",AbortReason.USER_INTERRUPT); });
            doAnswer(inv -> { authority.set(terminalSnapshot(run,RunEnvelope.RunStatus.COMPLETED,
                    RunEnvelope.RunExitReason.MODEL_FINISHED,null)); return null; })
                .when(runTracker).abortRun(anyString(),any(),anyString());
            assertThat(queryEngine.execute(buildConfig(),buildState("question"),handler).isSuccess()).isTrue();
        }

        @Test
        void partialToolStreamFallbackPreservesCallsCompletedResultsAndUnknownOutcomes() {
            String original = "降级前正文...[content truncated by system]";
            script((call, callback) -> {
                if (call == 1) {
                    callback.onEvent(new LlmStreamEvent.TextDelta(original));
                    for (String id : List.of("done", "failed", "pending")) {
                        callback.onEvent(new LlmStreamEvent.ToolUseStart(id, "Bash"));
                        callback.onEvent(new LlmStreamEvent.ToolInputDelta(id, "{}"));
                        if (!id.equals("pending")) callback.onEvent(new LlmStreamEvent.BlockStop(0));
                    }
                    throw new LlmApiException("overloaded", true, 529);
                }
                finish(callback, "end_turn", new LlmStreamEvent.TextDelta("fallback answer"));
            });
            QueryConfig config = new QueryConfig("mock-model", "fallback-model", "You are helpful.",
                    List.of(bashTool), List.of(), 8192, 200000, new ThinkingConfig.Disabled(), 10, "test", null, List.of());
            var state = buildState("question");
            List<Message> persisted = new ArrayList<>();
            state.setPersistenceSink(persisted::add);
            var result = queryEngine.execute(config, state, handler);
            assertThat(result.isSuccess()).isTrue();
            assertThat(requests).hasSize(2);
            verify(toolSession, never()).addTool(any(), any(), anyString(), any());
            verify(toolSession, never()).addErrorResult(anyString(), anyString());
            assertThat(requests.get(1).toString()).doesNotContain(
                    "completed evidence", "actual failure", "execution outcome unconfirmed");
            assertThat(requests.get(1).toString()).contains(original);
            assertThat(persisted.getFirst()).isInstanceOfSatisfying(Message.AssistantMessage.class,
                    partial -> assertThat(partial.content()).containsExactly(new ContentBlock.TextBlock(original)));
            assertThat(state.getMessages()).noneMatch(message -> message instanceof Message.UserMessage user
                    && user.content().stream().anyMatch(ContentBlock.ToolResultBlock.class::isInstance));
        }

        @Test
        void oneInvalidToolInputRejectsTheWholeTurnBeforeAnyToolStarts() {
            script((call, callback) -> finish(callback, "tool_use",
                    new LlmStreamEvent.ToolUseStart("valid", "Bash"),
                    new LlmStreamEvent.ToolInputDelta("valid", "{}"),
                    new LlmStreamEvent.ToolUseStart("broken", "Bash"),
                    new LlmStreamEvent.ToolInputDelta("broken", "{")));

            var result = queryEngine.execute(buildConfig(), buildState("question"), handler);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).contains("INVALID_TOOL_INPUT_JSON");
            verify(toolSession, never()).addTool(any(), any(), anyString(), any());
        }

        @Test
        void truncatedToolTurnStartsNoTools() {
            script((call, callback) -> finish(callback, "max_tokens",
                    new LlmStreamEvent.ToolUseStart("truncated", "Bash"),
                    new LlmStreamEvent.ToolInputDelta("truncated", "{}")));

            var result = queryEngine.execute(buildConfig(), buildState("question"), handler);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).contains("TRUNCATED_TOOL_CALLS");
            verify(toolSession, never()).addTool(any(), any(), anyString(), any());
        }

        @Test
        void assistantPersistenceFailureStartsNoTools() {
            script((call, callback) -> finish(callback, "tool_use",
                    new LlmStreamEvent.ToolUseStart("durability", "Bash"),
                    new LlmStreamEvent.ToolInputDelta("durability", "{}")));
            QueryLoopState state = buildState("question");
            state.setPersistenceSink(message -> {
                throw new com.aicodeassistant.session.MessagePersistenceException(
                        "TEST_WRITE_FAILED", "injected assistant write failure");
            });

            var result = queryEngine.execute(buildConfig(), state, handler);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).contains("PERSISTENCE_FAILED", "TEST_WRITE_FAILED");
            verify(toolSession, never()).addTool(any(), any(), anyString(), any());
            assertThat(requests).hasSize(1);
        }

        @Test
        void stopHookPersistenceFailureCannotCompleteTheRun() {
            script((call, callback) -> finish(callback, "end_turn",
                    new LlmStreamEvent.TextDelta("initial answer")));
            when(hookService.executeStopHooks(anyList(), eq("test-session")))
                    .thenReturn(HookRegistry.StopHookResult.blocking(List.of("fix remaining issue")));
            QueryLoopState state = buildState("question");
            state.setPersistenceSink(message -> {
                if (message instanceof Message.UserMessage user
                        && user.content().stream().anyMatch(ContentBlock.TextBlock.class::isInstance)) {
                    throw new com.aicodeassistant.session.MessagePersistenceException(
                            "TEST_HOOK_WRITE_FAILED", "injected stop-hook write failure");
                }
            });

            var result = queryEngine.execute(buildConfig(), state, handler);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).contains("PERSISTENCE_FAILED", "TEST_HOOK_WRITE_FAILED");
            assertThat(requests).hasSize(1);
            verify(runTracker, never()).completeRun(anyString(), anyInt(), anyDouble(), anyInt(), anyInt());
        }

        @Test
        void unknownToolRejectsWholeBatchAndAllowsModelSelfCorrection() {
            script((call, callback) -> {
                if (call == 1) {
                    finish(callback, "tool_use",
                            new LlmStreamEvent.ToolUseStart("missing", "MissingTool"),
                            new LlmStreamEvent.ToolInputDelta("missing", "{}"),
                            new LlmStreamEvent.ToolUseStart("valid", "Bash"),
                            new LlmStreamEvent.ToolInputDelta("valid", "{}"));
                } else {
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("recovered"));
                }
            });
            QueryLoopState state = buildState("question");

            var result = queryEngine.execute(buildConfig(), state, handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(requests).hasSize(2);
            verify(toolSession, never()).addTool(any(), any(), anyString(), any());
            assertThat(handler.toolResults)
                    .hasSize(2)
                    .allMatch(ContentBlock.ToolResultBlock::isError);
        }

        @Test
        void toolResultPersistenceFailurePreventsAnotherModelRequest() {
            completedToolResult();
            script((call, callback) -> {
                if (call == 1) {
                    finish(callback, "tool_use",
                            new LlmStreamEvent.ToolUseStart("review-tool-1", "Bash"),
                            new LlmStreamEvent.ToolInputDelta("review-tool-1", "{}"));
                } else {
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("must not run"));
                }
            });
            QueryLoopState state = buildState("question");
            state.setPersistenceSink(message -> {
                if (message instanceof Message.UserMessage user
                        && user.content().stream().anyMatch(ContentBlock.ToolResultBlock.class::isInstance)) {
                    throw new com.aicodeassistant.session.MessagePersistenceException(
                            "TEST_RESULT_WRITE_FAILED", "injected result write failure");
                }
            });

            var result = queryEngine.execute(buildConfig(), state, handler);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).contains("PERSISTENCE_FAILED", "TEST_RESULT_WRITE_FAILED");
            assertThat(requests).hasSize(1);
            verify(toolSession, times(1)).addTool(eq(bashTool), any(), eq("review-tool-1"), any());
        }

        @Test
        void mandatoryContextOverBudgetFailsBeforeProviderCallWithActionableMessage() {
            LlmProvider provider = mock(LlmProvider.class);
            when(providerRegistry.getProvider(anyString())).thenReturn(provider);
            when(tokenBudgetGuard.enforcePhase2(anyList(), anyInt(), anySet(), anyDouble()))
                    .thenAnswer(inv -> new TokenBudgetGuard.FinalBudgetResult(
                            inv.getArgument(0), Set.of(), 20_000, inv.getArgument(1), false,
                            "mandatory context exceeds budget"));
            CompactService.CompactResult cannotCompact = new CompactService.CompactResult(
                    List.of(), 20_000, 20_000, 0, 1.0,
                    "mandatory_context_over_budget", 0);
            when(compactService.compact(anyList(), any(CompactionContext.class), eq(true)))
                    .thenReturn(cannotCompact);
            when(compactService.reactiveCompact(anyList(), any(CompactionContext.class), eq(false)))
                    .thenReturn(cannotCompact);
            QueryLoopState state = buildState("question");
            List<Message> original = List.copyOf(state.getMessages());

            var result = queryEngine.execute(buildConfig(), state, handler);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).contains(
                    "必须保留的上下文超过模型窗口，请使用已有新会话并重新提供必要要求。");
            assertThat(state.getMessages()).containsExactlyElementsOf(original);
            verify(provider, never()).streamChat(anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));
        }

        @Test
        void textToolTextOrderIsPreservedAndToolStartsExactlyOnce() {
            completedToolResult();
            when(toolResultSummarizer.processToolResults(anyList(), anyInt()))
                    .thenAnswer(inv -> inv.getArgument(0));
            script((call, callback) -> {
                if (call == 1) {
                    finish(callback, "tool_use",
                            new LlmStreamEvent.TextDelta("before"),
                            new LlmStreamEvent.ToolUseStart("review-tool-1", "Bash"),
                            new LlmStreamEvent.ToolInputDelta("review-tool-1", "{}"),
                            new LlmStreamEvent.TextDelta("after"));
                } else {
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("done"));
                }
            });

            var result = queryEngine.execute(buildConfig(), buildState("question"), handler);

            assertThat(result.isSuccess()).isTrue();
            Message.AssistantMessage toolTurn = result.messages().stream()
                    .filter(Message.AssistantMessage.class::isInstance)
                    .map(Message.AssistantMessage.class::cast)
                    .filter(message -> message.content().stream()
                            .anyMatch(ContentBlock.ToolUseBlock.class::isInstance))
                    .findFirst().orElseThrow();
            assertThat(toolTurn.content()).extracting(block -> block.getClass().getSimpleName())
                    .containsExactly("TextBlock", "ToolUseBlock", "TextBlock");
            verify(toolSession, times(1)).addTool(eq(bashTool), any(), eq("review-tool-1"), any());
        }

        @Test
        void backgroundResultsCompletedEarlyAreDeliveredOnceWithStatusAndFilePath() throws Exception {
            when(featureFlagService.isEnabled(anyString())).thenAnswer(inv -> "BACKGROUND_AGENT_WAIT".equals(inv.getArgument(0)));
            var output = backgroundOutputs.resolve("result.txt");
            java.nio.file.Files.writeString(output, "EVIDENCE_START" + "x".repeat(4100) + "TAIL_EVIDENCE");
            when(toolResultSummarizer.processToolResults(anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
            completedToolResult();
            script((call, callback) -> {
                if (call == 1) {
                    backgrounds.register("old-child", "test-session", "old-run", "old task", null);
                    backgrounds.register("early-child", "test-session", run.id(), "task", output.toString());
                    backgrounds.markCompleted("early-child", new com.aicodeassistant.tool.agent.SubAgentExecutor.AgentResult(
                            "timeout", "partial", "task", output.toString()));
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("I started the review"));
                } else if (call == 2) {
                    finish(callback, "tool_use", new LlmStreamEvent.ToolUseStart("review-tool-1", "Bash"),
                            new LlmStreamEvent.ToolInputDelta("review-tool-1", "{}"), new LlmStreamEvent.BlockStop(0));
                } else finish(callback, "end_turn", new LlmStreamEvent.TextDelta("Review incomplete: child timed out"));
            });
            var result = queryEngine.execute(buildConfig(), buildState("review"), handler);
            assertThat(result.isSuccess()).isTrue();
            assertThat(requests).hasSize(3);
            for (var request : requests.subList(1, 3)) {
                String body = request.toString();
                assertThat(body).contains("Status: timeout", output.toString(), "EVIDENCE_START", "[truncated]");
                assertThat(body).doesNotContain("old-child", "TAIL_EVIDENCE");
                assertThat(body.indexOf("### Agent: early-child")).isEqualTo(body.lastIndexOf("### Agent: early-child"));
            }
        }

        @Test
        void backgroundLateResultResumesMainModelBeforeRunCompletes() throws Exception {
            when(featureFlagService.isEnabled(anyString())).thenAnswer(inv -> "BACKGROUND_AGENT_WAIT".equals(inv.getArgument(0)));
            var waiting = new java.util.concurrent.CountDownLatch(1);
            var waitBudget = new java.util.concurrent.atomic.AtomicReference<java.time.Duration>();
            doAnswer(inv -> {
                waitBudget.set(inv.getArgument(2));
                waiting.countDown();
                return inv.callRealMethod();
            }).when(backgrounds).awaitRun(anyString(), anyString(), any(), any());
            var output = backgroundOutputs.resolve("late.txt");
            java.nio.file.Files.writeString(output, "late review evidence");
            script((call, callback) -> {
                if (call == 1) {
                    backgrounds.register("late", "test-session", run.id(), "review", output.toString());
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("started review"));
                } else finish(callback, "end_turn", new LlmStreamEvent.TextDelta("consolidated review"));
            });
            var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> queryEngine.execute(buildConfig(), buildState("review"), handler));
            try {
                assertThat(waiting.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(waitBudget.get()).isGreaterThan(java.time.Duration.ofSeconds(
                        backgroundTimeouts.getMaxSeconds() + backgroundTimeouts.getGracefulShutdownSeconds()));
                assertThat(future.isDone()).isFalse();
                backgrounds.markCompleted("late", new com.aicodeassistant.tool.agent.SubAgentExecutor.AgentResult("completed", "evidence", "review", output.toString()));
                var result = future.get(3, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(result.isSuccess()).isTrue();
                assertThat(requests).hasSize(2);
                assertThat(requests.get(1).toString()).contains("late review evidence");
            } finally { queryEngine.abort("test-session", AbortReason.USER_INTERRUPT); }
        }

        @Test
        void cancellationDuringBackgroundWaitStopsWithoutConsolidation() throws Exception {
            when(featureFlagService.isEnabled(anyString())).thenAnswer(inv -> "BACKGROUND_AGENT_WAIT".equals(inv.getArgument(0)));
            var waiting = new java.util.concurrent.CountDownLatch(1);
            doAnswer(inv -> { waiting.countDown(); return inv.callRealMethod(); })
                    .when(backgrounds).awaitRun(anyString(), anyString(), any(), any());
            script((call, callback) -> {
                backgrounds.register("pending", "test-session", run.id(), "review", null);
                finish(callback, "end_turn", new LlmStreamEvent.TextDelta("started review"));
            });
            var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> queryEngine.execute(buildConfig(), buildState("review"), handler));
            try {
                assertThat(waiting.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                queryEngine.abort("test-session", AbortReason.USER_INTERRUPT);
                var result = future.get(3, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(result.stopReason()).isEqualTo("cancelled");
                assertThat(result.isSuccess()).isFalse();
                assertThat(requests).hasSize(1);
                assertThat(result.totalUsage().outputTokens()).isEqualTo(5);
            } finally { queryEngine.abort("test-session", AbortReason.USER_INTERRUPT); }
        }

        @Test
        void backgroundWaitTimeoutReturnsErrorWithPartialTextAndUsage() {
            when(featureFlagService.isEnabled(anyString())).thenAnswer(inv -> "BACKGROUND_AGENT_WAIT".equals(inv.getArgument(0)));
            backgroundTimeouts.setMaxWaitMinutes(0);
            script((call, callback) -> {
                backgrounds.register("pending", "test-session", run.id(), "task", null);
                finish(callback, "end_turn", new LlmStreamEvent.TextDelta("progress before waiting"));
            });
            var result = queryEngine.execute(buildConfig(), buildState("review"), handler);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.stopReason()).isEqualTo("error");
            assertThat(result.error()).contains("BACKGROUND_AGENT_WAIT_TIMEOUT", "termination unconfirmed");
            assertThat(result.messages().toString()).contains("progress before waiting");
            assertThat(result.totalUsage().outputTokens()).isEqualTo(5);
            assertThat(backgrounds.getStatus("pending").status()).isEqualTo("running");
            assertThat(requests).hasSize(1);
        }

        @Test
        void backgroundResultsDoNotBypassTurnLimit() {
            when(featureFlagService.isEnabled(anyString())).thenAnswer(inv -> "BACKGROUND_AGENT_WAIT".equals(inv.getArgument(0)));
            script((call, callback) -> {
                backgrounds.register("pending", "test-session", run.id(), "task", null);
                finish(callback, "end_turn", new LlmStreamEvent.TextDelta("progress"));
            });
            var result = queryEngine.execute(withMaxTurns(1), buildState("review"), handler);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).contains("BACKGROUND_AGENT_RESULTS_UNDELIVERED_TURN_LIMIT");
            assertThat(requests).hasSize(1);
        }

        @Test
        void eachBackgroundWaitWaveReceivesFullIndependentBudget() throws Exception {
            when(featureFlagService.isEnabled(anyString())).thenAnswer(inv -> "BACKGROUND_AGENT_WAIT".equals(inv.getArgument(0)));
            var waitBudgets = java.util.Collections.synchronizedList(new java.util.ArrayList<java.time.Duration>());
            var firstWait = new java.util.concurrent.CountDownLatch(1);
            var secondWait = new java.util.concurrent.CountDownLatch(1);
            doAnswer(inv -> {
                java.time.Duration budget = inv.getArgument(2);
                waitBudgets.add(budget);
                if (waitBudgets.size() == 1) firstWait.countDown();
                if (waitBudgets.size() == 2) secondWait.countDown();
                return inv.callRealMethod();
            }).when(backgrounds).awaitRun(anyString(), anyString(), any(), any());
            var firstOutput = backgroundOutputs.resolve("wave1.txt");
            java.nio.file.Files.writeString(firstOutput, "wave one evidence");
            var secondOutput = backgroundOutputs.resolve("wave2.txt");
            java.nio.file.Files.writeString(secondOutput, "wave two evidence");
            script((call, callback) -> {
                if (call == 1) {
                    backgrounds.register("wave-1", "test-session", run.id(), "wave one", firstOutput.toString());
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("wave one launched"));
                } else if (call == 2) {
                    backgrounds.register("wave-2", "test-session", run.id(), "wave two", secondOutput.toString());
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("wave two launched"));
                } else {
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("consolidated final answer"));
                }
            });
            var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> queryEngine.execute(buildConfig(), buildState("review"), handler));
            try {
                assertThat(firstWait.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                backgrounds.markCompleted("wave-1", new com.aicodeassistant.tool.agent.SubAgentExecutor.AgentResult(
                        "completed", "wave one evidence", "wave one", firstOutput.toString()));
                assertThat(secondWait.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                backgrounds.markCompleted("wave-2", new com.aicodeassistant.tool.agent.SubAgentExecutor.AgentResult(
                        "completed", "wave two evidence", "wave two", secondOutput.toString()));
                var result = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(result.isSuccess()).isTrue();
                // 关键回归断言：第二波等待必须获得与第一波完全相同的完整预算。
                // 旧实现共享运行级截止时间，第二波只能拿到剩余预算（生产事故中为 0，立即超时）。
                assertThat(waitBudgets).hasSize(2);
                assertThat(waitBudgets.get(0)).isEqualTo(java.time.Duration.ofMinutes(backgroundTimeouts.getMaxWaitMinutes()));
                assertThat(waitBudgets.get(1)).isEqualTo(waitBudgets.get(0));
                assertThat(requests).hasSize(3);
                assertThat(requests.get(1).toString()).contains("wave one evidence");
                assertThat(requests.get(2).toString()).contains("wave two evidence");
            } finally { queryEngine.abort("test-session", AbortReason.USER_INTERRUPT); }
        }

        @Test
        void disabledBackgroundWaitKeepsExistingImmediateReturn() {
            script((call, callback) -> {
                backgrounds.register("pending", "test-session", run.id(), "task", null);
                finish(callback, "end_turn", new LlmStreamEvent.TextDelta("launched"));
            });
            assertThat(queryEngine.execute(buildConfig(), buildState("review"), handler).isSuccess()).isTrue();
            assertThat(requests).hasSize(1);
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
                    List.of(bashTool), List.of(), 8192, 200000,
                    new ThinkingConfig.Disabled(), maxTurns, "test");
        }

        private QueryConfig buildConfig() {
            return QueryConfig.withDefaults(
                    "mock-model", "You are a helpful assistant.",
                    List.of(bashTool), List.of(), 8192, 200000,
                    new ThinkingConfig.Disabled(), 10, "test");
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

    // ═══════════════ 已观测用量收敛（R-03） ═══════════════

    @Nested
    @DisplayName("已观测用量与快照（R-03）")
    class ObservedUsageTests {
        private RunExecutionRegistry executions;
        private RunEnvelope run;
        private AtomicReference<RunEnvelope> authority;
        private StreamingToolExecutor.ExecutionSession toolSession;
        private Tool bashTool;

        @BeforeEach
        void wireEngineWithRunAuthority() {
            executions = new RunExecutionRegistry();
            run = RunEnvelope.start("test-session", null, "query", "mock-model");
            when(runTracker.startRun("test-session", null, "query", "mock-model")).thenReturn(run);
            authority = new AtomicReference<>(run);
            lenient().when(runTracker.getRun(run.id())).thenAnswer(inv -> Optional.of(authority.get()));
            lenient().doAnswer(inv -> { authority.set(terminalSnapshot(run, RunEnvelope.RunStatus.COMPLETED,
                    RunEnvelope.RunExitReason.MODEL_FINISHED, null)); return null; })
                    .when(runTracker).completeRun(eq(run.id()), anyInt(), anyDouble(), anyInt(), anyInt());
            lenient().doAnswer(inv -> { authority.set(terminalSnapshot(run, RunEnvelope.RunStatus.FAILED,
                    RunEnvelope.RunExitReason.INTERNAL_ERROR, inv.getArgument(1))); return null; })
                    .when(runTracker).failRun(eq(run.id()), anyString());
            lenient().doAnswer(inv -> { authority.set(terminalSnapshot(run, RunEnvelope.RunStatus.FAILED,
                    inv.getArgument(1), inv.getArgument(2))); return null; })
                    .when(runTracker).failRun(eq(run.id()), any(RunEnvelope.RunExitReason.class), anyString());
            lenient().doAnswer(inv -> { boolean timeout = inv.getArgument(1) == AbortReason.TIMEOUT;
                authority.set(terminalSnapshot(run, timeout ? RunEnvelope.RunStatus.FAILED : RunEnvelope.RunStatus.CANCELLED,
                    timeout ? RunEnvelope.RunExitReason.DEADLINE_EXCEEDED : RunEnvelope.RunExitReason.USER_CANCELLED,
                    inv.getArgument(2))); return null; })
                    .when(runTracker).abortRun(eq(run.id()), any(), anyString());
            queryEngine = new QueryEngine(
                    providerRegistry, compactService, apiRetryService, tokenCounter,
                    objectMapper, streamingToolExecutor, new MessageNormalizer(), hookService,
                    snipService, microCompactService, modelRegistry,
                    thinkingBudgetCalculator, modelTierService, fileHistoryService,
                    toolResultSummarizer, contextCascade, compactMetrics,
                    null, null, null, featureFlagService,
                    new DefaultTerminationStrategy(), new ToolPriorityScheduler(),
                    null, new AgentTimeoutConfig(), tokenBudgetGuard, imageRefInjector,
                    runTracker, executions, userImageTranscoder);
            toolSession = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession(any())).thenReturn(toolSession);
            bashTool = mock(Tool.class);
            lenient().when(bashTool.getName()).thenReturn("Bash");
            lenient().when(bashTool.getAliases()).thenReturn(List.of());
            lenient().when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any()))
                    .thenAnswer(inv -> inv.getArgument(0, Supplier.class).get());
            lenient().when(hookService.executeStopHooks(anyList(), anyString()))
                    .thenReturn(HookRegistry.StopHookResult.ok());
        }

        private void script(BiConsumer<Integer, StreamChatCallback> response) {
            LlmProvider provider = mock(LlmProvider.class);
            when(providerRegistry.getProvider(anyString())).thenReturn(provider);
            AtomicInteger calls = new AtomicInteger();
            doAnswer(inv -> {
                response.accept(calls.incrementAndGet(), inv.getArgument(7));
                return null;
            }).when(provider).streamChat(anyString(), anyList(), anyString(), anyList(),
                    anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));
        }

        private void finish(StreamChatCallback callback, String stopReason, LlmStreamEvent... events) {
            for (LlmStreamEvent event : events) callback.onEvent(event);
            callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(10, 5, 0, 0), stopReason));
            callback.onComplete();
        }

        private QueryConfig buildConfig() {
            return QueryConfig.withDefaults("mock-model", "You are a helpful assistant.",
                    List.of(bashTool), List.of(), 8192, 200000,
                    new ThinkingConfig.Disabled(), 10, "test");
        }

        @Test
        @DisplayName("多轮累计：两轮报告相加（15 + 10 = 25），且快照随轮次单调推进")
        void accumulatesReportedUsageAcrossTurnsAndPushesSnapshots() {
            when(toolSession.isAllCompleted()).thenReturn(true);
            StreamingToolExecutor.TrackedTool completed = mock(StreamingToolExecutor.TrackedTool.class);
            when(completed.getToolUseId()).thenReturn("tool-1");
            when(completed.getResult()).thenReturn(ToolResult.success("tool result"));
            when(toolSession.yieldCompleted()).thenReturn(List.of(completed), List.of());
            when(toolResultSummarizer.processToolResults(anyList(), anyInt()))
                    .thenAnswer(inv -> inv.getArgument(0));
            script((call, callback) -> {
                if (call == 1) {
                    callback.onEvent(new LlmStreamEvent.ToolUseStart("tool-1", "Bash"));
                    callback.onEvent(new LlmStreamEvent.ToolInputDelta("tool-1", "{}"));
                    callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(10, 5, 0, 0), "tool_use"));
                    callback.onComplete();
                } else {
                    callback.onEvent(new LlmStreamEvent.TextDelta("done"));
                    callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(4, 6, 0, 0), "end_turn"));
                    callback.onComplete();
                }
            });

            QueryLoopState state = buildState("question");
            QueryEngine.QueryResult result = queryEngine.execute(buildConfig(), state, handler);

            assertThat(result.isSuccess()).isTrue();
            // 第一轮 15（10+5），第二轮再报告 10（4+6），累计 25。
            assertThat(result.totalUsage().totalTokens()).isEqualTo(25);
            assertThat(state.getObservedUsage().totalTokens()).isEqualTo(25);
            assertThat(handler.usageEvents).extracting(Usage::totalTokens).containsExactly(15, 10);
            assertThat(state.observationStatus()).isEqualTo(QueryLoopState.ObservationStatus.KNOWN);

            ArgumentCaptor<Long> seqs = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Integer> tokens = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<RunEnvelope.UsageStatus> statuses =
                    ArgumentCaptor.forClass(RunEnvelope.UsageStatus.class);
            verify(runTracker, atLeastOnce()).recordUsageSnapshotBestEffort(
                    eq(run.id()), seqs.capture(), tokens.capture(), anyInt(), statuses.capture());
            assertThat(tokens.getAllValues().getLast()).isEqualTo(25);
            // 序号从 1 开始（0 保留为"尚无快照"，否则与迁移默认值相撞会被误判为冲突丢弃）。
            assertThat(seqs.getAllValues().getFirst()).isEqualTo(1L);
            assertThat(seqs.getAllValues()).isSorted();
            assertThat(statuses.getAllValues()).containsOnly(RunEnvelope.UsageStatus.KNOWN);
        }

        @Test
        @DisplayName("显式零值算一次报告：状态 KNOWN，总量为 0 也不估算")
        void explicitZeroUsageCountsAsReported() {
            script((call, callback) -> {
                callback.onEvent(new LlmStreamEvent.TextDelta("answer"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(0, 0, 0, 0), "end_turn"));
                callback.onComplete();
            });

            QueryLoopState state = buildState("question");
            QueryEngine.QueryResult result = queryEngine.execute(buildConfig(), state, handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(state.getObservedUsage().totalTokens()).isZero();
            assertThat(state.observationStatus()).isEqualTo(QueryLoopState.ObservationStatus.KNOWN);
            assertThat(handler.usageEvents).hasSize(1);
        }

        @Test
        @DisplayName("缺 usage 不估算：状态 UNKNOWN，不回填任何 token")
        void missingUsageIsMarkedUnknownWithoutEstimation() {
            script((call, callback) -> {
                callback.onEvent(new LlmStreamEvent.TextDelta("answer without usage metadata"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(null, "end_turn"));
                callback.onComplete();
            });

            QueryLoopState state = buildState("question");
            QueryEngine.QueryResult result = queryEngine.execute(buildConfig(), state, handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(state.getObservedUsage().totalTokens()).isZero();
            assertThat(state.observationStatus()).isEqualTo(QueryLoopState.ObservationStatus.UNKNOWN);
            assertThat(handler.usageEvents).isEmpty();
            ArgumentCaptor<RunEnvelope.UsageStatus> statuses =
                    ArgumentCaptor.forClass(RunEnvelope.UsageStatus.class);
            verify(runTracker, atLeastOnce()).recordUsageSnapshotBestEffort(
                    eq(run.id()), anyLong(), anyInt(), anyInt(), statuses.capture());
            assertThat(statuses.getAllValues()).containsOnly(RunEnvelope.UsageStatus.UNKNOWN);
        }

        @Test
        @DisplayName("同一响应的两个回调只收敛一次：10+5 记 15 而不是 30")
        void singleResponseIsConvergedExactlyOnceAcrossCallbacks() {
            script((call, callback) -> finish(callback, "end_turn",
                    new LlmStreamEvent.TextDelta("answer")));

            QueryLoopState state = buildState("question");
            QueryEngine.QueryResult result = queryEngine.execute(buildConfig(), state, handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(state.getObservedUsage().totalTokens()).isEqualTo(15);
            assertThat(state.getObservedUsage()).isEqualTo(new Usage(10, 5, 0, 0));
            assertThat(result.totalUsage().totalTokens()).isEqualTo(15);
            assertThat(handler.assistantMessages).hasSize(1);
            assertThat(handler.usageEvents).hasSize(1)
                    .allSatisfy(usage -> assertThat(usage.totalTokens()).isEqualTo(15));
        }

        @Test
        @DisplayName("partial 响应只加一次：中断前完整报告的 usage 只收敛一次")
        void partialResponseUsageIsRetainedExactlyOnce() {
            script((call, callback) -> {
                finish(callback, "end_turn", new LlmStreamEvent.TextDelta("partial result"));
                queryEngine.abort("test-session", AbortReason.TIMEOUT);
            });

            QueryLoopState state = buildState("question");
            QueryEngine.QueryResult result = queryEngine.execute(buildConfig(), state, handler);

            assertThat(state.getObservedUsage().totalTokens()).isEqualTo(15);
            assertThat(result.totalUsage().totalTokens()).isEqualTo(15);
            assertThat(state.observationStatus()).isEqualTo(QueryLoopState.ObservationStatus.KNOWN);
            assertThat(handler.usageEvents).hasSize(1)
                    .allSatisfy(usage -> assertThat(usage.totalTokens()).isEqualTo(15));

            // 取消/超时路径的最终观测值必须在终止前落库；首个快照序号为 1（seq=0 会与默认值冲突被丢弃）。
            ArgumentCaptor<Long> snapshotSeqs = ArgumentCaptor.forClass(Long.class);
            verify(runTracker, atLeastOnce()).recordUsageSnapshotBestEffort(
                    eq(run.id()), snapshotSeqs.capture(), eq(15), anyInt(),
                    eq(RunEnvelope.UsageStatus.KNOWN));
            assertThat(snapshotSeqs.getAllValues().getFirst()).isEqualTo(1L);
        }

        @Test
        void responseKeepsLatestCumulativeUsageAcrossNullAndRepeatedDeltas() {
            script((call, callback) -> {
                callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(10, 2, 0, 0), null));
                callback.onEvent(new LlmStreamEvent.TextDelta("answer"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(10, 5, 0, 0), null));
                callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(10, 5, 0, 0), null));
                callback.onEvent(new LlmStreamEvent.MessageDelta(null, "end_turn"));
                callback.onComplete();
            });
            QueryLoopState state = buildState("question");
            var result = queryEngine.execute(buildConfig(), state, handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.totalUsage().totalTokens()).isEqualTo(15);
            assertThat(state.getReportedUsageObservations()).isEqualTo(1);
            assertThat(state.observationStatus()).isEqualTo(QueryLoopState.ObservationStatus.KNOWN);
            assertThat(handler.usageEvents).containsExactly(new Usage(10, 5, 0, 0));
        }

        @Test
        void compatibleProviderMissingUsageRemainsUnknownThroughEngine() throws Exception {
            var provider = new com.aicodeassistant.llm.impl.OpenAiCompatibleProvider(
                    "test", objectMapper, new LlmHttpProperties(
                            new LlmHttpProperties.PoolProperties(2, 30), 10, 10, false),
                    new ApiKeyRotationManager("fixture"), "fixture", "http://127.0.0.1:1/v1",
                    "mock-model", List.of("mock-model"));
            var parse = provider.getClass().getDeclaredMethod("processChunk",
                    String.class, Map.class, StreamChatCallback.class, AtomicBoolean.class);
            parse.setAccessible(true);
            script((call, callback) -> {
                try {
                    parse.invoke(provider,
                            "{\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}",
                            new LinkedHashMap<>(), callback, new AtomicBoolean());
                    callback.onComplete();
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
            });
            QueryLoopState state = buildState("question");
            var result = queryEngine.execute(buildConfig(), state, handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(state.getReportedUsageObservations()).isZero();
            assertThat(state.observationStatus()).isEqualTo(QueryLoopState.ObservationStatus.UNKNOWN);
            assertThat(handler.usageEvents).isEmpty();
        }

        @Test
        void reportedUsageBeforeProviderErrorIsRetained() {
            script((call, callback) -> {
                callback.onEvent(new LlmStreamEvent.TextDelta("partial"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(10, 5, 0, 0), null));
                callback.onError(new LlmApiException("provider failure after usage", false));
            });
            QueryLoopState state = buildState("question");
            var result = queryEngine.execute(buildConfig(), state, handler);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.totalUsage().totalTokens()).isEqualTo(15);
            assertThat(state.getReportedUsageObservations()).isEqualTo(1);
            verify(runTracker).recordUsageSnapshotBestEffort(eq(run.id()), anyLong(), eq(15), anyInt(),
                    eq(RunEnvelope.UsageStatus.KNOWN));
        }

        @Test
        void reportedUsageBeforeRuntimeFailureIsRetained() {
            script((call, callback) -> {
                callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(10, 5, 0, 0), null));
                throw new IllegalStateException("failure after usage");
            });
            QueryLoopState state = buildState("question");
            var result = queryEngine.execute(buildConfig(), state, handler);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.totalUsage().totalTokens()).isEqualTo(15);
            assertThat(state.getReportedUsageObservations()).isEqualTo(1);
        }

        @Test
        void invalidToolJsonDoesNotDiscardAlreadyReportedUsage() {
            script((call, callback) -> {
                callback.onEvent(new LlmStreamEvent.ToolUseStart("invalid-tool", "Bash"));
                callback.onEvent(new LlmStreamEvent.ToolInputDelta("invalid-tool", "{"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(10, 5, 0, 0), "tool_use"));
                callback.onComplete();
            });
            QueryLoopState state = buildState("question");
            var result = queryEngine.execute(buildConfig(), state, handler);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.totalUsage().totalTokens()).isEqualTo(15);
            assertThat(state.getReportedUsageObservations()).isEqualTo(1);
            verify(toolSession, never()).addTool(any(), any(), anyString(), any());
        }

        @Test
        void unreportedFailureAfterKnownTurnMarksPartial() {
            completedToolTurn();
            script((call, callback) -> {
                if (call == 1) finish(callback, "tool_use",
                        new LlmStreamEvent.ToolUseStart("tool-1", "Bash"),
                        new LlmStreamEvent.ToolInputDelta("tool-1", "{}"));
                else {
                    callback.onEvent(new LlmStreamEvent.TextDelta("partial response without usage"));
                    callback.onError(new LlmApiException("failure without usage", false));
                }
            });
            QueryLoopState state = buildState("question");
            var result = queryEngine.execute(buildConfig(), state, handler);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.totalUsage().totalTokens()).isEqualTo(15);
            assertThat(state.observationStatus()).isEqualTo(QueryLoopState.ObservationStatus.PARTIAL);
        }

        @Test
        void localPayloadRejectionDoesNotInventAnUnreportedResponse() {
            completedToolTurn();
            script((call, callback) -> {
                if (call == 1) finish(callback, "tool_use",
                        new LlmStreamEvent.ToolUseStart("tool-1", "Bash"),
                        new LlmStreamEvent.ToolInputDelta("tool-1", "{}"));
                else throw new LlmApiException("local payload too large", false, 413,
                        "CONTEXT_BUDGET_EXCEEDED", 0);
            });
            QueryLoopState state = buildState("question");
            var result = queryEngine.execute(buildConfig(), state, handler);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.totalUsage().totalTokens()).isEqualTo(15);
            assertThat(state.observationStatus()).isEqualTo(QueryLoopState.ObservationStatus.KNOWN);
            assertThat(state.hasMissingUsageObservation()).isFalse();
        }

        @Test
        void cancellationBeforeStreamChatDoesNotInventAnUnreportedResponse() {
            completedToolTurn();
            AtomicInteger providerCalls = new AtomicInteger();
            script((call, callback) -> {
                providerCalls.incrementAndGet();
                finish(callback, "tool_use", new LlmStreamEvent.ToolUseStart("tool-1", "Bash"),
                        new LlmStreamEvent.ToolInputDelta("tool-1", "{}"));
            });
            AtomicInteger retryCalls = new AtomicInteger();
            doAnswer(inv -> {
                if (retryCalls.incrementAndGet() == 2) {
                    queryEngine.abort("test-session", AbortReason.USER_INTERRUPT);
                }
                return inv.getArgument(0, Supplier.class).get();
            }).when(apiRetryService).executeWithRetry(any(), anyString(), anyString(), any());
            QueryLoopState state = buildState("question");
            var result = queryEngine.execute(buildConfig(), state, handler);

            assertThat(result.stopReason()).isEqualTo("cancelled");
            assertThat(providerCalls.get()).isEqualTo(1);
            assertThat(result.totalUsage().totalTokens()).isEqualTo(15);
            assertThat(state.observationStatus()).isEqualTo(QueryLoopState.ObservationStatus.KNOWN);
            assertThat(state.hasMissingUsageObservation()).isFalse();
            assertThat(handler.usageEvents).hasSize(1);
        }

        @Test
        void cancellationExceptionAfterUsageDoesNotDoubleCount() {
            script((call, callback) -> {
                callback.onEvent(new LlmStreamEvent.TextDelta("partial"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(10, 5, 0, 0), null));
                throw new java.util.concurrent.CancellationException("cancelled stream");
            });
            QueryLoopState state = buildState("question");
            var result = queryEngine.execute(buildConfig(), state, handler);

            assertThat(result.stopReason()).isEqualTo("cancelled");
            assertThat(result.totalUsage().totalTokens()).isEqualTo(15);
            assertThat(state.getReportedUsageObservations()).isEqualTo(1);
            assertThat(handler.usageEvents).containsExactly(new Usage(10, 5, 0, 0));
        }

        private void completedToolTurn() {
            when(toolSession.isAllCompleted()).thenReturn(true);
            StreamingToolExecutor.TrackedTool completed = mock(StreamingToolExecutor.TrackedTool.class);
            when(completed.getToolUseId()).thenReturn("tool-1");
            when(completed.getResult()).thenReturn(ToolResult.success("tool result"));
            when(toolSession.yieldCompleted()).thenReturn(List.of(completed), List.of());
            when(toolResultSummarizer.processToolResults(anyList(), anyInt()))
                    .thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("消息持久化失败：usage 在持久化前已收敛且快照已推进，失败路径不重复累计、不判成功")
        void usageIsConvergedBeforeMessagePersistenceFailure() {
            script((call, callback) -> finish(callback, "end_turn", new LlmStreamEvent.TextDelta("answer")));

            QueryLoopState state = buildState("question");
            AtomicInteger assistantPersistAttempts = new AtomicInteger();
            state.setPersistenceSink(message -> {
                if (message instanceof Message.AssistantMessage) {
                    assistantPersistAttempts.incrementAndGet();
                    throw new com.aicodeassistant.session.MessagePersistenceException(
                            "TEST_WRITE_FAILED", "injected assistant write failure");
                }
            });

            QueryEngine.QueryResult result = queryEngine.execute(buildConfig(), state, handler);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).contains("PERSISTENCE_FAILED", "TEST_WRITE_FAILED");
            assertThat(assistantPersistAttempts.get()).isEqualTo(1);
            // 原始 usage（10+5=15）在持久化之前收敛并已推送快照；失败不清零、不重复。
            assertThat(state.getObservedUsage().totalTokens()).isEqualTo(15);
            assertThat(state.observationStatus()).isEqualTo(QueryLoopState.ObservationStatus.KNOWN);
            verify(runTracker, atLeastOnce()).recordUsageSnapshotBestEffort(
                    eq(run.id()), anyLong(), eq(15), anyInt(), eq(RunEnvelope.UsageStatus.KNOWN));
            verify(runTracker).failRun(eq(run.id()), eq(RunEnvelope.RunExitReason.INCOMPLETE),
                    startsWith("PERSISTENCE_FAILED:"));
            verify(runTracker, never()).completeRun(anyString(), anyInt(), anyDouble(), anyInt(), anyInt());
        }
    }

    // ═══════════════ Inline skill 正文投递（请求级） ═══════════════

    @Nested
    @DisplayName("Inline skill 正文投递（请求级回归）")
    class InlineSkillDeliveryTests {

        private static final String RENDERED_BODY = "BODY_SENTINEL Hello World!";
        private static final String CONTROL_MARKER = "CONTROL_METADATA_ONLY_SENTINEL";
        private static final String CONTROL_CONTENT = "control-tool-visible-content";
        private static final String DISABLED_NAME = "isolated-disabled-skill-sentinel";
        private static final String DISABLED_DESCRIPTION = "DISABLED_SKILL_DESCRIPTION_SENTINEL";
        private static final String DISABLED_BODY = "DISABLED_SKILL_BODY_SENTINEL {{name}}";

        private RunExecutionRegistry executions;
        private RunEnvelope run;
        private AtomicReference<RunEnvelope> authority;
        private final List<List<Map<String, Object>>> requests = new ArrayList<>();
        private final List<Map<String, Object>> completeRequests = new ArrayList<>();
        private SkillRegistry registry;
        private Tool skillTool;
        private Tool controlTool;
        @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace;

        @BeforeEach
        void wireRealSkillToolExecution() {
            executions = new RunExecutionRegistry();
            run = RunEnvelope.start("test-session", null, "query", "mock-model");
            lenient().when(runTracker.startRun("test-session", null, "query", "mock-model"))
                    .thenReturn(run);
            authority = new AtomicReference<>(run);
            lenient().when(runTracker.getRun(run.id())).thenAnswer(inv -> Optional.of(authority.get()));
            lenient().doAnswer(inv -> { authority.set(terminalSnapshot(run, RunEnvelope.RunStatus.COMPLETED,
                    RunEnvelope.RunExitReason.MODEL_FINISHED, null)); return null; })
                    .when(runTracker).completeRun(eq(run.id()), anyInt(), anyDouble(), anyInt(), anyInt());
            lenient().doAnswer(inv -> { authority.set(terminalSnapshot(run, RunEnvelope.RunStatus.FAILED,
                    RunEnvelope.RunExitReason.INTERNAL_ERROR, inv.getArgument(1))); return null; })
                    .when(runTracker).failRun(eq(run.id()), anyString());
            lenient().doAnswer(inv -> { authority.set(terminalSnapshot(run, RunEnvelope.RunStatus.FAILED,
                    inv.getArgument(1), inv.getArgument(2))); return null; })
                    .when(runTracker).failRun(eq(run.id()), any(RunEnvelope.RunExitReason.class), anyString());
            lenient().doAnswer(inv -> { boolean timeout = inv.getArgument(1) == AbortReason.TIMEOUT;
                authority.set(terminalSnapshot(run, timeout ? RunEnvelope.RunStatus.FAILED : RunEnvelope.RunStatus.CANCELLED,
                    timeout ? RunEnvelope.RunExitReason.DEADLINE_EXCEEDED : RunEnvelope.RunExitReason.USER_CANCELLED,
                    inv.getArgument(2))); return null; })
                    .when(runTracker).abortRun(eq(run.id()), any(), anyString());

            // 真实 skill 栈 — 合成 inline 技能
            registry = new SkillRegistry(new SkillStateService(
                    objectMapper, workspace.resolve("skill-states.json").toString()));
            registry.register(SkillDefinition.fromMarkdown(
                    "greet.md",
                    "---\ncontext: inline\n---\nBODY_SENTINEL Hello {{name}}!",
                    SkillDefinition.SkillSource.PROJECT, null));
            SkillExecutor skillExecutor = new SkillExecutor(registry, new SkillToolValidator());
            skillTool = new SkillTool(skillExecutor, registry);

            // 对照工具：标记只存在于 metadata.injectedPrompt，正文中绝不出现
            controlTool = new Tool() {
                @Override public String getName() { return "ControlTool"; }
                @Override public String getDescription() { return "metadata-only marker control"; }
                @Override public Map<String, Object> getInputSchema() { return Map.of(); }
                @Override public ToolResult call(ToolInput input, ToolUseContext context) {
                    return ToolResult.success(CONTROL_CONTENT, Map.of("injectedPrompt", CONTROL_MARKER));
                }
            };

            // 真实执行链 — AutoApproveTest 风格 stub 授权/网关；网关真实调用工具本身
            ObjectMapper json = new ObjectMapper();
            AuthorizationService authorization = mock(AuthorizationService.class);
            ToolExecutionGateway gateway = mock(ToolExecutionGateway.class);
            AuthorizationSubject subject = new AuthorizationSubject(
                    "session", "run", "run", "workspace", workspace);
            OperationDescriptor descriptor = new OperationDescriptor(
                    1, "Skill", "invoke", "input-hash", "generic-v1",
                    List.of(EffectClass.PROCESS), List.of(), List.of(), List.of(),
                    RiskClass.HIGH, "operation-hash", "test operation");
            PreparedOperation prepared = new PreparedOperation(subject, descriptor, "attempt");
            lenient().when(authorization.prepare(any(), any(), any(), any())).thenReturn(prepared);
            lenient().when(authorization.authorizePrepared(any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> new AuthorizedOperation(
                            prepared.subject(), prepared.descriptor(), invocation.getArgument(2),
                            AuthorizationDiagnostic.Source.MODE, "AUTO_APPROVE",
                            null, null, null, "attempt"));
            lenient().when(gateway.execute(any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> {
                        Runnable admission = invocation.getArgument(3);
                        Runnable started = invocation.getArgument(4);
                        admission.run();
                        started.run();
                        Tool executingTool = invocation.getArgument(0);
                        AuthorizedOperation allowed = invocation.getArgument(1);
                        ToolUseContext executingContext = invocation.getArgument(2);
                        return executingTool.call(allowed.executionInput(), executingContext);
                    });
            ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                    new HookService(new HookRegistry(), null), json, new SensitiveDataFilter(),
                    new FrozenToolInputFactory(json, 1024, 4096),
                    authorization, gateway, new ToolRecoveryFramework(List.of()),
                    null, null);
            StreamingToolExecutor realToolExecutor =
                    new StreamingToolExecutor(pipeline, new SimpleMeterRegistry());

            lenient().when(toolResultSummarizer.processToolResults(anyList(), anyInt()))
                    .thenAnswer(inv -> inv.getArgument(0));
            lenient().when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any()))
                    .thenAnswer(inv -> inv.getArgument(0, Supplier.class).get());
            lenient().when(hookService.executeStopHooks(anyList(), anyString()))
                    .thenReturn(HookRegistry.StopHookResult.ok());

            queryEngine = new QueryEngine(
                    providerRegistry, compactService, apiRetryService, tokenCounter,
                    objectMapper, realToolExecutor, new MessageNormalizer(), hookService,
                    snipService, microCompactService, modelRegistry,
                    thinkingBudgetCalculator, modelTierService, fileHistoryService,
                    toolResultSummarizer, contextCascade, compactMetrics,
                    null, null, null, featureFlagService,
                    new DefaultTerminationStrategy(), new ToolPriorityScheduler(),
                    null, new AgentTimeoutConfig(), tokenBudgetGuard, imageRefInjector,
                    runTracker, executions, userImageTranscoder);
        }

        @Test
        @DisplayName("inline skill 渲染正文作为 tool_result 正文送达下一轮模型请求，metadata 不外泄")
        void inlineSkillBodyDeliveredAsToolResultContent() throws Exception {
            script((call, callback) -> {
                if (call == 1) {
                    finish(callback, "tool_use",
                            new LlmStreamEvent.ToolUseStart("skill-call-1", "Skill"),
                            new LlmStreamEvent.ToolInputDelta("skill-call-1",
                                    "{\"skill\":\"greet\",\"args\":\"name=World\"}"),
                            new LlmStreamEvent.ToolUseStart("control-call-1", "ControlTool"),
                            new LlmStreamEvent.ToolInputDelta("control-call-1", "{}"));
                } else {
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("done"));
                }
            });

            QueryEngine.QueryResult result = queryEngine.execute(
                    twoRoundConfig(), buildState("run the greet skill"), handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(handler.errors).isEmpty();
            assertThat(requests).hasSize(2);

            // 第 1 轮出站请求不得包含技能正文
            String round1Json = objectMapper.writeValueAsString(requests.get(0));
            assertThat(round1Json).doesNotContain("BODY_SENTINEL");

            String round2Json = objectMapper.writeValueAsString(requests.get(1));
            // 完整渲染正文恰好出现一次，且包含替换后的参数
            assertThat(countOccurrences(round2Json, "BODY_SENTINEL")).isEqualTo(1);
            assertThat(round2Json).contains(RENDERED_BODY);
            // tool_result 与 tool_use 按 id 正确配对
            List<Map<String, Object>> toolResults = toolResultBlocks(requests.get(1));
            assertThat(toolResults).anySatisfy(block -> {
                assertThat(block.get("tool_use_id")).isEqualTo("skill-call-1");
                assertThat(block.get("content")).isEqualTo(RENDERED_BODY);
                assertThat(block.get("is_error")).isNull();
            });
            // 对照：只在 metadata 中的标记不得进入请求；内部 metadata 键不外泄
            assertThat(toolResults).anySatisfy(block -> {
                assertThat(block.get("tool_use_id")).isEqualTo("control-call-1");
                assertThat(block.get("content")).isEqualTo(CONTROL_CONTENT);
            });
            assertThat(round2Json).doesNotContain(CONTROL_MARKER);
            assertThat(round2Json).doesNotContain("injectedPrompt");
            verify(runTracker).completeRun(eq(run.id()), anyInt(), anyDouble(), anyInt(), eq(2));
        }

        @Test
        @DisplayName("内置 review 的完整范围经过真实 Skill 链送达下一轮请求")
        void bundledReviewScopeDeliveredInNextModelRequest() throws Exception {
            String scope = "commit=abc123  排除 docs/**\n只查 \"src/a b.java\" {{args}} review_scope=literal";
            SkillDefinition review;
            try (var resource = java.util.Objects.requireNonNull(
                    getClass().getResourceAsStream("/skills/bundled/review.md"))) {
                review = SkillDefinition.fromMarkdown("review.md",
                        new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
                        SkillDefinition.SkillSource.BUNDLED, null);
            }
            registry.registerBuiltin(review);
            String toolInput = objectMapper.writeValueAsString(Map.of("skill", "review", "args", scope));
            script((call, callback) -> {
                if (call == 1) {
                    finish(callback, "tool_use",
                            new LlmStreamEvent.ToolUseStart("review-scope-call", "Skill"),
                            new LlmStreamEvent.ToolInputDelta("review-scope-call", toolInput));
                } else {
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("done"));
                }
            });

            QueryEngine.QueryResult result = queryEngine.execute(
                    twoRoundConfig(), buildState("run the review skill"), handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(handler.errors).isEmpty();
            assertThat(requests).hasSize(2);
            assertThat(objectMapper.writeValueAsString(requests.getFirst()))
                    .doesNotContain("commit=abc123", "review_scope=literal");
            String expected = review.content().replace("{{review_scope}}", scope);
            List<Map<String, Object>> blocks = toolResultBlocks(requests.get(1));
            assertThat(blocks).filteredOn(block -> "review-scope-call".equals(block.get("tool_use_id")))
                    .singleElement().satisfies(block -> {
                        assertThat(block.get("is_error")).isNull();
                        assertThat((String) block.get("content")).isEqualTo(expected).containsOnlyOnce(scope);
                    });
        }

        @Test
        @DisplayName("未知技能返回错误结果而非成功正文")
        void unknownSkillYieldsErrorNotSuccessBody() throws Exception {
            script((call, callback) -> {
                if (call == 1) {
                    finish(callback, "tool_use",
                            new LlmStreamEvent.ToolUseStart("skill-call-1", "Skill"),
                            new LlmStreamEvent.ToolInputDelta("skill-call-1",
                                    "{\"skill\":\"missing-skill\"}"));
                } else {
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("done"));
                }
            });

            QueryEngine.QueryResult result = queryEngine.execute(
                    twoRoundConfig(), buildState("run an unknown skill"), handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(requests).hasSize(2);
            String round2Json = objectMapper.writeValueAsString(requests.get(1));
            assertThat(round2Json).doesNotContain("BODY_SENTINEL");
            assertThat(round2Json).doesNotContain("loaded. Prompt injected");
            Map<String, Object> block = toolResultBlocks(requests.get(1)).stream()
                    .filter(b -> "skill-call-1".equals(b.get("tool_use_id")))
                    .findFirst().orElseThrow();
            assertThat(block.get("is_error")).isEqualTo(true);
            assertThat((String) block.get("content")).contains("Skill not found");
        }

        @Test
        @DisplayName("同一会话技能累计超过旧固定阈值后正文仍送达下一轮请求")
        void cumulativeSkillContentBeyondFormerLimitsReachesNextModelRequests() throws Exception {
            List<String> skillNames = new ArrayList<>();
            List<String> renderedBodies = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String name = "cumulative-skill-" + i;
                SkillDefinition skill = SkillDefinition.fromMarkdown(name + ".md",
                        "---\ncontext: inline\n---\nCUMULATIVE_BODY_" + i + "\n" + "验".repeat(8000),
                        SkillDefinition.SkillSource.PROJECT, null);
                registry.register(skill);
                skillNames.add(name);
                renderedBodies.add(skill.content());
            }
            List<Integer> calls = List.of(0, 0, 1, 2, 3, 4, 5);
            // 仅测量夹具的旧估算规模，不向 SkillExecutor 或主引擎注入计数器。
            TokenCounter fixtureCounter = new TokenCounter(null, null, null);
            assertThat(renderedBodies).allSatisfy(body ->
                    assertThat(fixtureCounter.estimateTokens(body)).isBetween(2501, 5000));
            assertThat(fixtureCounter.estimateTokens(renderedBodies.getFirst()) * 2).isGreaterThan(5000);
            assertThat(calls.stream().mapToInt(i -> fixtureCounter.estimateTokens(renderedBodies.get(i))).sum())
                    .isGreaterThan(25000);
            script((call, callback) -> {
                if (call <= calls.size()) {
                    String callId = "cumulative-call-" + call;
                    String name = skillNames.get(calls.get(call - 1));
                    finish(callback, "tool_use",
                            new LlmStreamEvent.ToolUseStart(callId, "Skill"),
                            new LlmStreamEvent.ToolInputDelta(callId, "{\"skill\":\"" + name + "\"}"));
                } else {
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("done"));
                }
            });
            QueryConfig config = QueryConfig.withDefaults(
                    "mock-model", "You are a helpful assistant.", List.of(skillTool),
                    List.of(skillTool.toToolDefinition()),
                    8192, 200000, new ThinkingConfig.Disabled(), calls.size() + 1, "test");

            QueryEngine.QueryResult result = queryEngine.execute(
                    config, buildState("Run the requested skills in this session"), handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(handler.errors).isEmpty();
            assertThat(requests).hasSize(calls.size() + 1);
            for (int i = 0; i < calls.size(); i++) {
                String callId = "cumulative-call-" + (i + 1);
                String expectedBody = renderedBodies.get(calls.get(i));
                assertThat(toolResultBlocks(requests.get(i + 1)))
                        .filteredOn(block -> callId.equals(block.get("tool_use_id")))
                        .singleElement().satisfies(block -> {
                            assertThat(block.get("is_error")).isNull();
                            assertThat(block.get("content")).isEqualTo(expectedBody);
                        });
            }
            verify(runTracker).completeRun(eq(run.id()), anyInt(), anyDouble(), anyInt(), eq(calls.size() + 1));
        }

        @Test
        @DisplayName("关闭技能不出现在实际模型请求或未知技能建议中")
        void disabledSkillIsAbsentFromActualModelRequestsAndSuggestions() throws Exception {
            registerIsolatedSkill();
            registry.setEnabled(DISABLED_NAME, false);
            script((call, callback) -> {
                if (call == 1) {
                    finish(callback, "tool_use",
                            new LlmStreamEvent.ToolUseStart("skill-call-1", "Skill"),
                            new LlmStreamEvent.ToolInputDelta("skill-call-1",
                                    "{\"skill\":\"missing-workflow\"}"));
                } else {
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("done"));
                }
            });

            QueryEngine.QueryResult result = queryEngine.execute(
                    configFromRegistry(realToolRegistry(), false), buildState("Find a workflow"), handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(completeRequests).hasSize(2);
            // The user and scripted model never supplied the disabled markers: none may be added by the app.
            assertThat(objectMapper.writeValueAsString(completeRequests))
                    .doesNotContain(DISABLED_NAME, DISABLED_DESCRIPTION, "DISABLED_SKILL_BODY_SENTINEL");
            assertThat(toolResultBlocks(requests.get(1))).anySatisfy(block -> {
                assertThat(block.get("is_error")).isEqualTo(true);
                assertThat((String) block.get("content")).contains("Available skills: greet");
            });
        }

        @Test
        @DisplayName("配置建立后关闭技能，旧工具通过别名调用也不能渲染正文")
        void disabledSkillRejectsStaleToolCallsBeforeRendering() throws Exception {
            registerIsolatedSkill();
            assertThat(registry.resolve("/Isolated-Alias").name()).isEqualTo(DISABLED_NAME);
            QueryConfig staleConfig = configFromRegistry(realToolRegistry(), false);
            script((call, callback) -> {
                if (call == 1) {
                    registry.setEnabled(DISABLED_NAME, false);
                    finish(callback, "tool_use",
                            new LlmStreamEvent.ToolUseStart("skill-call-1", "Skill"),
                            new LlmStreamEvent.ToolInputDelta("skill-call-1",
                                    "{\"skill\":\"/Isolated-Alias\",\"args\":\"name=World\"}"));
                } else {
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("done"));
                }
            });

            QueryEngine.QueryResult result = queryEngine.execute(
                    staleConfig, buildState("Use the previously selected workflow"), handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(completeRequests).hasSize(2);
            assertThat(objectMapper.writeValueAsString(completeRequests))
                    .doesNotContain(DISABLED_NAME, DISABLED_DESCRIPTION, "DISABLED_SKILL_BODY_SENTINEL");
            assertThat(toolResultBlocks(requests.get(1))).anySatisfy(block -> {
                assertThat(block.get("tool_use_id")).isEqualTo("skill-call-1");
                assertThat(block.get("is_error")).isEqualTo(true);
                assertThat((String) block.get("content")).contains("Skill not found");
            });
        }

        @Test
        @DisplayName("全部关闭后下一条主代理请求不含 Skill，重新开启后恢复")
        void nextRootRequestsReflectAllDisabledAndReenabledSkills() throws Exception {
            assertNextRequestsReflectToggle(false);
        }

        @Test
        @DisplayName("全部关闭后下一条子代理请求不含 Skill，重新开启后恢复")
        void nextSubAgentRequestsReflectAllDisabledAndReenabledSkills() throws Exception {
            when(runTracker.startRun("test-session", null, "subagent", "mock-model")).thenReturn(run);
            assertNextRequestsReflectToggle(true);
            verify(runTracker, times(3)).startRun("test-session", null, "subagent", "mock-model");
        }

        private void assertNextRequestsReflectToggle(boolean subAgent) throws Exception {
            ToolRegistry tools = realToolRegistry();
            script((call, callback) -> {
                if (call == 3) {
                    finish(callback, "tool_use",
                            new LlmStreamEvent.ToolUseStart("restored-skill-call", "Skill"),
                            new LlmStreamEvent.ToolInputDelta("restored-skill-call",
                                    "{\"skill\":\"greet\",\"args\":\"name=World\"}"));
                } else {
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("done"));
                }
            });

            for (boolean enabled : List.of(true, false, true)) {
                registry.setEnabled("greet", enabled);
                // Each execute models a new user message, with a newly assembled tool configuration.
                authority.set(run);
                QueryLoopState state = buildState("Continue the task");
                if (subAgent) {
                    state.setToolUseContext(state.getToolUseContext()
                            .withParentSessionId("parent-session").withNestingDepth(1));
                }
                QueryConfig config = configFromRegistry(tools, subAgent);
                assertThat(config.tools().stream().map(Tool::getName).toList().contains("Skill"))
                        .isEqualTo(enabled);
                assertThat(queryEngine.execute(config, state, handler).isSuccess()).isTrue();
            }

            assertThat(handler.errors).isEmpty();
            assertThat(completeRequests).hasSize(4);
            assertThat(toolNames(completeRequests.get(0))).contains("Skill");
            assertThat(toolNames(completeRequests.get(1))).doesNotContain("Skill");
            assertThat(toolNames(completeRequests.get(2))).contains("Skill");
            assertThat(objectMapper.writeValueAsString(completeRequests.get(1))).doesNotContain("BODY_SENTINEL");
            assertThat(toolResultBlocks(requests.get(3))).anySatisfy(block -> {
                assertThat(block.get("tool_use_id")).isEqualTo("restored-skill-call");
                assertThat(block.get("content")).isEqualTo(RENDERED_BODY);
                assertThat(block.get("is_error")).isNull();
            });
        }

        private void registerIsolatedSkill() {
            registry.register(SkillDefinition.fromMarkdown(DISABLED_NAME + ".md",
                    "---\nname: isolated-alias\ndescription: " + DISABLED_DESCRIPTION
                            + "\ncontext: inline\n---\n" + DISABLED_BODY,
                    SkillDefinition.SkillSource.PROJECT, null));
        }

        private ToolRegistry realToolRegistry() {
            OperationAnalyzerRegistry analyzers = mock(OperationAnalyzerRegistry.class);
            when(analyzers.isExplicitCoreTool(anyString())).thenReturn(true);
            ToolRegistry tools = new ToolRegistry(List.of(skillTool, controlTool), analyzers);
            tools.activate("test-session", List.of("Skill"));
            return tools;
        }

        private QueryConfig configFromRegistry(ToolRegistry tools, boolean subAgent) {
            List<Tool> enabled = subAgent ? tools.getSubAgentTools()
                    : tools.getEnabledToolsSortedCached("test-session");
            List<Map<String, Object>> definitions = subAgent
                    ? enabled.stream().map(Tool::toToolDefinition).toList()
                    : tools.getActiveToolDefinitions("test-session");
            return QueryConfig.withDefaults("mock-model", "You are a helpful assistant.",
                    enabled, definitions, 8192, 200000, new ThinkingConfig.Disabled(), 2, "test");
        }

        private List<String> toolNames(Map<String, Object> request) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> definitions = (List<Map<String, Object>>) request.get("tools");
            return definitions.stream()
                    .map(definition -> (String) ((Map<?, ?>) definition.get("function")).get("name"))
                    .toList();
        }

        private void script(BiConsumer<Integer, StreamChatCallback> response) {
            LlmProvider provider = mock(LlmProvider.class);
            when(providerRegistry.getProvider(anyString())).thenReturn(provider);
            doAnswer(inv -> {
                List<Map<String, Object>> messages = inv.getArgument(1);
                requests.add(List.copyOf(messages));
                completeRequests.add(Map.of("messages", List.copyOf(messages),
                        "system", inv.<String>getArgument(2), "tools", List.copyOf(inv.getArgument(3))));
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

        private QueryConfig twoRoundConfig() {
            return QueryConfig.withDefaults(
                    "mock-model", "You are a helpful assistant.",
                    List.of(skillTool, controlTool),
                    List.of(skillTool.toToolDefinition(), controlTool.toToolDefinition()),
                    8192, 200000, new ThinkingConfig.Disabled(), 2, "test");
        }

        private static int countOccurrences(String haystack, String needle) {
            int count = 0;
            int index = 0;
            while ((index = haystack.indexOf(needle, index)) != -1) {
                count++;
                index += needle.length();
            }
            return count;
        }

        private static List<Map<String, Object>> toolResultBlocks(List<Map<String, Object>> messages) {
            List<Map<String, Object>> blocks = new ArrayList<>();
            for (Map<String, Object> message : messages) {
                if (!(message.get("content") instanceof List<?> content)) continue;
                for (Object block : content) {
                    if (block instanceof Map<?, ?> map && "tool_result".equals(map.get("type"))) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> typed = (Map<String, Object>) map;
                        blocks.add(typed);
                    }
                }
            }
            return blocks;
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
            when(compactService.reactiveCompact(anyList(), any(CompactionContext.class), eq(false)))
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
            Tool bashTool = mock(Tool.class);
            when(bashTool.getName()).thenReturn("BashTool");
            when(providerRegistry.getProvider(anyString())).thenReturn(mockProvider);
            when(messageNormalizer.normalizeTyped(anyList())).thenReturn(List.of());

            StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
            when(streamingToolExecutor.newSession(any())).thenReturn(session);
            lenient().when(session.isAllCompleted()).thenReturn(true);
            lenient().when(session.yieldCompleted()).thenReturn(List.of());

            when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                var supplier = inv.getArgument(0, Supplier.class);
                return supplier.get();
            });

            // Always return tool_use to keep loop going
            doAnswer(inv -> {
                StreamChatCallback callback = inv.getArgument(7);
                String toolId = "t-" + System.nanoTime();
                callback.onEvent(new LlmStreamEvent.ToolUseStart(toolId, "BashTool"));
                callback.onEvent(new LlmStreamEvent.ToolInputDelta(toolId, "{}"));
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
                    List.of(bashTool), List.of(),
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

            when(modelRegistry.getContextWindowForModel("fallback-model")).thenReturn(32000);
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
            assertThat(state.getCompactionContext().model()).isEqualTo("fallback-model");
            assertThat(state.getCompactionContext().contextWindow()).isEqualTo(32000);
            int fallbackBudget = 32000 - 8192 - (int)("You are helpful.".length()/3.5) - 1600;
            verify(tokenBudgetGuard).enforcePhase2(anyList(),eq(fallbackBudget),anySet(),eq(3.5));
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

            // Without a Run authority cancellation cannot be claimed as confirmed or successful.
            assertThat(result.stopReason()).isEqualTo("error");
            assertThat(result.error()).contains("RUN_TERMINATION_UNCONFIRMED");
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void handoffProjectionOnlyRunsForExplicitlyMergedSessions(boolean merged) {
        var handoff=mock(com.aicodeassistant.engine.HandoffContextService.class);
        var reference=new Message.UserMessage("handoff-test",Instant.EPOCH,
                List.of(new ContentBlock.TextBlock("HandoffRead historical reference")),null,null);
        if(merged) when(handoff.project(any(),anyString(),anyInt(),anyDouble(),anyBoolean())).thenReturn(
                new com.aicodeassistant.engine.HandoffContextService.Projection(List.of(reference),200));
        org.springframework.test.util.ReflectionTestUtils.setField(queryEngine,"handoffContext",handoff);
        var provider=mock(LlmProvider.class); when(providerRegistry.getProvider(anyString())).thenReturn(provider);
        when(messageNormalizer.normalizeTyped(anyList())).thenAnswer(i -> new MessageNormalizer().normalizeTyped(i.getArgument(0)));
        when(streamingToolExecutor.newSession(any())).thenReturn(mock(StreamingToolExecutor.ExecutionSession.class));
        when(apiRetryService.executeWithRetry(any(),anyString(),anyString(),any())).thenAnswer(i -> i.getArgument(0,Supplier.class).get());
        AtomicReference<List<Map<String,Object>>> payload=new AtomicReference<>();
        doAnswer(i -> {
            payload.set(i.getArgument(1)); StreamChatCallback cb=i.getArgument(7);
            cb.onEvent(new LlmStreamEvent.TextDelta("Continue with current code"));
            cb.onEvent(new LlmStreamEvent.MessageDelta(new Usage(10,5,0,0),"end_turn")); cb.onComplete(); return null;
        }).when(provider).streamChat(anyString(),anyList(),anyString(),anyList(),anyInt(),any(),any(LlmCallContext.class),any(StreamChatCallback.class));
        when(hookService.executeStopHooks(anyList(),anyString())).thenReturn(HookRegistry.StopHookResult.ok());
        var state=buildState("E current TODO supersedes historical TODO");
        if(merged) state.setHandoffOperationId("merge-test");
        var result=queryEngine.execute(buildConfig(),state,handler);
        assertThat(result.isSuccess()).isTrue();
        assertThat(objectMapper.valueToTree(payload.get()).toString()).contains("E current TODO");
        if(merged) assertThat(objectMapper.valueToTree(payload.get()).toString()).contains("HandoffRead");
        else {
            assertThat(objectMapper.valueToTree(payload.get()).toString()).doesNotContain("HandoffRead");
            verifyNoInteractions(handoff);
        }
        assertThat(state.getMessages()).noneMatch(m -> m.uuid().equals("handoff-test"));
        var phase1=org.mockito.ArgumentCaptor.forClass(Integer.class);
        var phase2=org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(tokenBudgetGuard).enforcePhase1(anyList(),phase1.capture(),anyDouble(),nullable(String.class));
        verify(tokenBudgetGuard).enforcePhase2(anyList(),phase2.capture(),anySet(),anyDouble());
        assertThat(phase2.getValue()-phase1.getValue()).isEqualTo(merged ? 200 : 0);
        assertThat(state.getCompactionContext().historyBudget()).isEqualTo(phase1.getValue());
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
        final List<Message.AssistantMessage> assistantMessages = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final List<ContentBlock.ToolResultBlock> toolResults = new CopyOnWriteArrayList<>();
        final List<Usage> usageEvents = new CopyOnWriteArrayList<>();
        @Override public void onTextDelta(String text) { textDeltas.add(text); }
        @Override public void onToolUseStart(String id, String name) {}
        @Override public void onToolUseComplete(String id, ContentBlock.ToolUseBlock toolUse) {}
        @Override public void onToolResult(String id, ContentBlock.ToolResultBlock result) { toolResults.add(result); }
        @Override public void onAssistantMessage(Message.AssistantMessage message) { assistantMessages.add(message); }
        @Override public void onUsage(Usage usage) { usageEvents.add(usage); }
        @Override public void onError(Throwable error) { errors.add(error); }
    }
    @org.junit.jupiter.api.Test
    void mergedEntryCapacityUsesTheExistingContextFailureResult() {
        QueryConfig config=buildConfig();
        QueryLoopState state=buildState("synthetic user request");
        state.setHandoffOperationId("synthetic-merge");
        HandoffContextService handoff=mock(HandoffContextService.class);
        when(handoff.project(any(),anyString(),anyInt(),anyDouble(),anyBoolean()))
                .thenThrow(new HandoffContextService.CapacityException());
        org.springframework.test.util.ReflectionTestUtils.setField(queryEngine,"handoffContext",handoff);
        QueryMessageHandler handler=mock(QueryMessageHandler.class);
        QueryEngine.QueryResult result=queryEngine.execute(config,state,handler);
        assertThat(result.error()).startsWith("CONTEXT_RECOVERY_EXHAUSTED: HANDOFF_CONTEXT_BUDGET_TOO_SMALL");
        verify(handler).onError(isA(HandoffContextService.CapacityException.class));
        verify(providerRegistry,never()).getProvider(anyString());
        assertThat(state.getMessages()).anyMatch(m -> m instanceof com.aicodeassistant.model.Message.UserMessage);
    }

}
