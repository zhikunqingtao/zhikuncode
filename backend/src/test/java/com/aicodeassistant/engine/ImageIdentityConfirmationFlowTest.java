package com.aicodeassistant.engine;

import com.aicodeassistant.config.AgentTimeoutConfig;
import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.engine.scheduling.ToolPriorityScheduler;
import com.aicodeassistant.engine.strategy.DefaultTerminationStrategy;
import com.aicodeassistant.history.FileHistoryService;
import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.llm.*;
import com.aicodeassistant.model.*;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.impl.ImageResultExternalizer.ImageToolRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * R-02 验收 — QueryEngine 端到端确认语义：
 * 请求失败不确认且下一轮可重新注入；成功送达后确认并下一轮去重；
 * BASE64_ONLY 二次转码不改变注入图字节（身份映射口径一致）；
 * 被二次转码省略的注入图不确认。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("R-02 图片确认身份 — QueryEngine 端到端")
class ImageIdentityConfirmationFlowTest {

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

    @TempDir Path directory;

    private ObjectMapper objectMapper;
    private TokenBudgetGuard tokenBudgetGuard;
    private ImageRefInjector imageRefInjector;
    private UserImageTranscoder userImageTranscoder;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tokenBudgetGuard = new TokenBudgetGuard();
        userImageTranscoder = new UserImageTranscoder(UserImageTranscoderTest.properties());

        lenient().when(snipService.snipToolResults(anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(microCompactService.compactMessages(anyList(), anyInt()))
                .thenReturn(new MicroCompactService.MicroCompactResult(List.of(), 0));
        lenient().when(modelTierService.resolveModel(anyString(), anyList())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(modelRegistry.getContextWindowForModel(anyString())).thenReturn(200000);
        lenient().when(modelRegistry.getTokenCharRatio(anyString())).thenReturn(3.5);
        lenient().when(modelRegistry.getCapabilities(anyString())).thenReturn(urlModeCapabilities());
        lenient().when(apiRetryService.executeWithRetry(any(), anyString(), anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(0, Supplier.class).get());
        lenient().when(toolResultSummarizer.processToolResults(anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(hookService.executeStopHooks(anyList(), anyString())).thenReturn(HookRegistry.StopHookResult.ok());
        lenient().when(contextCascade.executePreApiCascade(anyList(), anyString(), any(), any()))
                .thenAnswer(inv -> {
                    List<Message> msgs = inv.getArgument(0);
                    int tokens = msgs.size() * 100;
                    return new ContextCascade.CascadeResult(
                            msgs, tokens, tokens, false, 0, false, 0, false, 0, false, false, null);
                });
        // 默认空执行会话（无工具完成）；工具循环测试用 toolLoopSession() 覆盖。
        StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
        lenient().when(session.isAllCompleted()).thenReturn(true);
        lenient().when(session.yieldCompleted()).thenReturn(List.of());
        lenient().when(streamingToolExecutor.newSession(any())).thenReturn(session);
    }

    // ═══════════════ 请求失败 → 不确认；下一轮重新注入 ═══════════════

    @Test
    @DisplayName("请求失败（未送达）不确认，重试轮重新注入")
    void failedDeliveryDoesNotConfirmAndRetryReinjects() throws Exception {
        Path file = writePng("tool.png", UserImageTranscoderTest.png(32));
        List<Set<String>> confirmedSnapshots = installInjector();
        ScriptedProvider scripted = scriptProvider((call, callback) -> {
            if (call == 1) throw new LlmApiException("upstream overloaded", true, 529);
            finish(callback, "end_turn", new LlmStreamEvent.TextDelta("ok"));
        });

        QueryEngine engine = newEngine(new MessageNormalizer(), userImageTranscoder);
        QueryConfig config = new QueryConfig("mock-model", "fallback-model", "You are a helpful assistant.",
                List.of(), List.of(), 8192, 200000, new ThinkingConfig.Disabled(), 10, "test", null, List.of());
        QueryEngine.QueryResult result = engine.execute(config, toolImageState(file), new TestHandler());

        assertThat(result.isSuccess()).isTrue();
        assertThat(scripted.requests()).hasSize(2);
        // 第 1 轮请求失败（529 → fallback 重试）：未确认，第 2 轮必须重新注入同一张图片。
        assertThat(imageParts(scripted.requests().get(0))).hasSize(1);
        assertThat(imageParts(scripted.requests().get(1))).hasSize(1);
        assertThat(decodedImageBytes(scripted.requests().get(1)).getFirst())
                .isEqualTo(Files.readAllBytes(file));
        assertThat(confirmedSnapshots).hasSize(2);
        assertThat(confirmedSnapshots.get(0)).isEmpty();
        assertThat(confirmedSnapshots.get(1)).isEmpty();
    }

    // ═══════════════ 成功确认 → 下一轮去重 ═══════════════

    @Test
    @DisplayName("请求成功送达确认图片身份，下一轮不再重复注入")
    void successfulDeliveryConfirmsAndNextTurnSkipsInjection() throws Exception {
        Path file = writePng("tool.png", UserImageTranscoderTest.png(32));
        String sourceHash = sha256(Files.readAllBytes(file));
        List<Set<String>> confirmedSnapshots = installInjector();
        ScriptedProvider scripted = scriptProvider(toolThenTextScript());
        toolLoopSession();

        QueryEngine engine = newEngine(new MessageNormalizer(), userImageTranscoder);
        QueryEngine.QueryResult result = engine.execute(toolConfig("mock-model"), toolImageState(file), new TestHandler());

        assertThat(result.isSuccess()).isTrue();
        assertThat(scripted.requests()).hasSize(2);
        assertThat(imageParts(scripted.requests().get(0))).hasSize(1);
        assertThat(decodedImageBytes(scripted.requests().get(0)).getFirst())
                .isEqualTo(Files.readAllBytes(file));
        // 第 1 轮成功送达 → 确认；第 2 轮引用仍在历史中，但不得重复注入。
        assertThat(confirmedSnapshots).hasSize(2);
        assertThat(confirmedSnapshots.get(0)).isEmpty();
        assertThat(confirmedSnapshots.get(1)).containsExactly(sourceHash);
        assertThat(imageParts(scripted.requests().get(1))).isEmpty();
    }

    @Test
    @DisplayName("BASE64_ONLY：二次转码保持注入图字节不变，确认后下一轮去重")
    void base64OnlySecondTranscodePreservesBytesAndConfirms() throws Exception {
        Path file = writePng("tool.png", UserImageTranscoderTest.png(32));
        String sourceHash = sha256(Files.readAllBytes(file));
        when(modelRegistry.getCapabilities("kimi-k3")).thenReturn(base64OnlyCapabilities("kimi-k3", 5));
        List<Set<String>> confirmedSnapshots = installInjector();
        ScriptedProvider scripted = scriptProvider(toolThenTextScript());
        toolLoopSession();

        QueryEngine engine = newEngine(new MessageNormalizer(), userImageTranscoder);
        QueryEngine.QueryResult result = engine.execute(toolConfig("kimi-k3"), toolImageState(file), new TestHandler());

        assertThat(result.isSuccess()).isTrue();
        assertThat(scripted.requests()).hasSize(2);
        // 注入图经过规范化/二次转码后字节必须完全不变（否则身份映射失效）。
        assertThat(decodedImageBytes(scripted.requests().get(0)).getFirst())
                .isEqualTo(Files.readAllBytes(file));
        assertThat(confirmedSnapshots.get(1)).containsExactly(sourceHash);
        assertThat(imageParts(scripted.requests().get(1))).isEmpty();
    }

    @Test
    @DisplayName("多源同载荷（两个不同 BMP → 同一 PNG 载荷）：第 1 轮注入 2 张，第 2 轮零重复注入")
    void multipleSourcesWithOnePayloadAreAllConfirmedAcrossTurns() throws Exception {
        Path first = writeGradientBmp("tool-a.bmp", 60, 60);
        Path second = writeHeaderPatchedBmp("tool-b.bmp", first);
        byte[] payload = pngPayloadOf(first);
        String firstHash = sha256(Files.readAllBytes(first));
        String secondHash = sha256(Files.readAllBytes(second));
        // 场景自检：两个不同源文件（不同哈希）解码/转码出同一 PNG 载荷。
        assertThat(secondHash).isNotEqualTo(firstHash);
        assertThat(pngPayloadOf(second)).isEqualTo(payload);

        when(modelRegistry.getCapabilities("kimi-k3")).thenReturn(base64OnlyCapabilities("kimi-k3", 5));
        List<Set<String>> confirmedSnapshots = installInjector();
        ScriptedProvider scripted = scriptProvider(toolThenTextScript());
        toolLoopSession();

        QueryEngine engine = newEngine(new MessageNormalizer(), userImageTranscoder);
        QueryEngine.QueryResult result = engine.execute(toolConfig("kimi-k3"),
                toolImageWithTwoSourcesState(first, second), new TestHandler());

        assertThat(result.isSuccess()).isTrue();
        assertThat(scripted.requests()).hasSize(2);
        // 第 1 轮：两个同载荷工具图都必须注入（BMP 转码后同字节）。
        assertThat(imageParts(scripted.requests().get(0))).hasSize(2);
        assertThat(decodedImageBytes(scripted.requests().get(0)))
                .allSatisfy(bytes -> assertThat(bytes).isEqualTo(payload));
        // 第 2 轮：两个源身份都已确认（不再单值覆盖丢源）→ 零重复注入。
        assertThat(confirmedSnapshots.get(1)).contains(firstHash, secondHash);
        assertThat(imageParts(scripted.requests().get(1))).isEmpty();
    }

    // ═══════════════ 被二次转码省略 → 不确认 ═══════════════

    @Test
    @DisplayName("BASE64_ONLY：注入图被二次转码按数量上限省略 → 不确认，后续轮仍尝试重新注入")
    void imageOmittedBySecondTranscodeIsNotConfirmed() throws Exception {
        byte[] toolBytes = UserImageTranscoderTest.png(32);
        byte[] downloadedBytes = UserImageTranscoderTest.png(16);
        Path file = writePng("tool.png", toolBytes);
        String sourceHash = sha256(toolBytes);
        when(modelRegistry.getCapabilities("kimi-k3")).thenReturn(base64OnlyCapabilities("kimi-k3", 8));
        userImageTranscoder = new UserImageTranscoder(UserImageTranscoderTest.properties()) {
            @Override protected DownloadedImage fetchImage(String url, CancellationSignal cancellation) {
                return new DownloadedImage(downloadedBytes, "image/png");
            }
        };
        List<Set<String>> confirmedSnapshots = installInjector();
        ScriptedProvider scripted = scriptProvider(toolThenTextScript());
        toolLoopSession();

        QueryEngine engine = newEngine(new MessageNormalizer(), userImageTranscoder);
        TestHandler handler = new TestHandler();
        QueryEngine.QueryResult result = engine.execute(toolConfig("kimi-k3"),
                toolImageWithEightNewerUrlImagesState(file), handler);

        assertThat(result.isSuccess()).isTrue();
        assertThat(scripted.requests()).hasSize(2);
        // 8 张更新的 URL 图占满上限，更旧的注入工具图两轮都被二次转码省略。
        assertThat(imageParts(scripted.requests().get(0))).hasSize(8);
        assertThat(imageParts(scripted.requests().get(1))).hasSize(8);
        assertThat(decodedImageBytes(scripted.requests().get(0)))
                .noneSatisfy(bytes -> assertThat(bytes).isEqualTo(toolBytes))
                .anySatisfy(bytes -> assertThat(bytes).isEqualTo(downloadedBytes));
        // 从未在最终 payload 中保留 → 从未确认（两轮快照都不含源身份）。
        assertThat(confirmedSnapshots).hasSize(2);
        assertThat(confirmedSnapshots.get(0)).doesNotContain(sourceHash);
        assertThat(confirmedSnapshots.get(1)).doesNotContain(sourceHash);
        assertThat(handler.systemMessages).anySatisfy(notice ->
                assertThat(notice.content()).contains("本轮已省略"));
    }

    // ═══════════════ 测试设施 ═══════════════

    /** 在真实 ImageRefInjector 上记录每轮传入的 confirmedHashes 快照（调用时拷贝）。 */
    private List<Set<String>> installInjector() {
        imageRefInjector = spy(new ImageRefInjector(objectMapper, allowingSecurity()));
        List<Set<String>> snapshots = new ArrayList<>();
        doAnswer(inv -> {
            snapshots.add(new HashSet<>(inv.getArgument(3)));
            return inv.callRealMethod();
        }).when(imageRefInjector).injectForApiCall(anyList(), anyInt(), anyInt(), anySet(), anyMap(),
                nullable(String.class), anyInt());
        return snapshots;
    }

    private static PathSecurityService allowingSecurity() {
        PathSecurityService security = mock(PathSecurityService.class);
        lenient().when(security.checkReadPermission(anyString(), anyString()))
                .thenReturn(PathSecurityService.PathCheckResult.allowed());
        return security;
    }

    private record ScriptedProvider(LlmProvider provider, List<List<Map<String, Object>>> requests) {}

    private ScriptedProvider scriptProvider(BiConsumer<Integer, StreamChatCallback> script) {
        LlmProvider provider = mock(LlmProvider.class);
        when(providerRegistry.getProvider(anyString())).thenReturn(provider);
        List<List<Map<String, Object>>> requests = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) inv.getArgument(1);
            requests.add(List.copyOf(messages));
            script.accept(requests.size(), inv.getArgument(7));
            return null;
        }).when(provider).streamChat(anyString(), anyList(), anyString(), anyList(),
                anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));
        return new ScriptedProvider(provider, requests);
    }

    /** 第 1 轮返回 tool_use，第 2 轮返回文本：驱动两轮循环。 */
    private static BiConsumer<Integer, StreamChatCallback> toolThenTextScript() {
        return (call, callback) -> {
            if (call == 1) {
                callback.onEvent(new LlmStreamEvent.ToolUseStart("call-1", "EchoTool"));
                callback.onEvent(new LlmStreamEvent.ToolInputDelta("call-1", "{}"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(10, 5, 0, 0), "tool_use"));
            } else {
                callback.onEvent(new LlmStreamEvent.TextDelta("done"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(10, 5, 0, 0), "end_turn"));
            }
            callback.onComplete();
        };
    }

    private void toolLoopSession() {
        var completed = mock(StreamingToolExecutor.TrackedTool.class);
        when(completed.getToolUseId()).thenReturn("call-1");
        when(completed.getResult()).thenReturn(ToolResult.success("Echo: ok"));
        StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
        lenient().when(session.isAllCompleted()).thenReturn(true);
        lenient().when(session.yieldCompleted()).thenReturn(List.of(completed), List.of());
        when(streamingToolExecutor.newSession(any())).thenReturn(session);
    }

    private static void finish(StreamChatCallback callback, String stopReason, LlmStreamEvent... events) {
        for (LlmStreamEvent event : events) callback.onEvent(event);
        callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(10, 5, 0, 0), stopReason));
        callback.onComplete();
    }

    private QueryEngine newEngine(MessageNormalizer normalizer, UserImageTranscoder transcoder) {
        return new QueryEngine(
                providerRegistry, compactService, apiRetryService, tokenCounter,
                objectMapper, streamingToolExecutor, normalizer, hookService,
                snipService, microCompactService, modelRegistry,
                thinkingBudgetCalculator, modelTierService, fileHistoryService,
                toolResultSummarizer, contextCascade, compactMetrics,
                null, null,
                null, featureFlagService,
                new DefaultTerminationStrategy(), new ToolPriorityScheduler(), null,
                new AgentTimeoutConfig(), tokenBudgetGuard, imageRefInjector,
                null, null, transcoder);
    }

    private QueryConfig toolConfig(String model) {
        Tool echo = mock(Tool.class);
        lenient().when(echo.getName()).thenReturn("EchoTool");
        return QueryConfig.withDefaults(model, "You are a helpful assistant.",
                List.of(echo), List.of(), 8192, 200000, new ThinkingConfig.Disabled(), 10, "test");
    }

    private static ModelCapabilities urlModeCapabilities() {
        return new ModelCapabilities("mock-model", "Mock Model", 8192, 200000,
                true, false, true, 5, true, 0.0, 0.0);
    }

    private static ModelCapabilities base64OnlyCapabilities(String modelId, int maxImages) {
        return new ModelCapabilities(modelId, modelId, 65536, 1000000,
                true, false, true, maxImages, true, 0.0, 0.0, 3.5, false,
                ModelCapabilities.ImageInputMode.BASE64_ONLY);
    }

    private QueryLoopState toolImageState(Path file) throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message.UserMessage("user-1", Instant.now(),
                List.of(new ContentBlock.TextBlock("read the image")), null, null));
        messages.add(new Message.AssistantMessage("assistant-1", Instant.now(),
                List.of(new ContentBlock.ToolUseBlock("read-1", "Read",
                        objectMapper.valueToTree(Map.of("file_path", file.toString())))), "tool_use", null));
        messages.add(new Message.UserMessage("tool-result-1", Instant.now(),
                List.of(new ContentBlock.ToolResultBlock("read-1", refText(file), false)),
                null, "assistant-1"));
        return new QueryLoopState(messages, ToolUseContext.of(directory.toString(), "test-session"));
    }

    private QueryLoopState toolImageWithEightNewerUrlImagesState(Path file) throws Exception {
        List<Message> messages = new ArrayList<>(toolImageState(file).getMessages());
        for (int i = 1; i <= 8; i++) {
            messages.add(UserImageTranscoderTest.message("url-" + i,
                    UserImageTranscoderTest.URL + i + ".png"));
        }
        return new QueryLoopState(messages, ToolUseContext.of(directory.toString(), "test-session"));
    }

    private String refText(Path file) throws Exception {
        return refText(file, "image/png");
    }

    private String refText(Path file, String mimeType) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(file.toFile());
        ImageToolRef ref = new ImageToolRef(file.toString(), mimeType, bytes.length, sha256(bytes),
                image.getWidth(), image.getHeight());
        return "[image_ref]" + objectMapper.writeValueAsString(ref) + "[/image_ref]";
    }

    /** 两个不同源文件（BMP 头部字节不同、解码像素相同 → 转码载荷相同）的工具引用消息。 */
    private QueryLoopState toolImageWithTwoSourcesState(Path first, Path second) throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message.UserMessage("user-1", Instant.now(),
                List.of(new ContentBlock.TextBlock("read both images")), null, null));
        messages.add(new Message.AssistantMessage("assistant-1", Instant.now(),
                List.of(new ContentBlock.ToolUseBlock("read-1", "Read",
                        objectMapper.valueToTree(Map.of("file_path", first.toString())))), "tool_use", null));
        messages.add(new Message.UserMessage("tool-result-1", Instant.now(),
                List.of(new ContentBlock.ToolResultBlock("read-1", refText(first, "image/bmp"), false)),
                null, "assistant-1"));
        messages.add(new Message.AssistantMessage("assistant-2", Instant.now(),
                List.of(new ContentBlock.ToolUseBlock("read-2", "Read",
                        objectMapper.valueToTree(Map.of("file_path", second.toString())))), "tool_use", null));
        messages.add(new Message.UserMessage("tool-result-2", Instant.now(),
                List.of(new ContentBlock.ToolResultBlock("read-2", refText(second, "image/bmp"), false)),
                null, "assistant-2"));
        return new QueryLoopState(messages, ToolUseContext.of(directory.toString(), "test-session"));
    }

    private Path writeGradientBmp(String name, int width, int height) throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, (x * 255 / width) << 16 | (y * 255 / height) << 8 | 128);
            }
        }
        Path file = directory.resolve(name);
        javax.imageio.ImageIO.write(image, "bmp", file.toFile());
        return file;
    }

    /** 仅修改 BMP 头部 xPelsPerMeter（解码器不使用的字段）：字节不同、解码图像相同。 */
    private Path writeHeaderPatchedBmp(String name, Path source) throws Exception {
        byte[] patched = Files.readAllBytes(source).clone();
        patched[38] = 0x10;
        patched[39] = 0x27;
        Path file = directory.resolve(name);
        Files.write(file, patched);
        return file;
    }

    private static byte[] pngPayloadOf(Path file) throws Exception {
        return pngBytes(javax.imageio.ImageIO.read(file.toFile()));
    }

    private static byte[] pngBytes(java.awt.image.BufferedImage image) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private Path writePng(String name, byte[] bytes) throws Exception {
        Path file = directory.resolve(name);
        Files.write(file, bytes);
        return file;
    }

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

    private static List<byte[]> decodedImageBytes(List<Map<String, Object>> request) {
        List<byte[]> decoded = new ArrayList<>();
        for (Map<String, Object> image : imageParts(request)) {
            Object source = image.get("source");
            if (source instanceof Map<?, ?> sourceMap && sourceMap.get("data") instanceof String data) {
                decoded.add(Base64.getDecoder().decode(data));
            }
        }
        return decoded;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static class TestHandler implements QueryMessageHandler {
        final List<Message.SystemMessage> systemMessages = new CopyOnWriteArrayList<>();
        final List<String> textDeltas = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final List<String> compactEvents = new CopyOnWriteArrayList<>();
        final List<RecoveryEvent> recoveryEvents = new CopyOnWriteArrayList<>();

        @Override public void onSystemMessage(Message.SystemMessage message) { systemMessages.add(message); }
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