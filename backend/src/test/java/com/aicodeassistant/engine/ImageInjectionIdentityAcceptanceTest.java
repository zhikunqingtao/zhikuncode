package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.tool.impl.ImageResultExternalizer.ImageToolRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R-02 验收 — 图片确认必须绑定"最终实际保留的图片身份"。
 * <p>
 * 覆盖单元级语义：注入记录（源身份 → 载荷哈希）只在其字节最终仍以 image 形式保留时确认，
 * 转码/缩略/文字替代/移除/字节不匹配一律不确认；与 mandatory 附件相撞的工具图不被降级策略移除。
 * 并包含一条「注入 → normalize/toMaps → enforcePhase2」的完整管线一致性用例。
 */
@DisplayName("R-02 图片确认身份 — 单元级验收")
class ImageInjectionIdentityAcceptanceTest {

    @TempDir Path directory;

    private final ObjectMapper mapper = new ObjectMapper();
    private final TokenBudgetGuard guard = new TokenBudgetGuard();

    // ═══════════════ 注入 + 管线一致性 ═══════════════

    @Test
    @DisplayName("注入 → normalize/toMaps → enforcePhase2：预算内全部图片按源身份确认（含 BMP 转码）")
    void injectionThroughNormalizationConfirmsEveryRetainedSourceIdentity() throws Exception {
        Path first = writePng("first.png", randomImage(64));
        Path second = writePng("second.png", randomImage(48));
        Path bitmap = writeBmp("third.bmp", gradientImage(120, 90));

        ImageRefInjector.InjectResult injected = inject(injector(), 8, first, second, bitmap);
        assertThat(injected.pendingHashes()).hasSize(3);
        assertThat(injected.injectedSourceToPayloadHashes()).hasSize(3);

        // BMP 被转换为 PNG 载荷：源哈希（BMP 文件字节）≠ 载荷哈希（PNG 字节）。
        String bmpSourceHash = sha256(Files.readAllBytes(bitmap));
        String bmpPayloadHash = injected.injectedSourceToPayloadHashes().get(bmpSourceHash);
        assertThat(bmpPayloadHash).isNotEqualTo(bmpSourceHash);
        assertThat(decodedImageBlocks(injected.messages()).stream().map(ImageInjectionIdentityAcceptanceTest::sha256))
                .contains(bmpPayloadHash);

        List<Map<String, Object>> apiMessages = toApiMessages(injected.messages());
        int estimated = guard.estimateApiTokens(apiMessages, 3.5);
        TokenBudgetGuard.FinalBudgetResult result = guard.enforcePhase2(
                apiMessages, estimated + 1000, injected.injectedSourceToPayloadHashes(),
                new HashSet<>(injected.injectedSourceToPayloadHashes().keySet()), 3.5);

        assertThat(result.fitsBudget()).isTrue();
        assertThat(result.retainedSourceImageHashes())
                .containsExactlyInAnyOrderElementsOf(injected.injectedSourceToPayloadHashes().keySet());
        // 映射哈希与最终块哈希口径一致：块哈希 = 映射的载荷侧值。
        assertThat(collectPayloadHashes(result.apiMessages()))
                .containsExactlyInAnyOrderElementsOf(injected.injectedSourceToPayloadHashes().values());
    }

    @Test
    @DisplayName("原图保留 → 确认；PNG 直读时源哈希与载荷哈希一致")
    void originalImageWithinBudgetConfirmsSourceIdentity() throws Exception {
        byte[] bytes = UserImageTranscoderTest.png(48);
        Path file = writeRaw("original.png", bytes);

        ImageRefInjector.InjectResult injected = inject(injector(), 8, file);
        String sourceHash = sha256(bytes);
        assertThat(injected.injectedSourceToPayloadHashes()).containsEntry(sourceHash, sha256(bytes));

        List<Map<String, Object>> apiMessages = toApiMessages(injected.messages());
        TokenBudgetGuard.FinalBudgetResult result = guard.enforcePhase2(
                apiMessages, guard.estimateApiTokens(apiMessages, 3.5) + 500,
                injected.injectedSourceToPayloadHashes(), Set.of(sourceHash), 3.5);

        assertThat(result.fitsBudget()).isTrue();
        assertThat(result.retainedSourceImageHashes()).containsExactly(sourceHash);
    }

    @Test
    @DisplayName("成功注入才记录身份：模型不支持 / 文件缺失 / 已确认去重均不记录")
    void onlySuccessfulInjectionsAreRecorded() throws Exception {
        Path file = writeRaw("present.png", UserImageTranscoderTest.png(24));

        // 模型不支持图片 → 空结果
        ImageRefInjector.InjectResult unsupported = inject(injector(), 0, file);
        assertThat(unsupported.injectedSourceToPayloadHashes()).isEmpty();

        // 引用指向不存在文件 → 不记录
        Path missing = directory.resolve("missing.png");
        ImageRefInjector.InjectResult failed = inject(injector(), 8, missing);
        assertThat(failed.injectedSourceToPayloadHashes()).isEmpty();
        assertThat(failed.pendingHashes()).isEmpty();

        // 已确认哈希 → 不再注入、不记录
        String hash = sha256(Files.readAllBytes(file));
        ImageRefInjector.InjectResult deduped = injector().injectForApiCall(
                messagesForRefs(file), 0, 10_000_000, Set.of(hash), new HashMap<>(),
                directory.toString(), 8);
        assertThat(deduped.injectedSourceToPayloadHashes()).isEmpty();
    }

    @Test
    @DisplayName("多源同载荷（无需降级）→ 确认集合包含全部源身份，不再被单值映射覆盖丢源")
    void multipleSourcesSharingOnePayloadAreAllConfirmed() throws Exception {
        String payload = base64(gradientPngBytes(64, 64));
        String payloadHash = sha256(Base64.getDecoder().decode(payload));
        String firstSource = "source-a";
        String secondSource = "source-b";
        Map<String, String> identity = new LinkedHashMap<>();
        identity.put(firstSource, payloadHash);
        identity.put(secondSource, payloadHash);
        // 同一载荷字节在最终 payload 中出现两次（两个不同源的文件引用同为该字节），预算充足。
        List<Map<String, Object>> apiMessages = List.of(userMessage(
                imageBlock("image/png", payload),
                imageBlock("image/png", payload)));

        TokenBudgetGuard.FinalBudgetResult result = guard.enforcePhase2(
                apiMessages, guard.estimateApiTokens(apiMessages, 3.5) + 1000,
                identity, new HashSet<>(identity.keySet()), 3.5);

        assertThat(result.fitsBudget()).isTrue();
        assertThat(result.retainedSourceImageHashes())
                .containsExactlyInAnyOrder(firstSource, secondSource);
    }

    @Test
    @DisplayName("缩略降级：多源同载荷并集登记 → resize 后仍确认全部源身份")
    void thumbnailDegradationConfirmsAllSourcesSharingOnePayload() throws Exception {
        String payload = base64(gradientPngBytes(1400, 1400));
        String payloadHash = sha256(Base64.getDecoder().decode(payload));
        String firstSource = "source-large-a";
        String secondSource = "source-large-b";
        Map<String, String> identity = new LinkedHashMap<>();
        identity.put(firstSource, payloadHash);
        identity.put(secondSource, payloadHash);
        List<Map<String, Object>> apiMessages = List.of(userMessage(
                imageBlock("image/png", payload),
                imageBlock("image/png", payload)));

        // 两份 1400px 全图 ≈ 2×8768 tokens；缩略后 ≈ 2×2624 → 预算 6000 只能靠策略1救场。
        TokenBudgetGuard.FinalBudgetResult result = guard.enforcePhase2(
                apiMessages, 6000, identity, new HashSet<>(identity.keySet()), 3.5);

        assertThat(result.fitsBudget()).isTrue();
        assertThat(result.reductionSummary()).contains("策略1-缩略图替换");
        assertThat(result.retainedSourceImageHashes())
                .containsExactlyInAnyOrder(firstSource, secondSource);
        // 缩略后字节已变：原载荷哈希不再存在，身份经「resize 后哈希 → 源集合」并集登记取回。
        assertThat(listPayloadHashes(result.apiMessages())).hasSize(2).doesNotContain(payloadHash);
    }

    @Test
    @DisplayName("混合集合规则：同载荷源中只有部分可降级 → 整块不可降级（全集合规则）")
    void partiallyDegradableSourceSetMakesWholePayloadUndegradable() throws Exception {
        String payload = base64(gradientPngBytes(1400, 1400));
        String payloadHash = sha256(Base64.getDecoder().decode(payload));
        String degradableSource = "source-degradable";
        String protectedSource = "source-protected";
        Map<String, String> identity = new LinkedHashMap<>();
        identity.put(degradableSource, payloadHash);
        identity.put(protectedSource, payloadHash);
        List<Map<String, Object>> apiMessages = List.of(userMessage(
                imageBlock("image/png", payload),
                imageBlock("image/png", payload)));

        // 预算 5000：若块可降级（旧行为下由任意"赢家"决定），策略2 文字替代足以救场；
        // 全集合规则下 protectedSource 不在可降级集合内 → 整块不可降级 → 所有策略都无法触碰。
        TokenBudgetGuard.FinalBudgetResult result = guard.enforcePhase2(
                apiMessages, 5000, identity, Set.of(degradableSource), 3.5);

        assertThat(result.fitsBudget()).isFalse();
        assertThat(result.reductionSummary())
                .contains("策略1-缩略图替换")
                .contains("策略2-图片替换为文本")
                .contains("策略3-移除所有图片");
        // 图片原字节未被触碰；两块仍以 image 形式保留 → 两个源身份都会被确认。
        assertThat(listPayloadHashes(result.apiMessages()))
                .containsExactlyInAnyOrder(payloadHash, payloadHash);
        assertThat(result.retainedSourceImageHashes())
                .containsExactlyInAnyOrder(degradableSource, protectedSource);
    }

    // ═══════════════ 降级策略下的确认语义 ═══════════════

    @Test
    @DisplayName("缩略降级后保留 → 确认（resize 后字节变化仍按块追踪）")
    void thumbnailDegradationStillConfirmsSourceIdentity() throws Exception {
        String payload = base64(gradientPngBytes(1400, 1400));
        String sourceHash = "source-large-image";
        Map<String, String> identity = Map.of(sourceHash, sha256(Base64.getDecoder().decode(payload)));
        List<Map<String, Object>> apiMessages = List.of(userMessage(imageBlock("image/png", payload)));

        // 预算低于原图估算（1400px ≈ 8768 tokens），高于 640px 缩略图估算（≈ 2624）。
        TokenBudgetGuard.FinalBudgetResult result = guard.enforcePhase2(
                apiMessages, 4000, identity, Set.of(sourceHash), 3.5);

        assertThat(result.fitsBudget()).isTrue();
        assertThat(result.reductionSummary()).contains("策略1-缩略图替换");
        assertThat(result.retainedSourceImageHashes()).containsExactly(sourceHash);
        // 最终载荷已是缩略图：原载荷哈希不再存在，但身份仍通过变换时登记取回。
        assertThat(collectPayloadHashes(result.apiMessages()))
                .hasSize(1)
                .doesNotContain(identity.get(sourceHash));
    }

    @Test
    @DisplayName("文字替代（策略2）→ 不确认")
    void textSummaryReplacementDoesNotConfirm() throws Exception {
        String payload = base64(gradientPngBytes(1400, 1400));
        String sourceHash = "source-to-be-replaced";
        Map<String, String> identity = Map.of(sourceHash, sha256(Base64.getDecoder().decode(payload)));
        List<Map<String, Object>> apiMessages = List.of(userMessage(imageBlock("image/png", payload)));

        // 预算低于缩略图估算（640px ≈ 2624 tokens），但高于文字替代后的估算。
        TokenBudgetGuard.FinalBudgetResult result = guard.enforcePhase2(
                apiMessages, 2000, identity, Set.of(sourceHash), 3.5);

        assertThat(result.fitsBudget()).isTrue();
        assertThat(result.reductionSummary()).contains("策略2-图片替换为文本");
        assertThat(result.retainedSourceImageHashes()).isEmpty();
        assertThat(collectPayloadHashes(result.apiMessages())).isEmpty();
    }

    @Test
    @DisplayName("移除（策略3）→ 不确认")
    void removalDoesNotConfirm() throws Exception {
        String payload = base64(gradientPngBytes(1400, 1400));
        String sourceHash = "source-to-be-removed";
        Map<String, String> identity = Map.of(sourceHash, sha256(Base64.getDecoder().decode(payload)));
        List<Map<String, Object>> apiMessages = List.of(userMessage(
                imageBlock("image/png", payload),
                textBlock("x".repeat(3500))));

        // 缩略/文字都救不回预算（正文自身已超限）→ 策略3 移除图片。
        TokenBudgetGuard.FinalBudgetResult result = guard.enforcePhase2(
                apiMessages, 500, identity, Set.of(sourceHash), 3.5);

        assertThat(result.reductionSummary()).contains("策略3-移除所有图片");
        assertThat(result.fitsBudget()).isFalse();
        assertThat(result.retainedSourceImageHashes()).isEmpty();
        assertThat(collectPayloadHashes(result.apiMessages())).isEmpty();
    }

    @Test
    @DisplayName("同内容 mandatory 附件相撞的工具图 → 可确认且不被降级策略移除")
    void collidingMandatoryAttachmentImageSurvivesDegradationAndStaysConfirmable() throws Exception {
        byte[] collidedBytes = gradientPngBytes(1400, 1400);
        byte[] degradableBytes = gradientPngBytes(1600, 1600);
        String collidedPayload = sha256(collidedBytes);
        String collidedSource = "tool-source-colliding-with-attachment";
        String degradableSource = "other-tool-source";
        Map<String, String> identity = new LinkedHashMap<>();
        identity.put(collidedSource, collidedPayload);
        identity.put(degradableSource, sha256(degradableBytes));

        // 相撞字节在 payload 中出现两次（mandatory 附件 + 工具图），它们同为不可降级。
        List<Map<String, Object>> apiMessages = List.of(userMessage(
                imageBlock("image/png", base64(collidedBytes)),
                imageBlock("image/png", base64(collidedBytes)),
                imageBlock("image/png", base64(degradableBytes))));

        // 预算 = 两份相撞图（≈ 2 × 8768）勉强可容纳；只有可降级的那张可以变化。
        TokenBudgetGuard.FinalBudgetResult result = guard.enforcePhase2(
                apiMessages, 18_000, identity, Set.of(degradableSource), 3.5);

        assertThat(result.fitsBudget()).isTrue();
        assertThat(result.reductionSummary()).contains("策略2-图片替换为文本");
        assertThat(result.retainedSourceImageHashes()).containsExactly(collidedSource);
        // 相撞字节的两份 image 均保持原样（不可降级），只有可降级的另一张被文字替代。
        assertThat(listPayloadHashes(result.apiMessages()))
                .containsExactlyInAnyOrder(collidedPayload, collidedPayload);
    }

    // ═══════════════ 不匹配边界 ═══════════════

    @Test
    @DisplayName("注入记录与最终块字节不匹配 → 不得误确认（缺块 / 字节不同）")
    void mismatchedFinalBlocksAreNeverConfirmed() throws Exception {
        String missingSource = "source-not-in-payload";
        String missingPayload = sha256(UserImageTranscoderTest.png(32));

        // ① 最终 payload 完全没有图片。
        List<Map<String, Object>> textOnly = List.of(userMessage(textBlock("no image here")));
        TokenBudgetGuard.FinalBudgetResult absent = guard.enforcePhase2(
                textOnly, 100_000, Map.of(missingSource, missingPayload), Set.of(missingSource), 3.5);
        assertThat(absent.retainedSourceImageHashes()).isEmpty();

        // ② 最终 payload 有图片，但字节与注入记录不一致。
        List<Map<String, Object>> mismatched = List.of(userMessage(
                imageBlock("image/png", base64(gradientPngBytes(1400, 1400)))));
        TokenBudgetGuard.FinalBudgetResult mismatch = guard.enforcePhase2(
                mismatched, 100_000, Map.of(missingSource, missingPayload), Set.of(missingSource), 3.5);
        assertThat(mismatch.retainedSourceImageHashes()).isEmpty();

        // ③ 超限路径同样不得反查/误确认（未登记字节的图片不参与降级，也不被确认）。
        TokenBudgetGuard.FinalBudgetResult degraded = guard.enforcePhase2(
                mismatched, 1000, Map.of(missingSource, missingPayload), Set.of(missingSource), 3.5);
        assertThat(degraded.retainedSourceImageHashes()).isEmpty();
    }

    @Test
    @DisplayName("真实 UserImageTranscoder 省略注入图（数量上限）→ 不确认")
    void imageOmittedByRealTranscoderIsNotConfirmed() throws Exception {
        byte[] toolBytes = UserImageTranscoderTest.png(32);
        Path toolFile = writeRaw("tool.png", toolBytes);
        ImageRefInjector.InjectResult injected = inject(injector(), 8, toolFile);
        String toolSource = sha256(toolBytes);
        assertThat(injected.injectedSourceToPayloadHashes()).containsKey(toolSource);

        // 当前消息自带 1 张 mandatory 附件，模型上限 1：工具图（更旧）在二次转码中被省略。
        byte[] currentBytes = UserImageTranscoderTest.png(16);
        Message.UserMessage current = new Message.UserMessage("current", Instant.now(), List.of(
                new ContentBlock.ImageBlock("image/png",
                        Base64.getEncoder().encodeToString(currentBytes), 16, 16, null)), null, null);
        List<Message> withCurrent = new ArrayList<>(injected.messages());
        withCurrent.add(current);

        UserImageTranscoder transcoder = new UserImageTranscoder(UserImageTranscoderTest.properties());
        UserImageTranscoder.TranscodeResult transcoded =
                transcoder.transcode(withCurrent, UserImageTranscoderTest.caps(1));
        assertThat(decodedImageBlocks(transcoded.messages()).stream()
                .map(ImageInjectionIdentityAcceptanceTest::sha256)).doesNotContain(toolSource);
        assertThat(transcoded.warnings()).anySatisfy(warning ->
                assertThat(warning).contains("本轮已省略"));

        List<Map<String, Object>> apiMessages = toApiMessages(transcoded.messages());
        TokenBudgetGuard.FinalBudgetResult result = guard.enforcePhase2(
                apiMessages, guard.estimateApiTokens(apiMessages, 3.5) + 1000,
                injected.injectedSourceToPayloadHashes(),
                new HashSet<>(injected.injectedSourceToPayloadHashes().keySet()), 3.5);

        assertThat(result.fitsBudget()).isTrue();
        assertThat(result.retainedSourceImageHashes()).isEmpty();
    }

    @Test
    @DisplayName("传入空身份映射（模型不支持/未注入路径）→ 保留集合为空且不降级任何图片")
    void emptyIdentityMapRetainsNothingAndDegradesNothing() throws Exception {
        String payload = base64(gradientPngBytes(1400, 1400));
        List<Map<String, Object>> apiMessages = List.of(userMessage(imageBlock("image/png", payload)));

        TokenBudgetGuard.FinalBudgetResult result = guard.enforcePhase2(
                apiMessages, 4000, Map.of(), Set.of(), 3.5);

        assertThat(result.retainedSourceImageHashes()).isEmpty();
        // 无身份记录时不允许降级他人图片（安全性：不触碰未登记的载荷）。
        assertThat(result.fitsBudget()).isFalse();
        assertThat(collectPayloadHashes(result.apiMessages()))
                .containsExactly(sha256(Base64.getDecoder().decode(payload)));
    }

    // ═══════════════ 辅助 ═══════════════

    private ImageRefInjector injector() {
        PathSecurityService security = mock(PathSecurityService.class);
        when(security.checkReadPermission(anyString(), anyString()))
                .thenReturn(PathSecurityService.PathCheckResult.allowed());
        return new ImageRefInjector(mapper, security);
    }

    /** 构造 [assistant tool_use, user tool_result(ref)] 消息并执行真实注入。 */
    private ImageRefInjector.InjectResult inject(ImageRefInjector injector, int modelMaxImages, Path... files)
            throws IOException {
        return injector.injectForApiCall(messagesForRefs(files), 0, 10_000_000, Set.of(),
                new HashMap<>(), directory.toString(), modelMaxImages);
    }

    private List<Message> messagesForRefs(Path... files) throws IOException {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            Path file = files[i];
            String toolUseId = "read-" + i;
            ImageToolRef ref = Files.exists(file)
                    ? refFor(file, mimeFor(file))
                    : new ImageToolRef(file.toString(), mimeFor(file), 10, "deadbeef", 1, 1);
            String refText = "[image_ref]" + mapper.writeValueAsString(ref) + "[/image_ref]";
            messages.add(new Message.AssistantMessage("assistant-" + i, Instant.now(),
                    List.of(new ContentBlock.ToolUseBlock(toolUseId, "Read",
                            mapper.valueToTree(Map.of("file_path", file.toString())))), "tool_use", null));
            messages.add(new Message.UserMessage("tool-result-" + i, Instant.now(),
                    List.of(new ContentBlock.ToolResultBlock(toolUseId, refText, false)),
                    null, "assistant-" + i));
        }
        return messages;
    }

    private ImageToolRef refFor(Path file, String mimeType) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        BufferedImage image = ImageIO.read(file.toFile());
        return new ImageToolRef(file.toString(), mimeType, bytes.length, sha256(bytes),
                image.getWidth(), image.getHeight());
    }

    private static List<Map<String, Object>> toApiMessages(List<Message> messages) {
        return com.aicodeassistant.llm.MessageParamConverter.toMaps(
                new MessageNormalizer().normalizeTyped(messages));
    }

    private static Map<String, Object> imageBlock(String mediaType, String base64Data) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "base64");
        source.put("media_type", mediaType);
        source.put("data", base64Data);
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "image");
        block.put("source", source);
        return block;
    }

    private static Map<String, Object> textBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    private static Map<String, Object> userMessage(Object... blocks) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", List.of(blocks));
        return message;
    }

    private static Set<String> collectPayloadHashes(List<Map<String, Object>> apiMessages) {
        return new LinkedHashSet<>(listPayloadHashes(apiMessages));
    }

    private static List<String> listPayloadHashes(List<Map<String, Object>> apiMessages) {
        List<String> hashes = new ArrayList<>();
        for (Map<String, Object> message : apiMessages) {
            if (!(message.get("content") instanceof List<?> content)) continue;
            for (Object item : content) {
                if (!(item instanceof Map<?, ?> block) || !"image".equals(block.get("type"))) continue;
                Object source = block.get("source");
                if (source instanceof Map<?, ?> sourceMap && sourceMap.get("data") instanceof String data) {
                    hashes.add(sha256(Base64.getDecoder().decode(data)));
                }
            }
        }
        return hashes;
    }

    private static List<byte[]> decodedImageBlocks(List<Message> messages) {
        List<byte[]> images = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof Message.UserMessage user && user.content() != null) {
                for (ContentBlock block : user.content()) {
                    if (block instanceof ContentBlock.ImageBlock image && image.base64Data() != null) {
                        images.add(Base64.getDecoder().decode(image.base64Data()));
                    }
                }
            }
        }
        return images;
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String mimeFor(Path file) {
        return file.getFileName().toString().endsWith(".bmp") ? "image/bmp" : "image/png";
    }

    private Path writeRaw(String name, byte[] bytes) throws IOException {
        Path file = directory.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    private Path writePng(String name, BufferedImage image) throws IOException {
        Path file = directory.resolve(name);
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    private Path writeBmp(String name, BufferedImage image) throws IOException {
        Path file = directory.resolve(name);
        ImageIO.write(image, "bmp", file.toFile());
        return file;
    }

    private static byte[] gradientPngBytes(int width, int height) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(gradientImage(width, height), "png", output);
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

    private static BufferedImage randomImage(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(19);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        return image;
    }
}