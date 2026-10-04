package com.aicodeassistant.engine;

import com.aicodeassistant.config.AgentTimeoutConfig;
import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.engine.scheduling.ToolPriorityScheduler;
import com.aicodeassistant.engine.strategy.DefaultTerminationStrategy;
import com.aicodeassistant.engine.strategy.TerminationStrategy.LoopContext;
import com.aicodeassistant.history.FileHistoryService;
import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.llm.*;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.Usage;
import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real query loop, ordered tool executor and failure tracking; provider and tool effects are local scripts. */
class QueryEngineErrorRecoveryTest {
    private static final String RECOVERY_HINT = "[System] Multiple consecutive errors detected.";
    private static final ToolResult REMOTE_FAILURE = ToolResult.failed(
            ToolResult.ToolFailureType.PROVIDER, "MCP_TOOL_REPORTED_ERROR", "Synthetic remote failure",
            ToolResult.Retryability.NEVER, ToolResult.EffectState.UNKNOWN, null, Map.of());

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, ToolResult> scriptedResults = new ConcurrentHashMap<>();
    private final List<Integer> requestedMaxTokens = new ArrayList<>();
    private final DefaultTerminationStrategy strategy = spy(new DefaultTerminationStrategy());
    private final QueryMessageHandler handler = mock(QueryMessageHandler.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private LlmProvider provider;
    private HookService hooks;
    private QueryEngine engine;
    private QueryLoopState state;
    private int calls;

    private final Tool tool = new Tool() {
        @Override public String getName() { return "mcp__recovery__probe"; }
        @Override public String getDescription() { return "Returns a scripted result without external effects"; }
        @Override public Map<String, Object> getInputSchema() { return Map.of("type", "object"); }
        @Override public boolean isReadOnly(ToolInput input) { return true; }
        @Override public ToolResult call(ToolInput input, ToolUseContext context) {
            return scriptedResults.get(input.getString("result"));
        }
    };

    @BeforeEach
    void setUp() {
        LlmProviderRegistry providers = mock(LlmProviderRegistry.class);
        provider = mock(LlmProvider.class);
        when(providers.getProvider(anyString())).thenReturn(provider);
        when(provider.getProviderName()).thenReturn("scripted-recovery");
        ApiRetryService retries = mock(ApiRetryService.class);
        when(retries.executeWithRetry(any(), anyString(), anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(0, Supplier.class).get());

        ToolExecutionPipeline pipeline = mock(ToolExecutionPipeline.class);
        when(pipeline.execute(any(), any(), any(), any())).thenAnswer(inv -> {
            Tool current = inv.getArgument(0);
            return ToolExecutionResult.of(current.call(inv.getArgument(1), inv.getArgument(2)));
        });
        StreamingToolExecutor executor = new StreamingToolExecutor(pipeline, meterRegistry);
        hooks = mock(HookService.class);
        when(hooks.executeStopHooks(anyList(), anyString())).thenReturn(HookRegistry.StopHookResult.ok());

        ContextCascade cascade = mock(ContextCascade.class);
        when(cascade.executePreApiCascade(anyList(), anyString(), any(), any()))
                .thenAnswer(inv -> new ContextCascade.CascadeResult(
                        inv.getArgument(0), 0, 0, false, 0, false, 0, false, 0, false, false, null));
        ModelRegistry models = mock(ModelRegistry.class);
        when(models.getContextWindowForModel(anyString())).thenReturn(200_000);
        when(models.getTokenCharRatio(anyString())).thenReturn(3.5);
        when(models.getCapabilities(anyString())).thenReturn(new ModelCapabilities(
                "mock-model", "Mock Model", 8192, 200_000, true, false, true, 5, true, 0.0, 0.0));

        TokenBudgetGuard guard = mock(TokenBudgetGuard.class);
        when(guard.enforcePhase1(anyList(), anyInt(), anyDouble(), nullable(String.class)))
                .thenAnswer(inv -> new TokenBudgetGuard.GuardResult(inv.getArgument(0), false, 0, 0));
        when(guard.enforcePhase2(anyList(), anyInt(), anyMap(), anySet(), anyDouble()))
                .thenAnswer(inv -> new TokenBudgetGuard.FinalBudgetResult(
                        inv.getArgument(0), Set.of(), Set.of(), 0, inv.getArgument(1), true, ""));
        ImageRefInjector images = mock(ImageRefInjector.class);
        when(images.injectForApiCall(anyList(), anyInt(), anyInt(), anySet(), anyMap(), nullable(String.class), anyInt()))
                .thenAnswer(inv -> new ImageRefInjector.InjectResult(inv.getArgument(0), Set.of(), Map.of()));
        UserImageTranscoder transcoder = mock(UserImageTranscoder.class);
        when(transcoder.transcode(anyList(), any(), nullable(String.class), any(), anyInt()))
                .thenAnswer(inv -> new UserImageTranscoder.TranscodeResult(inv.getArgument(0), 0, List.of()));
        ToolResultSummarizer summarizer = mock(ToolResultSummarizer.class);
        when(summarizer.processToolResults(anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));

        engine = new QueryEngine(providers, mock(CompactService.class), retries, mock(TokenCounter.class),
                json, executor, new MessageNormalizer(), hooks, new SnipService(),
                mock(MicroCompactService.class), models, mock(ThinkingBudgetCalculator.class),
                new ModelTierService(), mock(FileHistoryService.class), summarizer, cascade,
                mock(CompactMetrics.class), null, null, null, mock(FeatureFlagService.class),
                strategy, new ToolPriorityScheduler(), null, new AgentTimeoutConfig(), guard, images,
                null, null, transcoder);
        state = new QueryLoopState(List.of(new Message.UserMessage("user", Instant.EPOCH,
                List.of(new ContentBlock.TextBlock("Inspect the available files and report the result.")), null, null)),
                ToolUseContext.of(System.getProperty("java.io.tmpdir"), "recovery-test"));
    }

    @AfterEach
    void close() {
        if (engine != null) engine.shutdownCleanupScheduler();
        meterRegistry.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"end_turn", "stop"})
    void finalResponseAfterThreeFailuresCompletesWithoutAnotherModelCall(String stopReason) {
        script(tools(REMOTE_FAILURE), tools(REMOTE_FAILURE), tools(REMOTE_FAILURE),
                new Reply(List.of(), "The service is unavailable; here is what I could verify.", stopReason));

        QueryEngine.QueryResult result = execute(8);

        assertThat(result.isSuccess()).isTrue();
        assertThat(calls).isEqualTo(4);
        assertThat(recoveryHints()).hasSize(1);
        verify(handler, never()).onError(any());
        verify(handler, times(1)).onTurnEnd(anyInt(), eq("switch_strategy"));
    }

    @Test
    void emptyFinalResponseAfterThreeFailuresStillRequiresAVisibleAnswer() {
        script(tools(REMOTE_FAILURE, REMOTE_FAILURE, REMOTE_FAILURE),
                new Reply(List.of(), "", "end_turn"), done());

        QueryEngine.QueryResult result = execute(6);

        assertThat(result.isSuccess()).isTrue();
        assertThat(calls).isEqualTo(3);
        assertThat(recoveryHints()).hasSize(1);
        assertThat(userTexts()).filteredOn(text -> text.startsWith(
                "Your previous response ended without a visible final answer.")).hasSize(1);
        verify(handler).onTurnEnd(2, "empty_final_response_retry");
        verify(handler).onTurnEnd(3, "end_turn");
        verify(handler, never()).onError(any());
    }

    @Test
    void finalResponseAfterThreeFailuresStillHonorsBlockingStopHooks() {
        String instruction = "Address the remaining verification issue before completing.";
        when(hooks.executeStopHooks(anyList(), anyString()))
                .thenReturn(HookRegistry.StopHookResult.blocking(List.of(instruction)));
        script(tools(REMOTE_FAILURE, REMOTE_FAILURE, REMOTE_FAILURE), done(),
                new Reply(List.of(), "The verification issue is now addressed.", "end_turn"));

        QueryEngine.QueryResult result = execute(6);

        assertThat(result.isSuccess()).isTrue();
        assertThat(calls).isEqualTo(3);
        assertThat(recoveryHints()).hasSize(1);
        assertThat(userTexts()).filteredOn(instruction::equals).hasSize(1);
        verify(hooks, times(1)).executeStopHooks(anyList(), anyString());
        verify(handler).onTurnEnd(2, "stop_hook_blocking");
        verify(handler).onTurnEnd(3, "end_turn");
        verify(handler, never()).onError(any());
    }

    @Test
    void thirdFourthAndFifthFailuresShareOneHintAndStillAllowCorrection() {
        script(tools(REMOTE_FAILURE), tools(REMOTE_FAILURE), tools(REMOTE_FAILURE),
                tools(REMOTE_FAILURE), tools(REMOTE_FAILURE), tools(ToolResult.success("corrected")), done());

        QueryEngine.QueryResult result = execute(10);

        assertThat(result.isSuccess()).isTrue();
        assertThat(calls).isEqualTo(7);
        assertThat(recoveryHints()).hasSize(1);
        ArgumentCaptor<LoopContext> contexts = ArgumentCaptor.forClass(LoopContext.class);
        verify(strategy, times(7)).evaluate(contexts.capture());
        assertThat(contexts.getAllValues()).extracting(LoopContext::consecutiveErrors)
                .containsExactly(1, 2, 3, 4, 5, 0, 0);
        verify(handler, never()).onTurnEnd(anyInt(), eq("request_user_input"));
        verify(handler, never()).onError(any());
    }

    @Test
    void fiveMissingFilesInOneBatchDoNotStopBeforeTheModelCanCorrectItsPaths() {
        ToolResult missing = ToolResult.validationError("FILE_NOT_FOUND", "Synthetic missing path");
        script(new Reply(Collections.nCopies(5, missing), null, "tool_use"),
                tools(ToolResult.success("found the correct path")), done());

        QueryEngine.QueryResult result = execute(6);

        assertThat(result.isSuccess()).isTrue();
        assertThat(calls).isEqualTo(3);
        assertThat(recoveryHints()).hasSize(1);
        verify(handler, times(6)).onToolResult(anyString(), any());
        verify(handler, never()).onTurnEnd(anyInt(), eq("request_user_input"));
    }

    @Test
    void batchCrossingFromTwoToFourFailuresStillReceivesOneHint() {
        script(tools(REMOTE_FAILURE, REMOTE_FAILURE), tools(REMOTE_FAILURE, REMOTE_FAILURE), done());

        assertThat(execute(6).isSuccess()).isTrue();

        assertThat(calls).isEqualTo(3);
        assertThat(recoveryHints()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "permission"})
    void resetOutcomeInsideABatchAllowsAHintForTheNewFailureStreak(String resetOutcome) {
        ToolResult reset = "success".equals(resetOutcome) ? ToolResult.success("found")
                : ToolResult.permissionDenied("PERMISSION_USER_DENIED", "declined");
        script(tools(REMOTE_FAILURE, REMOTE_FAILURE, REMOTE_FAILURE),
                tools(reset, REMOTE_FAILURE, REMOTE_FAILURE, REMOTE_FAILURE), done());

        assertThat(execute(6).isSuccess()).isTrue();

        assertThat(calls).isEqualTo(3);
        assertThat(recoveryHints()).hasSize(2);
        verify(handler, times(2)).onTurnEnd(anyInt(), eq("switch_strategy"));
        verify(handler, never()).onTurnEnd(anyInt(), eq("request_user_input"));
    }

    @Test
    void permissionDenialsAloneDoNotCauseRecoveryHints() {
        ToolResult denied = ToolResult.permissionDenied("PERMISSION_USER_DENIED", "declined");
        script(new Reply(Collections.nCopies(5, denied), null, "tool_use"), done());

        assertThat(execute(4).isSuccess()).isTrue();

        assertThat(recoveryHints()).isEmpty();
        assertThat(calls).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"max_tokens", "length"})
    void priorToolErrorsDoNotBlockOutputLimitRecovery(String stopReason) {
        script(tools(REMOTE_FAILURE, REMOTE_FAILURE, REMOTE_FAILURE),
                new Reply(List.of(), "partial answer", stopReason),
                new Reply(List.of(), "continued answer", stopReason), done());

        assertThat(execute(8).isSuccess()).isTrue();

        assertThat(calls).isEqualTo(4);
        assertThat(recoveryHints()).hasSize(1);
        assertThat(requestedMaxTokens).containsExactly(8192, 8192,
                QueryConfig.ESCALATED_MAX_TOKENS, QueryConfig.ESCALATED_MAX_TOKENS);
        assertThat(userTexts()).filteredOn(text -> text.startsWith("Output token limit hit.")).hasSize(1);
        verify(handler, times(2)).onTurnEnd(anyInt(), eq(stopReason));
    }

    @Test
    void hintSuppressionDoesNotExtendTheHardTurnLimit() {
        script(tools(REMOTE_FAILURE, REMOTE_FAILURE, REMOTE_FAILURE), tools(REMOTE_FAILURE),
                tools(REMOTE_FAILURE), done());

        QueryEngine.QueryResult result = execute(3);

        assertThat(result.stopReason()).isEqualTo("max_turns");
        assertThat(result.error()).startsWith("MAX_TURNS:");
        assertThat(calls).isEqualTo(3);
        assertThat(result.turnCount()).isEqualTo(3);
        assertThat(recoveryHints()).hasSize(1);
        verify(handler, never()).onTurnEnd(anyInt(), eq("request_user_input"));
    }

    private QueryEngine.QueryResult execute(int maxTurns) {
        QueryConfig config = QueryConfig.withDefaults("mock-model", "Test", List.of(tool), List.of(),
                8192, 200_000, new ThinkingConfig.Disabled(), maxTurns, "test");
        QueryEngine.QueryResult result = engine.execute(config, state, handler);
        // Suppression must not re-evaluate the policy with altered counters or activate its legacy stop branch.
        verify(strategy, times(calls)).evaluate(any());
        return result;
    }

    private void script(Reply... replies) {
        doAnswer(inv -> {
            int index = calls++;
            requestedMaxTokens.add(inv.getArgument(4));
            if (index >= replies.length) throw new AssertionError("Unexpected extra model request " + calls);
            Reply reply = replies[index];
            StreamChatCallback callback = inv.getArgument(7);
            for (int i = 0; i < reply.results().size(); i++) {
                String id = "call-" + index + "-" + i;
                scriptedResults.put(id, reply.results().get(i));
                callback.onEvent(new LlmStreamEvent.ToolUseStart(id, tool.getName()));
                callback.onEvent(new LlmStreamEvent.ToolInputDelta(id, "{\"result\":\"" + id + "\"}"));
            }
            if (reply.text() != null) callback.onEvent(new LlmStreamEvent.TextDelta(reply.text()));
            callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(10, 5, 0, 0), reply.stopReason()));
            callback.onComplete();
            return null;
        }).when(provider).streamChat(anyString(), anyList(), anyString(), anyList(), anyInt(), any(),
                any(LlmCallContext.class), any(StreamChatCallback.class));
    }

    private List<String> recoveryHints() {
        return userTexts().stream().filter(text -> text.startsWith(RECOVERY_HINT)).toList();
    }

    private List<String> userTexts() {
        return state.getMessages().stream().filter(Message.UserMessage.class::isInstance)
                .map(Message.UserMessage.class::cast).flatMap(message -> message.content().stream())
                .filter(ContentBlock.TextBlock.class::isInstance).map(ContentBlock.TextBlock.class::cast)
                .map(ContentBlock.TextBlock::text).toList();
    }

    private static Reply tools(ToolResult... results) { return new Reply(List.of(results), null, "tool_use"); }
    private static Reply done() { return new Reply(List.of(), "Finished inspection.", "end_turn"); }
    private record Reply(List<ToolResult> results, String text, String stopReason) {}
}
