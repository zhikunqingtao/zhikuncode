package com.aicodeassistant.tool.impl;

import com.aicodeassistant.authorization.AuthorizationSubject;
import com.aicodeassistant.authorization.AuthorizationSubjectResolver;
import com.aicodeassistant.engine.ImageRefInjector;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.session.merge.HandoffReadService;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolUseContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HandoffReadToolTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private final ImageResultExternalizer images = spy(new ImageResultExternalizer(json));
    private HandoffReadTool tool;
    private Path asset;

    @BeforeEach
    void setup() throws Exception {
        asset = root.resolve("a_synthetic_png_without_extension");
        var reads = mock(HandoffReadService.class);
        when(reads.asset("E", "synthetic-asset")).thenReturn(asset);
        var subjects = mock(AuthorizationSubjectResolver.class);
        when(subjects.resolve("synthetic-run")).thenReturn(
                new AuthorizationSubject("E", "synthetic-run", "synthetic-run", "wk", root));
        tool = new HandoffReadTool(reads, subjects, images);
    }

    @ParameterizedTest
    @ValueSource(ints = {1_124_999, 1_125_000})
    void pngWithinTheBoundaryStillReachesTheImageInjector(int bytes) throws Exception {
        writeValidPng(bytes);
        var result = tool.call(ToolInput.from(Map.of("action", "asset", "ref", "synthetic-asset")),
                ToolUseContext.of(root.toString(), "E").withCurrentRunId("synthetic-run"));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("[image_ref]");
        var injected = inject(result.content());
        assertThat(injected.pendingHashes()).hasSize(1);
        assertThat(((Message.UserMessage) injected.messages().getFirst()).content())
                .anyMatch(block -> block instanceof ContentBlock.ImageBlock);
    }

    @ParameterizedTest
    @ValueSource(ints = {1_125_001, 12 * 1024 * 1024})
    void oversizedPngIsExplicitlyUnreadAndItsOriginalIsPreserved(int bytes) throws Exception {
        writeValidPng(bytes);
        byte[] originalHash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(asset));
        var result = tool.call(ToolInput.from(Map.of("action", "asset", "ref", "synthetic-asset")),
                ToolUseContext.of(root.toString(), "E").withCurrentRunId("synthetic-run"));

        assertThat(result.isError()).isTrue();
        assertThat(result.failureCode()).isEqualTo("HANDOFF_ASSET_TOO_LARGE");
        assertThat(result.content()).contains("本次未读取图片内容", "原件已保留")
                .doesNotContain("[image_ref]");
        verify(images, never()).externalize(any(), anyString(), anyLong());
        assertThat(Files.size(asset)).isEqualTo(bytes);
        assertThat(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(asset)))
                .isEqualTo(originalHash);

        // The old successful reference cannot pass the real injector, even with ample request budget.
        String previousReference = new ImageResultExternalizer(json).externalize(asset, "image/png", bytes);
        var injected = inject(previousReference);
        assertThat(injected.pendingHashes()).isEmpty();
        assertThat(((Message.UserMessage) injected.messages().getFirst()).content())
                .noneMatch(block -> block instanceof ContentBlock.ImageBlock);
    }

    private ImageRefInjector.InjectResult inject(String content) {
        var security = mock(PathSecurityService.class);
        when(security.checkReadPermission(anyString(), anyString()))
                .thenReturn(PathSecurityService.PathCheckResult.allowed());
        var message = new Message.UserMessage("image", Instant.EPOCH,
                List.of(new ContentBlock.ToolResultBlock("tool", content, false)), null, null);
        return new ImageRefInjector(json, security).injectForApiCall(List.of(message), 0,
                100_000_000, Set.of(), new HashMap<>(), root.toString(), 1);
    }

    private void writeValidPng(int bytes) throws Exception {
        var small = new ByteArrayOutputStream();
        assertThat(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", small)).isTrue();
        byte[] png = small.toByteArray();
        // Insert a valid tEXt chunk before IEND, with a CRC, to exercise exact byte boundaries.
        // This remains a decodable PNG; no trailing junk or falsified image-ref size is used.
        byte[] text = new byte[bytes - png.length - 12];
        Arrays.fill(text, (byte) 'a');
        byte[] keyword = "Comment\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(keyword, 0, text, 0, keyword.length);
        byte[] type = "tEXt".getBytes(StandardCharsets.US_ASCII);
        var crc = new CRC32();
        crc.update(type);
        crc.update(text);
        try (var output = new DataOutputStream(Files.newOutputStream(asset))) {
            output.write(png, 0, png.length - 12);
            output.writeInt(text.length);
            output.write(type);
            output.write(text);
            output.writeInt((int) crc.getValue());
            output.write(png, png.length - 12, 12);
        }
        assertThat(Files.size(asset)).isEqualTo(bytes);
        assertThat(ImageIO.read(asset.toFile()).getWidth()).isEqualTo(2);
    }
}
