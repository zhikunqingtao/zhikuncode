package com.aicodeassistant.engine;

import com.aicodeassistant.config.AgentTimeoutConfig;
import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.config.database.V002_InitProjectSchema;
import com.aicodeassistant.config.database.V014_RebuildRunV2Schema;
import com.aicodeassistant.config.database.V015_CreateInteractionSchema;
import com.aicodeassistant.config.database.V019_CreateAuthorizationSchema;
import com.aicodeassistant.config.database.V027_AddRunUsageSnapshot;
import com.aicodeassistant.engine.scheduling.ToolPriorityScheduler;
import com.aicodeassistant.engine.strategy.DefaultTerminationStrategy;
import com.aicodeassistant.history.FileHistoryService;
import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.llm.ApiCircuitBreaker;
import com.aicodeassistant.llm.LlmProvider;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.llm.LlmStreamEvent;
import com.aicodeassistant.llm.ModelAwareRetryPolicy;
import com.aicodeassistant.llm.ModelCapabilities;
import com.aicodeassistant.llm.ModelRegistry;
import com.aicodeassistant.llm.StreamChatCallback;
import com.aicodeassistant.llm.ThinkingConfig;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.Usage;
import com.aicodeassistant.run.RunControlService;
import com.aicodeassistant.run.RunEnvelope;
import com.aicodeassistant.run.RunEnvelopeRepository;
import com.aicodeassistant.run.RunExecutionRegistry;
import com.aicodeassistant.run.RunTerminationCoordinator;
import com.aicodeassistant.run.RunTracker;
import com.aicodeassistant.security.SensitiveDataFilter;
import com.aicodeassistant.tool.StreamingToolExecutor;
import com.aicodeassistant.tool.ToolExecutionPipeline;
import com.aicodeassistant.tool.ToolUseContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real query/run persistence path with a local provider double and a migrated temporary database. */
class QueryEnginePromptHashIntegrationTest {
    private static final String MODEL = "mock-model";
    private static final String ROOT_SESSION = "prompt-hash-root";

    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<RunEnvelope> snapshotsAtProvider = new ArrayList<>();
    private SqliteConfig sqlite;
    private JdbcTemplate jdbc;
    private RunControlService control;
    private RunEnvelopeRepository envelopes;
    private RunTracker tracker;
    private RunExecutionRegistry executions;
    private RunTerminationCoordinator termination;
    private SimpleMeterRegistry meters;
    private LlmProvider provider;
    private QueryEngine engine;
    private String activeSession;

    @BeforeEach
    void setUp() {
        DatabaseResolver resolver = new DatabaseResolver(
                temp.resolve("global.db").toString(), temp.toString());
        sqlite = new SqliteConfig(resolver);
        var source = sqlite.getProjectDataSource(temp);
        jdbc = new JdbcTemplate(source);
        new V002_InitProjectSchema(jdbc).execute();
        // V014 clears these older tables before creating the authoritative Run/Event schema.
        jdbc.execute("CREATE TABLE agent_checkpoints(id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE artifact_entries(id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE artifact_manifests(id TEXT PRIMARY KEY)");
        var runSchema = new V014_RebuildRunV2Schema(jdbc);
        runSchema.execute();
        runSchema.validate();
        new V015_CreateInteractionSchema(jdbc).execute();
        new V019_CreateAuthorizationSchema(jdbc).execute();
        var usageSchema = new V027_AddRunUsageSnapshot(jdbc);
        usageSchema.execute();
        usageSchema.validate();
        String now = Instant.now().toString();
        jdbc.update("INSERT INTO sessions(id,model,working_dir,created_at,updated_at) VALUES(?,?,?,?,?)",
                ROOT_SESSION, MODEL, temp.toString(), now, now);
        control = new RunControlService(jdbc, sqlite, resolver,
                new DataSourceTransactionManager(source), mapper, new SensitiveDataFilter());
        envelopes = new RunEnvelopeRepository(jdbc);
        executions = new RunExecutionRegistry();
        termination = mock(RunTerminationCoordinator.class);
        tracker = new RunTracker(control, envelopes, mock(ApplicationEventPublisher.class),
                termination, executions);

        provider = mock(LlmProvider.class);
        when(provider.getProviderName()).thenReturn("prompt-hash-test");
        when(provider.getSupportedModels()).thenReturn(List.of(MODEL));
        var capabilities = new ModelCapabilities(MODEL, "Test", 8192, 200000,
                true, false, true, 5, true, 0.0, 0.0);
        when(provider.getModelCapabilities(MODEL)).thenReturn(capabilities);
        doAnswer(invocation -> {
            snapshotsAtProvider.add(envelopes.findBySession(activeSession, 1).getFirst());
            StreamChatCallback callback = invocation.getArgument(7);
            callback.onEvent(new LlmStreamEvent.TextDelta("已完成。"));
            callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(10, 5, 0, 0), "end_turn"));
            callback.onComplete();
            return null;
        }).when(provider).streamChat(eq(MODEL), anyList(), nullable(String.class), anyList(),
                anyInt(), any(), any(), any());

        // Same lightweight, non-Spring query-loop setup as QueryFlowIntegrationTest.
        var providers = new LlmProviderRegistry(List.of(provider), null);
        var tokens = new TokenCounter(null, null, null);
        var tiers = new ModelTierService();
        var retry = new ApiRetryService(tiers, new ModelAwareRetryPolicy(), new ApiCircuitBreaker());
        var hooks = new HookService(new HookRegistry(), null);
        meters = new SimpleMeterRegistry();
        var tools = new StreamingToolExecutor(mock(ToolExecutionPipeline.class), meters);
        var cascade = mock(ContextCascade.class);
        when(cascade.executePreApiCascade(anyList(), anyString(), any(), any())).thenAnswer(invocation -> {
            List<Message> messages = invocation.getArgument(0);
            int count = tokens.estimateTokens(messages);
            return new ContextCascade.CascadeResult(messages, count, count,
                    false, 0, false, 0, false, 0, false, false, null);
        });
        var budget = mock(TokenBudgetGuard.class);
        when(budget.enforcePhase1(anyList(), anyInt(), anyDouble(), nullable(String.class)))
                .thenAnswer(invocation -> new TokenBudgetGuard.GuardResult(invocation.getArgument(0), false, 0, 0));
        when(budget.enforcePhase2(anyList(), anyInt(), anyMap(), anySet(), anyDouble()))
                .thenAnswer(invocation -> new TokenBudgetGuard.FinalBudgetResult(
                        invocation.getArgument(0), Set.of(), Set.of(), 0, invocation.getArgument(1), true, ""));
        var images = mock(ImageRefInjector.class);
        when(images.injectForApiCall(anyList(), anyInt(), anyInt(), anySet(), anyMap(), nullable(String.class), anyInt()))
                .thenAnswer(invocation -> new ImageRefInjector.InjectResult(invocation.getArgument(0), Set.of(), Map.of()));
        var transcoder = mock(UserImageTranscoder.class);
        when(transcoder.transcode(anyList(), any(), nullable(String.class), any(), anyInt()))
                .thenAnswer(invocation -> new UserImageTranscoder.TranscodeResult(invocation.getArgument(0), 0, List.of()));
        var models = mock(ModelRegistry.class);
        when(models.getContextWindowForModel(MODEL)).thenReturn(200000);
        when(models.getTokenCharRatio(MODEL)).thenReturn(3.5);
        when(models.getCapabilities(MODEL)).thenReturn(capabilities);
        engine = new QueryEngine(providers, new CompactService(tokens, providers, null, null), retry,
                tokens, mapper, tools, new MessageNormalizer(), hooks,
                new SnipService(), new MicroCompactService(tokens), models, null, tiers,
                mock(FileHistoryService.class), mock(ToolResultSummarizer.class), cascade,
                mock(CompactMetrics.class), null, null, null, mock(FeatureFlagService.class),
                new DefaultTerminationStrategy(), new ToolPriorityScheduler(), null, new AgentTimeoutConfig(),
                budget, images, tracker, executions, transcoder);
    }

    @AfterEach
    void close() {
        if (engine != null) engine.shutdownCleanupScheduler();
        if (meters != null) meters.close();
        if (sqlite != null) sqlite.destroy();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\n", "你是智坤助手。\n验证 UTF-8：🧪", " 你是智坤助手。\n验证 UTF-8：🧪\n"})
    void rootRunStoresExactlyThePromptPassedToProviderThroughCompletion(String prompt) throws Exception {
        RunEnvelope run = execute(prompt, ToolUseContext.of(temp.toString(), ROOT_SESSION));

        assertThat(run.parentRunId()).isNull();
        assertThat(run.agentType()).isEqualTo("query");
        assertProviderAndPersistenceAgree(prompt, run);
    }

    @Test
    void childRunKeepsItsOwnFingerprintAndDoesNotOverwriteParent() throws Exception {
        String parentHash = independentFingerprint("父代理系统提示");
        RunEnvelope parent = tracker.startRun(ROOT_SESSION, null, "query", MODEL, parentHash);
        String childPrompt = "子代理任务：核验文件边界 🧪";
        ToolUseContext context = ToolUseContext.of(temp.toString(), "prompt-hash-child")
                .withParentSessionId(ROOT_SESSION).withCurrentRunId(parent.id());

        RunEnvelope child = execute(childPrompt, context);

        assertThat(child.parentRunId()).isEqualTo(parent.id());
        assertThat(child.agentType()).isEqualTo("subagent");
        assertProviderAndPersistenceAgree(childPrompt, child);
        assertThat(child.promptHash()).isNotEqualTo(parentHash);
        assertThat(envelopes.findById(parent.id()).orElseThrow().promptHash()).isEqualTo(parentHash);
        assertThat(control.complete(parent.id(), 0, 0, 0)).isEqualTo(RunControlService.TransitionResult.APPLIED);
        RunEnvelope completedParent = envelopes.findById(parent.id()).orElseThrow();
        assertThat(completedParent.status()).isEqualTo(RunEnvelope.RunStatus.COMPLETED);
        assertThat(completedParent.promptHash()).isEqualTo(parentHash);
        assertThat(envelopes.findTree(parent.id())).extracting(RunEnvelope::id)
                .containsExactlyInAnyOrder(parent.id(), child.id());
    }

    private RunEnvelope execute(String prompt, ToolUseContext context) {
        activeSession = context.sessionId();
        QueryConfig config = QueryConfig.withDefaults(MODEL, prompt, List.of(), List.of(),
                8192, 200000, new ThinkingConfig.Disabled(), 2, "prompt-hash-test");
        Message.UserMessage user = new Message.UserMessage(UUID.randomUUID().toString(), Instant.now(),
                List.of(new ContentBlock.TextBlock("确认完成")), null, null);
        QueryLoopState state = new QueryLoopState(List.of(user), context);
        QueryMessageHandler handler = mock(QueryMessageHandler.class);

        QueryEngine.QueryResult result = engine.execute(config, state, handler);

        verify(handler, never()).onError(any());
        verifyNoInteractions(termination);
        assertThat(result.isSuccess()).isTrue();
        assertThat(executions.activeRunForSession(context.sessionId())).isEmpty();
        RunEnvelope run = envelopes.findById(state.getToolUseContext().currentRunId()).orElseThrow();
        assertThat(run.status()).isEqualTo(RunEnvelope.RunStatus.COMPLETED);
        assertThat(run.terminalAt()).isNotNull();
        return run;
    }

    private void assertProviderAndPersistenceAgree(String configuredPrompt, RunEnvelope completed) throws Exception {
        ArgumentCaptor<String> sentPrompt = ArgumentCaptor.forClass(String.class);
        verify(provider).streamChat(eq(MODEL), anyList(), sentPrompt.capture(), anyList(),
                anyInt(), any(), any(), any());
        assertThat(sentPrompt.getValue()).isEqualTo(configuredPrompt);
        String expected = independentFingerprint(sentPrompt.getValue());
        assertThat(snapshotsAtProvider).hasSize(1);
        assertThat(snapshotsAtProvider.getFirst().id()).isEqualTo(completed.id());
        assertThat(snapshotsAtProvider.getFirst().status()).isEqualTo(RunEnvelope.RunStatus.RUNNING);
        assertThat(snapshotsAtProvider.getFirst().promptHash()).isEqualTo(expected);
        assertThat(completed.promptHash()).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT prompt_hash FROM run_envelopes WHERE id=?",
                String.class, completed.id())).isEqualTo(expected);
        String event = jdbc.queryForObject(
                "SELECT event_data FROM run_event_log WHERE run_id=? AND event_type='run_started'",
                String.class, completed.id());
        assertThat(mapper.readTree(event).path("data").path("promptHash").asText())
                .isEqualTo(expected == null ? "unknown" : expected);
    }

    private static String independentFingerprint(String prompt) throws Exception {
        if (prompt == null) return null;
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(prompt.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest, 0, 8);
    }
}
