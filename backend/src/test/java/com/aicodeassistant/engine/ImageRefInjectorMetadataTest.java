package com.aicodeassistant.engine;

import com.aicodeassistant.model.*;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.tool.impl.ImageResultExternalizer.ImageToolRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ImageRefInjectorMetadataTest {
    @TempDir Path directory;
    @Test void copyingAndImageInjectionPreserveUserAndSystemMetadata() throws Exception {
        Path image = directory.resolve("image.png");
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image)));
        ObjectMapper mapper = new ObjectMapper();
        ImageToolRef ref = new ImageToolRef(image.toString(), "image/png", Files.size(image), hash, 1, 1);
        Message.UserMessage user = new Message.UserMessage("u", Instant.now(), List.of(
                new ContentBlock.ToolResultBlock("tool", "[image_ref]" + mapper.writeValueAsString(ref) + "[/image_ref]", false)),
                null, "assistant", Map.of("steering", true));
        Message.SystemMessage system = new Message.SystemMessage("s", Instant.now(), "", SystemMessageType.INFO,
                "task_boundary", Map.of("task_id", "A"));
        PathSecurityService security = mock(PathSecurityService.class);
        when(security.checkReadPermission(image.toString(), directory.toString())).thenReturn(PathSecurityService.PathCheckResult.allowed());
        ImageRefInjector injector = new ImageRefInjector(mapper, security);
        List<Message> copied = injector.injectForApiCall(List.of(user, system), 0, 10000, Set.of(), new HashMap<>(), directory.toString(), 0).messages();
        assertThat(((Message.UserMessage) copied.getFirst()).meta()).isEqualTo(user.meta());
        assertThat(copied.get(1)).isEqualTo(system);
        List<Message> injected = injector.injectForApiCall(List.of(user, system), 0, 10000, Set.of(), directory.toString()).messages();
        Message.UserMessage rebuilt = (Message.UserMessage) injected.getFirst();
        assertThat(rebuilt.content()).hasSize(2);
        assertThat(rebuilt.content().getLast()).isInstanceOf(ContentBlock.ImageBlock.class);
        assertThat(rebuilt.meta()).isEqualTo(user.meta());
        assertThat(rebuilt.sourceToolAssistantUUID()).isEqualTo("assistant");
        assertThat(injected.get(1)).isEqualTo(system);
        assertThat(user.content()).hasSize(1);
    }
}
