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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A2 回归网 — R-02 mandatory 相撞保护必须建立在「注入载荷哈希」命名空间上。
 * <p>
 * 缺陷（本批角落回归）：可降级集合曾用「源哈希集合」减「mandatory 附件载荷哈希集合」，
 * 对 BMP→PNG 等源≠载荷形态排除失效；若工具图转码载荷恰与 mandatory 附件逐字节相同，
 * Phase2 会把 mandatory 一并静默降级（策略 1 缩略或策略 2 文字替代）。
 * 修复后按注入记录在载荷侧求交，mandatory 永不进入可降级集合。
 * <p>
 * 全部用例走真实 {@link QueryEngine#execute} 路径（真实注入器/转码器/预算守卫 + 捕获 wire 请求的假
 * provider），避免手工拼装 guard 输入绕过排除语句。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("A2 — mandatory 相撞保护（BMP 跨格式 / 策略1 / URL / F-41 交互）")
class MandatoryCollisionProtectionTest {

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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TokenBudgetGuard tokenBudgetGuard = new TokenBudgetGuard();
    private UserImageTranscoder userImageTranscoder;
    private ImageRefInjector imageRefInjector;
    private List<Set<String>> confirmedSnapshots;

    private static final String SYSTEM_PROMPT = "You are a helpful assistant.";
    private static final int WINDOW = 200_000;
    /** inputBudget = 200000 - 170000 - 8 - 10000 = 19992。 */
    private static final int DEFAULT_MAX_TOKENS = 170_000;
    /** 60px 场景超预算填充（≈18202 tokens），与此前 /tmp 复现一致。 */
    private static final int OVER_BUDGET_FILLER = 3353;
    /** 800px 场景填充：使「工具图准入所需的放大预算」与「超限」同时成立。 */
    private static final int STRATEGY_ONE_FILLER = 20_000;

    @BeforeEach
    void setUp() {
        userImageTranscoder = new UserImageTranscoder(UserImageTranscoderTest.properties());
        imageRefInjector = null;
        confirmedSnapshots = null;
        lenient().when(snipService.snipToolResults(anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(microCompactService.compactMessages(anyList(), anyInt()))
                .thenReturn(new MicroCompactService.MicroCompactResult(List.of(), 0));
        lenient().when(modelTierService.resolveModel(anyString(), anyList())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(modelRegistry.getContextWindowForModel(anyString())).thenReturn(WINDOW);
        lenient().when(modelRegistry.getTokenCharRatio(anyString())).thenReturn(3.5);
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
        StreamingToolExecutor.ExecutionSession session = mock(StreamingToolExecutor.ExecutionSession.class);
        lenient().when(session.isAllCompleted()).thenReturn(true);
        lenient().when(session.yieldCompleted()).thenReturn(List.of());
        lenient().when(streamingToolExecutor.newSession(any())).thenReturn(session);
    }

    // ═══════════════ 1. BMP 相撞 × 超预算 → 响亮失败 ═══════════════

    @Test
    @DisplayName("1. BMP 跨格式相撞 × 超预算 → 响亮失败，mandatory 不得被静默降级")
    void bmpCollisionOverBudgetFailsLoudly() throws Exception {
        Path bmp = writeGradientBmp("tool.bmp", 60, 60);
        byte[] payload = pngPayloadOf(bmp);
        when(modelRegistry.getCapabilities("kimi-k3")).thenReturn(base64OnlyCapabilities("kimi-k3", 5));
        ScriptedProvider scripted = scriptProvider(textScript());
        QueryEngine engine = newEngine();
        TestHandler handler = new TestHandler();

        QueryEngine.QueryResult result = engine.execute(configFor("kimi-k3", DEFAULT_MAX_TOKENS),
                collisionState(bmp, payload, "image/bmp", OVER_BUDGET_FILLER), handler);

        // 修复前：mandatory 被策略2文字替代且请求照发（静默降级）；修复后：相撞源已不可降级，
        // 预算无法满足 → 无请求发出、错误上抛。
        assertThat(scripted.requests()).isEmpty();
        assertThat(result.isSuccess()).isFalse();
        assertThat(handler.errors).anySatisfy(error ->
                assertThat(error.getMessage()).contains("Local context budget exceeded"));
    }

    // ═══════════════ 2. BMP 相撞 × 预算内 → 两张图原样保留且可确认 ═══════════════

    @Test
    @DisplayName("2. BMP 跨格式相撞 × 预算内 → 两张图原样保留，工具源可确认且下轮去重")
    void bmpCollisionWithinBudgetKeepsBothImagesAndConfirmsToolSource() throws Exception {
        Path bmp = writeGradientBmp("tool.bmp", 60, 60);
        byte[] payload = pngPayloadOf(bmp);
        String bmpSourceHash = sha256(Files.readAllBytes(bmp));
        installSpyInjector();
        when(modelRegistry.getCapabilities("kimi-k3")).thenReturn(base64OnlyCapabilities("kimi-k3", 5));
        ScriptedProvider scripted = scriptProvider(toolThenTextScript());
        toolLoopSession();
        QueryEngine engine = newEngine();
        TestHandler handler = new TestHandler();

        QueryEngine.QueryResult result = engine.execute(configFor("kimi-k3", DEFAULT_MAX_TOKENS),
                lightCollisionState(bmp, payload), handler);

        assertThat(result.isSuccess()).isTrue();
        assertThat(scripted.requests()).hasSize(2);
        // 第 1 轮：mandatory 附件与转码后工具图（同字节 P）都必须原样保留。
        assertThat(decodedImageBytes(scripted.requests().get(0))).hasSize(2)
                .allSatisfy(bytes -> assertThat(bytes).isEqualTo(payload));
        // 请求成功送达后按最终保留字节确认工具图源身份；第 2 轮 confirmed 快照必须含该源。
        assertThat(confirmedSnapshots.get(1)).contains(bmpSourceHash);
        // 确认生效 → 第 2 轮不再重复注入工具图，仅剩 mandatory 附件一张。
        assertThat(decodedImageBytes(scripted.requests().get(1))).hasSize(1)
                .allSatisfy(bytes -> assertThat(bytes).isEqualTo(payload));
    }

    // ═══════════════ 3. BMP 不相撞对照 → 仅工具图仍可降级 ═══════════════

    @Test
    @DisplayName("3. BMP 不相撞对照 × 超预算 → 仅工具图被降级，mandatory 保留")
    void bmpToolImageWithoutCollisionStillDegradesOnlyToolImage() throws Exception {
        Path bmp = writeGradientBmp("tool.bmp", 60, 60);
        byte[] mandatoryBytes = pngBytes(differentImage(60, 60));
        when(modelRegistry.getCapabilities("kimi-k3")).thenReturn(base64OnlyCapabilities("kimi-k3", 5));
        ScriptedProvider scripted = scriptProvider(textScript());
        QueryEngine engine = newEngine();
        TestHandler handler = new TestHandler();

        QueryEngine.QueryResult result = engine.execute(configFor("kimi-k3", DEFAULT_MAX_TOKENS),
                collisionState(bmp, mandatoryBytes, "image/bmp", OVER_BUDGET_FILLER), handler);

        assertThat(result.isSuccess()).isTrue();
        assertThat(scripted.requests()).hasSize(1);
        // mandatory 原字节保留，工具图被文字替代（修复不误伤正常降级能力）。
        assertThat(decodedImageBytes(scripted.requests().getFirst())).hasSize(1)
                .allSatisfy(bytes -> assertThat(bytes).isEqualTo(mandatoryBytes));
    }

    // ═══════════════ 4. PNG 直读相撞（源==载荷）→ 既有保护不回归 ═══════════════

    @Test
    @DisplayName("4. PNG 直读相撞（源==载荷）× 超预算 → 响亮失败（既有保护不回归）")
    void directFormatPngCollisionOverBudgetFailsLoudly() throws Exception {
        byte[] collided = pngBytes(differentImage(60, 60));
        Path png = directory.resolve("tool.png");
        Files.write(png, collided);
        when(modelRegistry.getCapabilities("kimi-k3")).thenReturn(base64OnlyCapabilities("kimi-k3", 5));
        ScriptedProvider scripted = scriptProvider(textScript());
        QueryEngine engine = newEngine();
        TestHandler handler = new TestHandler();

        QueryEngine.QueryResult result = engine.execute(configFor("kimi-k3", DEFAULT_MAX_TOKENS),
                collisionState(png, collided, "image/png", OVER_BUDGET_FILLER), handler);

        assertThat(scripted.requests()).isEmpty();
        assertThat(result.isSuccess()).isFalse();
        assertThat(handler.errors).anySatisfy(error ->
                assertThat(error.getMessage()).contains("Local context budget exceeded"));
    }

    // ═══════════════ 5. 多源同载荷（F-41 交互）→ 全部相撞源受保护 ═══════════════

    @Test
    @DisplayName("5. 多源同载荷相撞（F-41 交互）× 超预算 → 两个源都不得被静默降级")
    void multipleSourcesWithSamePayloadAreAllProtected() throws Exception {
        Path first = writeCheckerBmp("tool-a.bmp");
        Path second = writeHeaderPatchedBmp("tool-b.bmp", first);
        byte[] payload = pngPayloadOf(first);
        String firstHash = sha256(Files.readAllBytes(first));
        String secondHash = sha256(Files.readAllBytes(second));
        // 场景自检：两个不同源文件（不同哈希）解码/转码出同一 PNG 载荷。
        assertThat(secondHash).isNotEqualTo(firstHash);
        assertThat(pngPayloadOf(second)).isEqualTo(payload);

        ImageRefInjector probe = new ImageRefInjector(objectMapper, allowingSecurity());
        ImageRefInjector.InjectResult injected = probe.injectForApiCall(
                refMessages(first, second), 0, 10_000_000, Set.of(), new HashMap<>(), directory.toString(), 5);
        assertThat(injected.injectedSourceToPayloadHashes())
                .containsEntry(firstHash, sha256(payload))
                .containsEntry(secondHash, sha256(payload));

        when(modelRegistry.getCapabilities("kimi-k3")).thenReturn(base64OnlyCapabilities("kimi-k3", 5));
        ScriptedProvider scripted = scriptProvider(textScript());
        QueryEngine engine = newEngine();
        TestHandler handler = new TestHandler();

        QueryEngine.QueryResult result = engine.execute(configFor("kimi-k3", DEFAULT_MAX_TOKENS),
                multiSourceState(first, second, payload, OVER_BUDGET_FILLER), handler);

        // 最小断言：两个同载荷源都被移出可降级集合 → 无可降级空间 → 响亮失败（无请求）。
        assertThat(scripted.requests()).isEmpty();
        assertThat(result.isSuccess()).isFalse();
        assertThat(handler.errors).anySatisfy(error ->
                assertThat(error.getMessage()).contains("Local context budget exceeded"));
    }

    // ═══════════════ 6. URL 形态 mandatory 附件 ═══════════════

    @Test
    @DisplayName("6. URL 形态 mandatory 附件相撞 × 超预算 → 同样受保护（响亮失败）")
    void urlMandatoryAttachmentCollisionIsProtected() throws Exception {
        Path bmp = writeGradientBmp("tool.bmp", 60, 60);
        byte[] payload = pngPayloadOf(bmp);
        int[] downloads = {0};
        userImageTranscoder = new UserImageTranscoder(UserImageTranscoderTest.properties()) {
            @Override
            protected DownloadedImage fetchImage(String url, CancellationSignal cancellation) {
                downloads[0]++;
                return new DownloadedImage(payload, "image/png");
            }
        };
        when(modelRegistry.getCapabilities("kimi-k3")).thenReturn(base64OnlyCapabilities("kimi-k3", 5));
        ScriptedProvider scripted = scriptProvider(textScript());
        QueryEngine engine = newEngine();
        TestHandler handler = new TestHandler();

        QueryEngine.QueryResult result = engine.execute(configFor("kimi-k3", DEFAULT_MAX_TOKENS),
                urlMandatoryState(bmp, UserImageTranscoderTest.URL + "attached.png", OVER_BUDGET_FILLER), handler);

        // URL 附件经下载/转码后的载荷与 BMP 工具图转码载荷相撞 → 排除须成立。
        assertThat(downloads[0]).isGreaterThan(0);
        assertThat(scripted.requests()).isEmpty();
        assertThat(result.isSuccess()).isFalse();
        assertThat(handler.errors).anySatisfy(error ->
                assertThat(error.getMessage()).contains("Local context budget exceeded"));
    }

    // ═══════════════ 7. 策略 1（>640px 缩略）路径 ═══════════════

    @Test
    @DisplayName("7. 策略1（>640px 缩略）路径相撞 → mandatory 不被缩略，且该预算下缩略本可救场")
    void strategyOneThumbnailPathProtectsMandatory() throws Exception {
        Path bmp = writeSolidBinaryBmp("tool.bmp", 800);
        byte[] payload = pngPayloadOf(bmp);
        byte[] otherMandatory = pngBytes(checkerBinary(800, 800, 16));
        assertThat(otherMandatory).isNotEqualTo(payload);
        when(modelRegistry.getCapabilities("kimi-k3")).thenReturn(base64OnlyCapabilities("kimi-k3", 5));

        // ── Run A：相撞 + 超预算。预算刻意高于「BMP 文件准入」下限，且低于全量估算。
        int budgetA = 112_000;
        ScriptedProvider scriptedA = scriptProvider(textScript());
        QueryEngine engineA = newEngine();
        TestHandler handlerA = new TestHandler();
        QueryEngine.QueryResult resultA = engineA.execute(configFor("kimi-k3", maxTokensForBudget(budgetA)),
                collisionState(bmp, payload, "image/bmp", STRATEGY_ONE_FILLER), handlerA);

        assertThat(scriptedA.requests()).isEmpty();
        assertThat(resultA.isSuccess()).isFalse();
        int totalTokens = estimatedTokensFrom(handlerA, budgetA);

        // ── Run B（对照）：同几何、mandatory 不同字节（不相撞）→ 策略1只缩略工具图即可容纳，
        //    证明该预算区间策略1确实可以救场；相撞场景（Run A）却不允许触碰 mandatory。
        int budgetB = totalTokens - 600;
        ScriptedProvider scriptedB = scriptProvider(textScript());
        QueryEngine engineB = newEngine();
        TestHandler handlerB = new TestHandler();
        QueryEngine.QueryResult resultB = engineB.execute(configFor("kimi-k3", maxTokensForBudget(budgetB)),
                collisionState(bmp, otherMandatory, "image/bmp", STRATEGY_ONE_FILLER), handlerB);

        assertThat(resultB.isSuccess()).isTrue();
        assertThat(scriptedB.requests()).hasSize(1);
        List<byte[]> images = decodedImageBytes(scriptedB.requests().getFirst());
        assertThat(images).hasSize(2);
        // mandatory 原字节不动；工具图被缩略为 JPEG（≠ 原载荷），而非被移除或替换为文本。
        assertThat(images).anySatisfy(bytes -> assertThat(bytes).isEqualTo(otherMandatory));
        assertThat(images).noneSatisfy(bytes -> assertThat(bytes).isEqualTo(payload));
        assertThat(images).anySatisfy(bytes -> {
            assertThat(bytes).hasSizeGreaterThan(2);
            assertThat(bytes[0]).isEqualTo((byte) 0xFF);
            assertThat(bytes[1]).isEqualTo((byte) 0xD8);
        });
    }

    // ═══════════════ 场景构造 ═══════════════

    /** 单工具引用 + mandatory 附件（base64）+ 可调超预算填充。 */
    private QueryLoopState collisionState(Path file, byte[] mandatoryPayload, String mimeType, int fillerRepeats)
            throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(mandatoryUserMessage(mandatoryPayload));
        messages.add(toolUseMessage("assistant-1", "read-1", file));
        messages.add(toolResultMessage("tool-result-1", "read-1", "assistant-1",
                filler(fillerRepeats) + refText(file, mimeType)));
        return new QueryLoopState(messages, ToolUseContext.of(directory.toString(), "test-session"));
    }

    /** 预算内轻量场景：mandatory + 工具图引用，无超预算填充。 */
    private QueryLoopState lightCollisionState(Path bmpFile, byte[] mandatoryPayload) throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(mandatoryUserMessage(mandatoryPayload));
        messages.add(toolUseMessage("assistant-1", "read-1", bmpFile));
        messages.add(toolResultMessage("tool-result-1", "read-1", "assistant-1",
                "small tool output " + refText(bmpFile, "image/bmp")));
        return new QueryLoopState(messages, ToolUseContext.of(directory.toString(), "test-session"));
    }

    /** 两个不同源文件（同载荷）引用 + mandatory 附件。 */
    private QueryLoopState multiSourceState(Path first, Path second, byte[] mandatoryPayload, int fillerRepeats)
            throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(mandatoryUserMessage(mandatoryPayload));
        messages.add(toolUseMessage("assistant-1", "read-1", first));
        messages.add(toolResultMessage("tool-result-1", "read-1", "assistant-1",
                filler(fillerRepeats) + refText(first, "image/bmp")));
        messages.add(toolUseMessage("assistant-2", "read-2", second));
        messages.add(toolResultMessage("tool-result-2", "read-2", "assistant-2", refText(second, "image/bmp")));
        return new QueryLoopState(messages, ToolUseContext.of(directory.toString(), "test-session"));
    }

    /** URL 形态 mandatory 附件 + 工具引用。 */
    private QueryLoopState urlMandatoryState(Path bmpFile, String url, int fillerRepeats) throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message.UserMessage("user-1", Instant.now(), List.of(
                new ContentBlock.TextBlock("read the image"),
                ContentBlock.ImageBlock.fromUrl("image/png", url)), null, null));
        messages.add(toolUseMessage("assistant-1", "read-1", bmpFile));
        messages.add(toolResultMessage("tool-result-1", "read-1", "assistant-1",
                filler(fillerRepeats) + refText(bmpFile, "image/bmp")));
        return new QueryLoopState(messages, ToolUseContext.of(directory.toString(), "test-session"));
    }

    private Message.UserMessage mandatoryUserMessage(byte[] payload) {
        int[] dims = InlineImageBudget.dimensions(payload);
        return new Message.UserMessage("user-1", Instant.now(), List.of(
                new ContentBlock.TextBlock("read the image"),
                new ContentBlock.ImageBlock("image/png", Base64.getEncoder().encodeToString(payload),
                        dims[0], dims[1], null)), null, null);
    }

    private Message.AssistantMessage toolUseMessage(String id, String callId, Path file) {
        return new Message.AssistantMessage(id, Instant.now(),
                List.of(new ContentBlock.ToolUseBlock(callId, "Read",
                        objectMapper.valueToTree(Map.of("file_path", file.toString())))), "tool_use", null);
    }

    private Message.UserMessage toolResultMessage(String id, String callId, String assistantId, String content) {
        return new Message.UserMessage(id, Instant.now(),
                List.of(new ContentBlock.ToolResultBlock(callId, content, false)), null, assistantId);
    }

    /** 注入探针用：仅 [assistant tool_use, user tool_result(ref)] 对。 */
    private List<Message> refMessages(Path... files) throws Exception {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            messages.add(toolUseMessage("assistant-" + (i + 1), "read-" + (i + 1), files[i]));
            messages.add(toolResultMessage("tool-result-" + (i + 1), "read-" + (i + 1),
                    "assistant-" + (i + 1), refText(files[i], "image/bmp")));
        }
        return messages;
    }

    private String refText(Path file, String mimeType) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        BufferedImage image = ImageIO.read(file.toFile());
        ImageToolRef ref = new ImageToolRef(file.toString(), mimeType, bytes.length, sha256(bytes),
                image.getWidth(), image.getHeight());
        return "[image_ref]" + objectMapper.writeValueAsString(ref) + "[/image_ref]";
    }

    private static String filler(int repeats) {
        return "budget filler text ".repeat(repeats);
    }

    // ═══════════════ 文件夹具 ═══════════════

    private Path writeGradientBmp(String name, int width, int height) throws IOException {
        Path file = directory.resolve(name);
        ImageIO.write(gradientImage(width, height), "bmp", file.toFile());
        return file;
    }

    private Path writeCheckerBmp(String name) throws IOException {
        Path file = directory.resolve(name);
        ImageIO.write(checkerBinary(60, 60, 8), "bmp", file.toFile());
        return file;
    }

    /** 仅修改 BMP 头部 xPelsPerMeter（解码器不使用的字段）：字节不同、解码图像相同。 */
    private Path writeHeaderPatchedBmp(String name, Path source) throws IOException {
        byte[] patched = Files.readAllBytes(source).clone();
        patched[38] = 0x10;
        patched[39] = 0x27;
        Path file = directory.resolve(name);
        Files.write(file, patched);
        return file;
    }

    private Path writeSolidBinaryBmp(String name, int size) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, 0xFFFFFF);
            }
        }
        Path file = directory.resolve(name);
        ImageIO.write(image, "bmp", file.toFile());
        return file;
    }

    private static byte[] pngPayloadOf(Path file) throws IOException {
        return pngBytes(ImageIO.read(file.toFile()));
    }

    private static byte[] pngBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static BufferedImage gradientImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, (x * 255 / width) << 16 | (y * 255 / height) << 8 | 128);
            }
        }
        return image;
    }

    private static BufferedImage differentImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, (y * 255 / height) << 16 | 64 | (x * 255 / width));
            }
        }
        return image;
    }

    private static BufferedImage checkerBinary(int width, int height, int cell) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, (((x / cell) + (y / cell)) % 2 == 0) ? 0xFFFFFF : 0);
            }
        }
        return image;
    }

    // ═══════════════ 引擎与 provider 设施 ═══════════════

    private record ScriptedProvider(List<List<Map<String, Object>>> requests) {}

    private ScriptedProvider scriptProvider(BiConsumer<Integer, StreamChatCallback> script) {
        LlmProvider provider = mock(LlmProvider.class, withSettings().lenient());
        when(providerRegistry.getProvider(anyString())).thenReturn(provider);
        List<List<Map<String, Object>>> requests = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) inv.getArgument(1);
            requests.add(List.copyOf(messages));
            StreamChatCallback callback = inv.getArgument(7);
            script.accept(requests.size(), callback);
            return null;
        }).when(provider).streamChat(anyString(), anyList(), anyString(), anyList(),
                anyInt(), any(), any(LlmCallContext.class), any(StreamChatCallback.class));
        return new ScriptedProvider(requests);
    }

    private static BiConsumer<Integer, StreamChatCallback> textScript() {
        return (call, callback) -> finish(callback, "end_turn", new LlmStreamEvent.TextDelta("done"));
    }

    /** 第 1 轮返回 tool_use，第 2 轮返回文本：驱动两轮循环以观察确认行为。 */
    private static BiConsumer<Integer, StreamChatCallback> toolThenTextScript() {
        return (call, callback) -> {
            if (call == 1) {
                callback.onEvent(new LlmStreamEvent.ToolUseStart("call-1", "EchoTool"));
                callback.onEvent(new LlmStreamEvent.ToolInputDelta("call-1", "{}"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(10, 5, 0, 0), "tool_use"));
                callback.onComplete();
            } else {
                finish(callback, "end_turn", new LlmStreamEvent.TextDelta("done"));
            }
        };
    }

    private static void finish(StreamChatCallback callback, String stopReason, LlmStreamEvent... events) {
        for (LlmStreamEvent event : events) callback.onEvent(event);
        callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(10, 5, 0, 0), stopReason));
        callback.onComplete();
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

    /** 记录每轮传入注入器的 confirmed 集合快照（调用时拷贝）。 */
    private void installSpyInjector() {
        imageRefInjector = spy(new ImageRefInjector(objectMapper, allowingSecurity()));
        confirmedSnapshots = new ArrayList<>();
        doAnswer(inv -> {
            confirmedSnapshots.add(new HashSet<>(inv.getArgument(3)));
            return inv.callRealMethod();
        }).when(imageRefInjector).injectForApiCall(anyList(), anyInt(), anyInt(), anySet(), anyMap(),
                nullable(String.class), anyInt());
    }

    private QueryEngine newEngine() {
        return new QueryEngine(
                providerRegistry, compactService, apiRetryService, tokenCounter,
                objectMapper, streamingToolExecutor, new MessageNormalizer(), hookService,
                snipService, microCompactService, modelRegistry,
                thinkingBudgetCalculator, modelTierService, fileHistoryService,
                toolResultSummarizer, contextCascade, compactMetrics,
                null, null,
                null, featureFlagService,
                new DefaultTerminationStrategy(), new ToolPriorityScheduler(), null,
                new AgentTimeoutConfig(), tokenBudgetGuard,
                imageRefInjector != null ? imageRefInjector : new ImageRefInjector(objectMapper, allowingSecurity()),
                null, null, userImageTranscoder);
    }

    private static PathSecurityService allowingSecurity() {
        PathSecurityService security = mock(PathSecurityService.class);
        lenient().when(security.checkReadPermission(anyString(), anyString()))
                .thenReturn(PathSecurityService.PathCheckResult.allowed());
        return security;
    }

    private static QueryConfig configFor(String model, int maxTokens) {
        Tool echo = mock(Tool.class);
        lenient().when(echo.getName()).thenReturn("EchoTool");
        return QueryConfig.withDefaults(model, SYSTEM_PROMPT,
                List.of(echo), List.of(), maxTokens, WINDOW, new ThinkingConfig.Disabled(), 10, "test");
    }

    private static int maxTokensForBudget(int budget) {
        int system = (int) (SYSTEM_PROMPT.length() / 3.5);
        return WINDOW - system - (int) (WINDOW * 0.05) - budget;
    }

    private static ModelCapabilities base64OnlyCapabilities(String modelId, int maxImages) {
        return new ModelCapabilities(modelId, modelId, 65536, 1_000_000,
                true, false, true, maxImages, true, 0.0, 0.0, 3.5, false,
                ModelCapabilities.ImageInputMode.BASE64_ONLY);
    }

    /** 从「Local context budget exceeded: N > B」错误中取回 N，并校验 B 与构造一致。 */
    private static int estimatedTokensFrom(TestHandler handler, int expectedBudget) {
        for (Throwable error : handler.errors) {
            Matcher matcher = Pattern.compile("Local context budget exceeded: (\\d+) > (\\d+)")
                    .matcher(String.valueOf(error.getMessage()));
            if (matcher.find()) {
                assertThat(Integer.parseInt(matcher.group(2))).isEqualTo(expectedBudget);
                return Integer.parseInt(matcher.group(1));
            }
        }
        throw new AssertionError("未找到预算超限错误: " + handler.errors);
    }

    // ═══════════════ 断言辅助 ═══════════════

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

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static class TestHandler implements QueryMessageHandler {
        final List<Message.SystemMessage> systemMessages = new CopyOnWriteArrayList<>();
        final List<String> textDeltas = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();

        @Override public void onSystemMessage(Message.SystemMessage message) { systemMessages.add(message); }
        @Override public void onTextDelta(String text) { textDeltas.add(text); }
        @Override public void onToolUseStart(String id, String name) {}
        @Override public void onToolUseComplete(String id, ContentBlock.ToolUseBlock toolUse) {}
        @Override public void onToolResult(String id, ContentBlock.ToolResultBlock result) {}
        @Override public void onAssistantMessage(Message.AssistantMessage message) {}
        @Override public void onError(Throwable error) { errors.add(error); }
        @Override public void onCompactEvent(String type, int beforeTokens, int afterTokens) {}
        @Override public void onRecovery(RecoveryEvent event) {}
    }
}