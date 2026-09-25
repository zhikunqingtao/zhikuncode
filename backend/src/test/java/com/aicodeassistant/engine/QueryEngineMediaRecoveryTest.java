package com.aicodeassistant.engine;

import com.aicodeassistant.config.AgentTimeoutConfig;
import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.config.oss.OssPublishProperties;
import com.aicodeassistant.engine.scheduling.ToolPriorityScheduler;
import com.aicodeassistant.engine.strategy.DefaultTerminationStrategy;
import com.aicodeassistant.history.FileHistoryService;
import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.llm.*;
import com.aicodeassistant.model.*;
import com.aicodeassistant.run.RunEnvelope;
import com.aicodeassistant.run.RunTracker;
import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 测试 QueryEngine 的媒体错误判断、图片准备及 400 错误明确失败行为。
 */
@ExtendWith(MockitoExtension.class)
class QueryEngineMediaRecoveryTest {

    @ParameterizedTest
    @DisplayName("isMediaRelatedError 应识别媒体相关错误消息")
    @ValueSource(strings = {
            "image is invalid or too_large",
            "file_too_large: exceeds 10MB limit",
            "invalid_image format detected",
            "could not process image in request",
            "media_type_not_supported: audio/wav",
            "unsupported image url",
            "{\"error\":{\"message\":\"unsupported image url in request\"}}"
    })
    void isMediaRelatedError_shouldReturnTrue(String errorMessage) throws Exception {
        var exception = new LlmApiException(errorMessage, false, 413);
        boolean result = invokeIsMediaRelatedError(exception);
        assertTrue(result, "Should detect media error: " + errorMessage);
    }

    @ParameterizedTest
    @DisplayName("isMediaRelatedError 应排除非媒体错误")
    @ValueSource(strings = {
            "prompt is too long",
            "rate_limit_exceeded",
            "model_not_found",
            "insufficient_quota",
            "context_length_exceeded"
    })
    void isMediaRelatedError_shouldReturnFalse(String errorMessage) throws Exception {
        var exception = new LlmApiException(errorMessage, false, 413);
        boolean result = invokeIsMediaRelatedError(exception);
        assertFalse(result, "Should not detect as media error: " + errorMessage);
    }

    @Test
    @DisplayName("isMediaRelatedError 对null消息返回false")
    void isMediaRelatedError_nullMessage_shouldReturnFalse() throws Exception {
        var exception = new LlmApiException(null, false, 413);
        boolean result = invokeIsMediaRelatedError(exception);
        assertFalse(result);
    }

    /**
     * 通过反射调用 QueryEngine.isMediaRelatedError() 私有方法。
     */
    private boolean invokeIsMediaRelatedError(LlmApiException e) throws Exception {
        QueryEngine engine = createMinimalQueryEngine();
        Method method = QueryEngine.class.getDeclaredMethod("isMediaRelatedError", LlmApiException.class);
        method.setAccessible(true);
        return (boolean) method.invoke(engine, e);
    }

    /**
     * 创建最小化的QueryEngine实例（所有依赖为null，仅用于测试private方法）
     */
    private QueryEngine createMinimalQueryEngine() throws Exception {
        var constructor = QueryEngine.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object[] args = new Object[constructor.getParameterCount()];
        return (QueryEngine) constructor.newInstance(args);
    }

    // ═══════════════ 图片输入与错误保真 — 行为级测试 ═══════════════

    @Mock LlmProviderRegistry providerRegistry;
    @Mock CompactService compactService;
    @Mock ApiRetryService apiRetryService;
    @Mock TokenCounter tokenCounter;
    @Mock StreamingToolExecutor streamingToolExecutor;
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

    private static final String TRUSTED_URL =
            "https://zhikunshare.oss-cn-beijing.aliyuncs.com/zhikuncode-artifacts/clipboard/abc123.png";
    private static final String MEDIA_400_MESSAGE = "unsupported image url";
    private static final byte[] FAKE_PNG = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        // 默认 Snip/MicroCompact mock: 直接返回原消息列表
        lenient().when(snipService.snipToolResults(anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(microCompactService.compactMessages(anyList(), anyInt()))
                .thenReturn(new MicroCompactService.MicroCompactResult(List.of(), 0));
        // 默认 ModelTierService mock: 返回原模型（无降级）
        lenient().when(modelTierService.resolveModel(anyString(), anyList())).thenAnswer(inv -> inv.getArgument(0));
        // 默认 ModelRegistry mock: 返回合理的 contextWindow / URL 模式 capabilities
        lenient().when(modelRegistry.getContextWindowForModel(anyString())).thenReturn(200000);
        lenient().when(modelRegistry.getTokenCharRatio(anyString())).thenReturn(3.5);
        lenient().when(modelRegistry.getCapabilities(anyString())).thenReturn(urlModeCapabilities());
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
        var runs = new java.util.HashMap<String,RunEnvelope>();
        lenient().when(runTracker.startRun(anyString(), nullable(String.class), anyString(), anyString()))
            .thenAnswer(inv -> { var run = RunEnvelope.start(inv.getArgument(0),inv.getArgument(1),inv.getArgument(2),inv.getArgument(3)); runs.put(run.id(),run); return run; });
        lenient().when(runTracker.getRun(anyString())).thenAnswer(inv -> java.util.Optional.ofNullable(runs.get(inv.getArgument(0))));
        lenient().doAnswer(inv -> { String id = inv.getArgument(0); var run = runs.get(id);
            runs.put(id, new RunEnvelope(id,run.sessionId(),null,RunEnvelope.RunStatus.COMPLETED,run.agentType(),run.model(),null,
                run.startedAt(),Instant.now(),null,0,0,0,0,null,run.createdAt(),Instant.now(),1,
                RunEnvelope.RunExitReason.MODEL_FINISHED,null,RunEnvelope.VerificationStatus.NOT_REQUESTED,Instant.now(),null)); return null; })
            .when(runTracker).completeRun(anyString(),anyInt(),anyDouble(),anyInt(),anyInt());
        lenient().doAnswer(inv -> { String id = inv.getArgument(0); var run = runs.get(id);
            runs.put(id, new RunEnvelope(id,run.sessionId(),null,RunEnvelope.RunStatus.FAILED,run.agentType(),run.model(),null,
                run.startedAt(),Instant.now(),null,0,0,0,0,inv.getArgument(1),run.createdAt(),Instant.now(),1,
                RunEnvelope.RunExitReason.INTERNAL_ERROR,null,RunEnvelope.VerificationStatus.NOT_REQUESTED,Instant.now(),null)); return null; })
            .when(runTracker).failRun(anyString(),anyString());
        // 默认 ContextCascade mock: 直接返回原消息列表（无压缩）
        lenient().when(contextCascade.executePreApiCascade(anyList(), anyString(), any(), any()))
                .thenAnswer(inv -> {
                    List<Message> msgs = inv.getArgument(0);
                    int tokens = msgs.size() * 100;
                    return new ContextCascade.CascadeResult(
                            msgs, tokens, tokens, false, 0, false, 0, false, 0, false, false, null);
                });
    }

    private static ModelCapabilities urlModeCapabilities() {
        return new ModelCapabilities("mock-model", "Mock Model", 8192, 200000,
                true, false, true, 5, true, 0.0, 0.0);
    }

    private static ModelCapabilities base64OnlyCapabilities(String modelId) {
        return new ModelCapabilities(modelId, modelId, 8192, 200000,
                true, false, true, 5, true, 0.0, 0.0, 3.5, false,
                ModelCapabilities.ImageInputMode.BASE64_ONLY);
    }

    /** 覆盖 HTTP 下载的测试桩（参照 UserImageTranscoderTest 的子类覆盖模式）。 */
    static class StubTranscoder extends UserImageTranscoder {
        int fetchCount;
        byte[] imageBytes = FAKE_PNG;

        StubTranscoder(OssPublishProperties props) {
            super(props);
        }

        @Override
        protected DownloadedImage fetchImage(String url, com.aicodeassistant.llm.CancellationSignal cancellation) {
            fetchCount++;
            return new DownloadedImage(imageBytes, "image/png");
        }
    }

    @Nested
    @DisplayName("图片输入与错误保真")
    class MediaInputTests {

        @Test
        void media400MustFailWithoutStrippingEvenWithOneTurn() {
            ScriptedProvider scripted = scriptProvider((call, callback) -> {
                throw new LlmApiException(MEDIA_400_MESSAGE, false, 400);
            });
            QueryEngine engine = newEngine(new MessageNormalizer(), userImageTranscoder);
            QueryLoopState state = buildStateWithUrlImage();
            TestHandler handler = new TestHandler();
            QueryConfig config = QueryConfig.withDefaults("mock-model", "test", List.of(), List.of(),
                    8192, 200000, new ThinkingConfig.Disabled(), 1, "test");
            var result = engine.execute(config, state, handler);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.stopReason()).isEqualTo("error");
            assertThat(scripted.requests()).hasSize(1);
            assertThat(state.getMessages()).anyMatch(QueryEngineMediaRecoveryTest::hasImageBlock);
            assertThat(handler.errors).hasSize(1);
            assertThat(handler.recoveryEvents).isEmpty();
        }

        @Test
        @DisplayName("non-media-400-no-retry: 400 非媒体错误 → 不剥离不重试，错误直接上抛")
        void nonMediaError400_doesNotStripOrRetry() {
            ScriptedProvider scripted = scriptProvider((call, callback) -> {
                throw new LlmApiException("invalid api key", false, 400);
            });

            QueryEngine engine = newEngine(new MessageNormalizer(), userImageTranscoder);
            QueryLoopState state = buildStateWithUrlImage();
            TestHandler handler = new TestHandler();

            QueryEngine.QueryResult result = engine.execute(buildConfig("mock-model"), state, handler);

            // 只调用一次，未触发媒体剥离与重试
            assertThat(scripted.requests()).hasSize(1);
            assertThat(handler.recoveryEvents).isEmpty();
            assertThat(handler.compactEvents).isEmpty();
            // 请求与 state 历史中的图片块均保持原样
            assertThat(imageParts(scripted.requests().get(0))).isNotEmpty();
            assertThat(state.getMessages()).anyMatch(QueryEngineMediaRecoveryTest::hasImageBlock);
            // 错误直接上抛，整轮以 error 结束
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.stopReason()).isEqualTo("error");
            assertThat(handler.errors).hasSize(1);
            assertThat(handler.errors.get(0)).isInstanceOf(LlmApiException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"kimi-k3"})
        void realBudgetAndCapabilitiesAcceptLargePngWithoutChangingHistory(String model) throws Exception {
            OssPublishProperties ossProperties = new OssPublishProperties();
            ossProperties.setBucket("zhikunshare");
            ossProperties.setEndpoint("https://oss-cn-beijing.aliyuncs.com");
            StubTranscoder transcoder = new StubTranscoder(ossProperties);

            transcoder.imageBytes = UserImageTranscoderTest.png(640);
            tokenBudgetGuard = new TokenBudgetGuard();
            modelRegistry = new ModelRegistry(new LlmProviderRegistry(List.of(), new org.springframework.mock.env.MockEnvironment()));

            ScriptedProvider scripted = scriptProvider((call, callback) ->
                    finish(callback, "end_turn", new LlmStreamEvent.TextDelta("ok")));

            QueryEngine engine = newEngine(new MessageNormalizer(), transcoder);
            QueryLoopState state = buildStateWithUrlImage();
            TestHandler handler = new TestHandler();

            QueryEngine.QueryResult result = engine.execute(QueryConfig.withDefaults(model, "test", List.of(), List.of(),
                    QueryConfig.getRecommendedMaxTokens(modelRegistry, model), modelRegistry.getContextWindowForModel(model),
                    new ThinkingConfig.Disabled(), 10, "test"), state, handler);

            assertThat(result.isSuccess()).isTrue();
            assertThat(transcoder.fetchCount).isEqualTo(1);
            assertThat(scripted.requests()).hasSize(1);

            // 最终发给 provider 的请求里该图片是 base64 形态：source.type=="base64"、无 url 键
            List<Map<String, Object>> images = imageParts(scripted.requests().get(0));
            assertThat(images).hasSize(1);
            Object sourceObj = images.get(0).get("source");
            assertThat(sourceObj).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> source = (Map<String, Object>) sourceObj;
            assertThat(source.get("type")).isEqualTo("base64");
            assertThat(source).doesNotContainKey("url");
            assertThat(source.get("data")).isInstanceOf(String.class);
            assertThat((String) source.get("data")).isNotBlank();

            // state.getMessages() 中的历史仍保持 URL 形态（未被 base64 污染）
            Message.UserMessage history = state.getMessages().stream()
                    .filter(QueryEngineMediaRecoveryTest::hasImageBlock)
                    .map(Message.UserMessage.class::cast)
                    .findFirst().orElseThrow();
            ContentBlock.ImageBlock image = history.content().stream()
                    .filter(ContentBlock.ImageBlock.class::isInstance)
                    .map(ContentBlock.ImageBlock.class::cast)
                    .findFirst().orElseThrow();
            assertThat(image.url()).isEqualTo(TRUSTED_URL);
            assertThat(image.base64Data()).isNull();
        }
    }

    @Test
    void currentImageDownloadFailurePersistsAndPushesNoticeWithoutCallingProvider() {
        when(modelRegistry.getCapabilities("kimi-k3")).thenReturn(base64OnlyCapabilities("kimi-k3"));
        var provider = mock(LlmProvider.class);
        when(providerRegistry.getProvider(anyString())).thenReturn(provider);
        var transcoder = new UserImageTranscoder(UserImageTranscoderTest.properties()) {
            @Override protected DownloadedImage fetchImage(String url, CancellationSignal cancellation) throws java.io.IOException {
                throw new java.io.IOException("offline");
            }
        };
        var state = new QueryLoopState(List.of(UserImageTranscoderTest.message("current", UserImageTranscoderTest.URL + "x.png")),
                ToolUseContext.of("/tmp", "test-session"));
        var sessions = mock(com.aicodeassistant.session.SessionManager.class);
        com.aicodeassistant.session.SessionMessagePersistence.attach(state, sessions, "test-session", "test");
        var handler = new TestHandler();
        var result = newEngine(new MessageNormalizer(), transcoder).execute(buildConfig("kimi-k3"), state, handler);
        assertThat(result.isSuccess()).isFalse();
        verifyNoInteractions(compactService);
        assertThat(handler.systemMessages).hasSize(1);
        var notice = handler.systemMessages.getFirst();
        assertThat(notice.subtype()).isEqualTo("image_notice");
        assertThat(notice.content()).contains("本轮已停止");
        assertThat(state.getMessages()).contains(notice).anyMatch(QueryEngineMediaRecoveryTest::hasImageBlock);
        verify(sessions).addMessageWithId(eq(notice.uuid()), eq("test-session"), eq("system"), eq(notice.content()),
                isNull(), eq(0), eq(0), eq(Map.of("subtype", "image_notice")));
        verify(provider, never()).streamChat(anyString(), anyList(), anyString(), anyList(), anyInt(), any(), any(LlmCallContext.class), any());
    }

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path imageDirectory;

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void currentToolImageTakesSlotBeforeEightHistoricalAttachments(int currentImageCount) throws Exception {
        modelRegistry = new ModelRegistry(new LlmProviderRegistry(List.of(), new org.springframework.mock.env.MockEnvironment()));
        tokenBudgetGuard = new TokenBudgetGuard();
        tokenCounter = new TokenCounter(modelRegistry, null, featureFlagService);
        var transcoder = new StubTranscoder(UserImageTranscoderTest.properties());
        transcoder.imageBytes = UserImageTranscoderTest.png(16);
        byte[] toolBytes = UserImageTranscoderTest.png(32);
        var path = imageDirectory.resolve("fresh.png");
        java.nio.file.Files.write(path, toolBytes);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(toolBytes));
        var ref = new com.aicodeassistant.tool.impl.ImageResultExternalizer.ImageToolRef(
                path.toString(), "image/png", toolBytes.length, hash, 32, 32);
        var security = mock(com.aicodeassistant.security.PathSecurityService.class);
        when(security.checkReadPermission(path.toString(), imageDirectory.toString()))
                .thenReturn(com.aicodeassistant.security.PathSecurityService.PathCheckResult.allowed());
        imageRefInjector = spy(new ImageRefInjector(objectMapper, security));
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 8; i++) messages.add(UserImageTranscoderTest.message("old-" + i,
                UserImageTranscoderTest.URL + i + ".png"));
        messages.add(currentImageCount == 0
                ? new Message.UserMessage("current", Instant.now(), List.of(new ContentBlock.TextBlock("Read the new image")), null, null)
                : UserImageTranscoderTest.message("current", UserImageTranscoderTest.URL + "current.png"));
        messages.add(new Message.AssistantMessage("assistant", Instant.now(), List.of(
                new ContentBlock.ToolUseBlock("read", "Read", objectMapper.valueToTree(Map.of("file_path", path.toString())))), "tool_use", null));
        messages.add(new Message.UserMessage("tool-result", Instant.now(), List.of(
                new ContentBlock.ToolResultBlock("read", "[image_ref]" + objectMapper.writeValueAsString(ref) + "[/image_ref]", false)),
                null, "assistant"));
        var state = new QueryLoopState(messages, ToolUseContext.of(imageDirectory.toString(), "test-session"));
        var handler = new TestHandler();
        var scripted = scriptProvider((call, callback) -> finish(callback, "end_turn", new LlmStreamEvent.TextDelta("ok")));
        var result = newEngine(new MessageNormalizer(), transcoder).execute(buildConfig("kimi-k3"), state, handler);
        assertThat(result.isSuccess()).isTrue();
        var images = imageParts(scripted.requests().getFirst());
        assertThat(images).hasSize(8);
        assertThat(images).anySatisfy(image -> assertThat(((Map<?, ?>) image.get("source")).get("data"))
                .isEqualTo(java.util.Base64.getEncoder().encodeToString(toolBytes)));
        assertThat(handler.systemMessages).hasSize(1 + currentImageCount);
        assertThat(handler.systemMessages).allSatisfy(notice -> assertThat(notice.content()).contains("消息 old-", "本轮已省略").doesNotContain("本轮已停止"));
        assertThat(messages.subList(0, 8)).allSatisfy(message -> assertThat(
                ((ContentBlock.ImageBlock) ((Message.UserMessage) message).content().get(1)).url()).isNotNull());
        assertThat(((Message.UserMessage) messages.getLast()).content()).hasSize(1);
        var budget = org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(imageRefInjector).injectForApiCall(anyList(), anyInt(), budget.capture(), anySet(), anyMap(), anyString(), eq(8 - currentImageCount));
        int baseBudget = 1000000 - 8192 - (int) ("You are a helpful assistant.".length() / 3.5) - 50000
                - tokenCounter.estimateTokens(UserImageTranscoder.withoutImagesForBudget(messages), "kimi-k3");
        assertThat(budget.getValue()).isEqualTo(baseBudget - currentImageCount * InlineImageBudget.estimate(
                java.util.Base64.getEncoder().encodeToString(transcoder.imageBytes)));
    }

    @Test
    void existingImageEstimateIsNotChargedAgainDuringPreparation() throws Exception {
        modelRegistry = new ModelRegistry(new LlmProviderRegistry(List.of(), new org.springframework.mock.env.MockEnvironment()));
        tokenCounter = new TokenCounter(modelRegistry, null, featureFlagService);
        tokenBudgetGuard = new TokenBudgetGuard();
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(5000, 5000,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", bytes);
        String encoded = java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
        var image = new ContentBlock.ImageBlock("image/png", encoded, 5000, 5000, null);
        var user = new Message.UserMessage("current", Instant.now(), List.of(image, image), null, null);
        var state = new QueryLoopState(List.of(user), ToolUseContext.of("/tmp", "test-session"));
        var scripted = scriptProvider((call, callback) -> finish(callback, "end_turn", new LlmStreamEvent.TextDelta("ok")));
        var config = QueryConfig.withDefaults("kimi-k3", "test", List.of(), List.of(),
                16384, 256000, new ThinkingConfig.Disabled(), 1, "test");
        var handler = new TestHandler();
        var result = newEngine(new MessageNormalizer(), new UserImageTranscoder(UserImageTranscoderTest.properties()))
                .execute(config, state, handler);
        assertThat(result.isSuccess()).isTrue();
        assertThat(handler.errors).isEmpty();
        var request = scripted.requests().getFirst();
        assertThat(imageParts(request)).hasSize(2);
        assertThat(new FinalProviderPayloadGuard(modelRegistry).validate("moonshot", "kimi-k3",
                Map.of("messages", request), 16384).guarded()).isTrue();
        assertThat(((Message.UserMessage) state.getMessages().getFirst()).content()).containsExactly(image, image);
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "unchanged", "skipped"})
    void imageBudgetCompactsHistoryOnceWithoutSpendingTheOnlyModelTurn(String outcome) throws Exception {
        boolean freesSpace = "success".equals(outcome);
        when(modelRegistry.getCapabilities("kimi-k3")).thenReturn(base64OnlyCapabilities("kimi-k3"));
        tokenCounter = new TokenCounter(modelRegistry, null, featureFlagService);
        tokenBudgetGuard = new TokenBudgetGuard();
        var history = new Message.UserMessage("old", Instant.now(),
                List.of(new ContentBlock.TextBlock("history ".repeat(79250))), null, null);
        var current = new Message.UserMessage("current", Instant.now(), List.of(new ContentBlock.ImageBlock(
                "image/png", java.util.Base64.getEncoder().encodeToString(UserImageTranscoderTest.png(16)))), null, null, Map.of("steering", true));
        var summary = new Message.UserMessage("summary", Instant.now(), List.of(new ContentBlock.TextBlock("summary")), null, null);
        when(compactService.reactiveCompact(eq(List.of(history)), any(CompactionContext.class), eq(false)))
                .thenReturn(new CompactService.CompactResult(freesSpace ? List.of(summary) : List.of(history),
                        181000, freesSpace ? 10 : 181000, 1, freesSpace ? 0.001 : 1,
                        "skipped".equals(outcome) ? "NOT_COMPACTABLE" : null, 0));
        ScriptedProvider scripted = freesSpace ? scriptProvider((call, callback) -> finish(callback, "end_turn", new LlmStreamEvent.TextDelta("ok"))) : null;
        if (!freesSpace) when(providerRegistry.getProvider(anyString())).thenReturn(mock(LlmProvider.class));
        var state = new QueryLoopState(List.of(history, current), ToolUseContext.of("/tmp", "test-session"));
        var handler = new TestHandler();
        var config = QueryConfig.withDefaults("kimi-k3", "test", List.of(), List.of(), 8192, 200000,
                new ThinkingConfig.Disabled(), 1, "test");
        var result = newEngine(new MessageNormalizer(), new UserImageTranscoder(UserImageTranscoderTest.properties()))
                .execute(config, state, handler);
        verify(compactService, times(1)).reactiveCompact(eq(List.of(history)), any(CompactionContext.class), eq(false));
        assertThat(state.getTurnCount()).isEqualTo(1);
        assertThat(state.getMessages()).contains(current);
        assertThat(result.isSuccess()).isEqualTo(freesSpace);
        if (freesSpace) {
            assertThat(handler.errors).isEmpty();
            assertThat(scripted.requests()).hasSize(1);
            assertThat(imageParts(scripted.requests().getFirst())).hasSize(1);
        } else {
            assertThat(result.stopReason()).isEqualTo("error");
            assertThat(handler.errors).hasSize(1);
            assertThat(((LlmApiException) handler.errors.getFirst()).getErrorType()).isEqualTo("IMAGE_CONTEXT_BUDGET_EXCEEDED");
        }
    }

    @Test
    void mediaStripPreservesUserMetadata() throws Exception {
        var original = new Message.UserMessage("u", Instant.now(), List.of(
                new ContentBlock.ImageBlock("image/png", "data", 1, 1, null)), null, "assistant", Map.of("steering", true));
        var state = new QueryLoopState(List.of(original), ToolUseContext.of("/tmp", "test-session"));
        var method = QueryEngine.class.getDeclaredMethod("tryStripMediaBlocks", QueryLoopState.class, QueryMessageHandler.class);
        method.setAccessible(true);
        assertThat(method.invoke(createMinimalQueryEngine(), state, new TestHandler())).isEqualTo(true);
        assertThat(((Message.UserMessage) state.getMessages().getFirst()).meta()).isEqualTo(original.meta());
    }

    // ═══════════════ 行为测试辅助 ═══════════════

    /** 构造 QueryEngine（使用真实 MessageNormalizer 以便捕获真实 API 请求，转码器可替换）。 */
    private QueryEngine newEngine(MessageNormalizer normalizer, UserImageTranscoder transcoder) {
        return new QueryEngine(
                providerRegistry, compactService, apiRetryService, tokenCounter,
                objectMapper, streamingToolExecutor, normalizer, hookService,
                snipService, microCompactService, modelRegistry,
                thinkingBudgetCalculator, modelTierService, fileHistoryService,
                toolResultSummarizer, contextCascade, compactMetrics,
                null, null,  // incrementalCollapseManager, visualizationAutoRouter (both @Nullable)
                null, featureFlagService,  // backgroundAgentTracker (@Nullable), featureFlagService
                new DefaultTerminationStrategy(), new ToolPriorityScheduler(), null,
                new AgentTimeoutConfig(), tokenBudgetGuard, imageRefInjector,
                runTracker, null, transcoder);  // runExecutions 由集成测试覆盖
    }

    private record ScriptedProvider(LlmProvider provider, List<List<Map<String, Object>>> requests) {}

    /**
     * 配置 provider mock：apiRetryService 直接执行 supplier（异常自然传播到引擎 catch 分支），
     * provider.streamChat 记录每次请求消息列表后按脚本响应/抛错。
     */
    private ScriptedProvider scriptProvider(BiConsumer<Integer, StreamChatCallback> script) {
        LlmProvider provider = mock(LlmProvider.class);
        when(providerRegistry.getProvider(anyString())).thenReturn(provider);

        StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
        when(streamingToolExecutor.newSession(any())).thenReturn(session);

        when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(0, Supplier.class).get());

        lenient().when(hookService.executeStopHooks(anyList(), anyString()))
                .thenReturn(HookRegistry.StopHookResult.ok());

        List<List<Map<String, Object>>> requests = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) inv.getArgument(1);
            requests.add(List.copyOf(messages));
            script.accept(requests.size(), inv.getArgument(7));
            return null;
        }).when(provider).streamChat(
                anyString(), anyList(), anyString(), anyList(),
                anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));
        return new ScriptedProvider(provider, requests);
    }

    private static void finish(StreamChatCallback callback, String stopReason, LlmStreamEvent... events) {
        for (LlmStreamEvent event : events) callback.onEvent(event);
        callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(10, 5, 0, 0), stopReason));
        callback.onComplete();
    }

    private QueryConfig buildConfig(String model) {
        return QueryConfig.withDefaults(
                model, "You are a helpful assistant.",
                List.of(), List.of(),
                8192, 200000,
                new ThinkingConfig.Disabled(), 10, "test"
        );
    }

    /** 历史中持久化一条含受信任 OSS URL ImageBlock 的用户消息。 */
    private QueryLoopState buildStateWithUrlImage() {
        List<ContentBlock> content = new ArrayList<>();
        content.add(new ContentBlock.TextBlock("look at this"));
        content.add(new ContentBlock.ImageBlock("image/png", null, 0, 0, TRUSTED_URL));
        Message.UserMessage userMessage = new Message.UserMessage(
                UUID.randomUUID().toString(), Instant.now(), content, null, null);
        return new QueryLoopState(List.of(userMessage), ToolUseContext.of("/tmp", "test-session"));
    }

    /** 从一次捕获的 API 请求中提取所有 image 内容块（map 形态）。 */
    private static List<Map<String, Object>> imageParts(List<Map<String, Object>> request) {
        List<Map<String, Object>> images = new ArrayList<>();
        for (Map<String, Object> message : request) {
            if (message.get("content") instanceof List<?> parts) {
                for (Object part : parts) {
                    if (part instanceof Map<?, ?> map && "image".equals(map.get("type"))) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> imagePart = (Map<String, Object>) map;
                        images.add(imagePart);
                    }
                }
            }
        }
        return images;
    }

    private static boolean hasImageBlock(Message message) {
        return message instanceof Message.UserMessage user
                && user.content() != null
                && user.content().stream().anyMatch(ContentBlock.ImageBlock.class::isInstance);
    }

    static class TestHandler implements QueryMessageHandler {
        final List<Message.SystemMessage> systemMessages = new CopyOnWriteArrayList<>();
        @Override public void onSystemMessage(Message.SystemMessage message) { systemMessages.add(message); }
        final List<String> textDeltas = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final List<String> compactEvents = new CopyOnWriteArrayList<>();
        final List<RecoveryEvent> recoveryEvents = new CopyOnWriteArrayList<>();
        @Override public void onTextDelta(String text) { textDeltas.add(text); }
        @Override public void onToolUseStart(String id, String name) {}
        @Override public void onToolUseComplete(String id, ContentBlock.ToolUseBlock toolUse) {}
        @Override public void onToolResult(String id, ContentBlock.ToolResultBlock result) {}
        @Override public void onAssistantMessage(Message.AssistantMessage message) {}
        @Override public void onError(Throwable error) { errors.add(error); }
        @Override public void onCompactEvent(String type, int beforeTokens, int afterTokens) { compactEvents.add(type); }
        @Override public void onRecovery(RecoveryEvent event) { recoveryEvents.add(event); }
    }
}
