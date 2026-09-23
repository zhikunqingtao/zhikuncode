package com.aicodeassistant.session.merge;

import com.aicodeassistant.authorization.AuthorizationSubject;
import com.aicodeassistant.authorization.AuthorizationSubjectResolver;
import com.aicodeassistant.engine.ImageRefInjector;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.impl.HandoffReadTool;
import com.aicodeassistant.tool.impl.ImageResultExternalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Narrow synthetic component reproductions, not end-to-end E-session acceptance.
 * HandoffReadService.asset and the trusted subject resolver are mocked: these tests
 * start after authorization/catalog lookup has returned an already-approved asset.
 * HandoffReadTool, ImageResultExternalizer and ImageRefInjector are real code.
 * No network, provider, live database, retained user material or production edits.
 *
 * The embedded 2x2 WebP was generated locally with Pillow/libwebp:
 * Image.new("RGB", (2, 2), (220, 30, 70)).save(buffer, "WEBP", lossless=True).
 * Before embedding, Pillow reopened and fully decoded these exact bytes as WEBP,
 * dimensions (2,2), first pixel (220,30,70). No downloaded fixture is involved.
 */
class RankingAssetReproTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void validSyntheticWebpAcceptedBySuffixButRejectedAfterExtensionlessCopy() throws Exception {
        byte[] bytes = Base64.getDecoder().decode(
                "UklGRh4AAABXRUJQVlA4TBEAAAAvAUAAAAdQj3LXqP+BiOh/AAA=");
        assertThat(new String(bytes, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(bytes, 8, 8, StandardCharsets.US_ASCII)).isEqualTo("WEBPVP8L");
        Path named = Files.write(root.resolve("synthetic.webp"), bytes);
        Path copied = Files.write(root.resolve("a_synthetic_webp_no_extension"), bytes);
        assertThat(Files.readAllBytes(copied)).isEqualTo(Files.readAllBytes(named));

        // This records the stock application/JDK reader environment, not an
        // assumption that every possible deployment lacks a WebP ImageIO plug-in.
        try (var stream = ImageIO.createImageInputStream(copied.toFile())) {
            assertThat(ImageIO.getImageReaders(stream).hasNext())
                    .as("current application runtime has no content-sniffing WebP ImageIO reader")
                    .isFalse();
        }
        assertThat(Files.probeContentType(named)).isEqualTo("image/webp");
        assertThat(Files.probeContentType(copied)).isNull();

        ToolResult namedResult = callAsset(named);
        ToolResult copiedResult = callAsset(copied);
        assertThat(namedResult.isError()).isFalse();
        assertThat(namedResult.content()).contains("[image_ref]");
        assertThat(copiedResult.isError()).isTrue();
        assertThat(copiedResult.failureCode()).isEqualTo("HANDOFF_ASSET_UNSUPPORTED");
        assertThat(copiedResult.content()).doesNotContain("[image_ref]");
        // The named positive control proves only tool MIME acceptance. Java's
        // absent reader may also give zero dimensions downstream; no claim that
        // named WebP is fully injected or reaches a real model is made here.
    }

    @Test
    void validTenToTwentyMiBPngGetsSuccessfulRefButNeverBecomesAnImageBlock() throws Exception {
        BufferedImage image = new BufferedImage(2048, 2048, BufferedImage.TYPE_INT_RGB);
        int[] pixels = new int[2048 * 2048];
        Random random = new Random(20260923L);
        for (int i = 0; i < pixels.length; i++) pixels[i] = random.nextInt(0x1000000);
        image.setRGB(0, 0, 2048, 2048, pixels, 0, 2048);
        Path large = root.resolve("a_synthetic_png_no_extension");
        assertThat(ImageIO.write(image, "png", large.toFile())).isTrue();
        long size = Files.size(large);
        assertThat(size).isGreaterThan(10L * 1024 * 1024).isLessThan(20L * 1024 * 1024);
        // Decode actual PNG pixels; this is not an invalid file padded past IEND.
        BufferedImage decoded = ImageIO.read(large.toFile());
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(2048);
        assertThat(decoded.getHeight()).isEqualTo(2048);
        assertThat(decoded.getRGB(2037, 2019) & 0xffffff)
                .isEqualTo(image.getRGB(2037, 2019) & 0xffffff);

        ToolResult largeResult = callAsset(large);
        assertThat(largeResult.isError()).isFalse();
        assertThat(largeResult.content()).contains("[image_ref]", "image/png");
        var ref = parseRef(largeResult);
        assertThat(ref.fileSize()).isEqualTo(size);
        assertThat(ref.width()).isEqualTo(2048);
        assertThat(ref.height()).isEqualTo(2048);

        PathSecurityService security = mock(PathSecurityService.class);
        when(security.checkReadPermission(anyString(), anyString()))
                .thenReturn(PathSecurityService.PathCheckResult.allowed());
        ImageRefInjector injector = new ImageRefInjector(json, security);
        Map<String, Integer> rejected = new HashMap<>();
        var largeInjected = injector.injectForApiCall(messages(largeResult), 0, 100_000_000,
                Set.of(), rejected, root.toString(), 1);
        assertThat(largeInjected.pendingHashes()).isEmpty();
        assertThat(((Message.UserMessage) largeInjected.messages().getFirst()).content())
                .noneMatch(block -> block instanceof ContentBlock.ImageBlock);
        assertThat(rejected).containsKey(ref.sha256());
        // Actual first blocker is the earlier 1,500,000 Base64-byte single-image
        // cap, before validateAndLoad's 10MiB file-size guard. Raising the overall
        // request budget cannot bypass that fixed cap. Both are below tool's 20MiB.

        Path small = root.resolve("a_synthetic_small_png_no_extension");
        assertThat(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB),
                "png", small.toFile())).isTrue();
        ToolResult smallResult = callAsset(small);
        assertThat(smallResult.isError()).isFalse();
        var smallInjected = injector.injectForApiCall(messages(smallResult), 0, 100_000_000,
                Set.of(), new HashMap<>(), root.toString(), 1);
        assertThat(smallInjected.pendingHashes()).containsExactly(parseRef(smallResult).sha256());
        assertThat(((Message.UserMessage) smallInjected.messages().getFirst()).content())
                .anyMatch(block -> block instanceof ContentBlock.ImageBlock);
    }

    private ToolResult callAsset(Path asset) throws Exception {
        HandoffReadService reads = mock(HandoffReadService.class);
        AuthorizationSubjectResolver subjects = mock(AuthorizationSubjectResolver.class);
        when(reads.asset("E", "synthetic-asset")).thenReturn(asset);
        when(subjects.resolve("synthetic-run")).thenReturn(
                new AuthorizationSubject("E", "synthetic-run", "synthetic-run", "wk", root));
        HandoffReadTool tool = new HandoffReadTool(reads, subjects, new ImageResultExternalizer(json));
        ToolResult result = tool.call(ToolInput.from(Map.of("action", "asset", "ref", "synthetic-asset")),
                ToolUseContext.of(root.toString(), "E").withCurrentRunId("synthetic-run"));
        verify(reads).asset("E", "synthetic-asset");
        return result;
    }

    private ImageResultExternalizer.ImageToolRef parseRef(ToolResult result) throws Exception {
        String body = result.content().substring("[image_ref]".length(),
                result.content().length() - "[/image_ref]".length());
        return json.readValue(body, ImageResultExternalizer.ImageToolRef.class);
    }

    private List<Message> messages(ToolResult result) {
        return List.of(new Message.UserMessage("synthetic-image-result", Instant.EPOCH,
                List.of(new ContentBlock.ToolResultBlock("synthetic-tool", result.content(), false)), null, null));
    }
}
