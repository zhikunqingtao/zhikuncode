package com.aicodeassistant.llm;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class InlineImageBudgetTest {
    @Test void readsWebpCanvasWithoutDecodingAndDoesNotTreatAnimationAsOneFrame() {
        byte[] bytes = new byte[30];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        System.arraycopy("WEBPVP8X".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 8);
        bytes[24] = 127; bytes[27] = 63;
        assertThat(InlineImageBudget.dimensions(bytes)).containsExactly(128, 64);
        bytes[20] = 2;
        String encoded = Base64.getEncoder().encodeToString(bytes);
        assertThat(InlineImageBudget.dimensions(bytes)).containsExactly(128, 64);
        assertThat(InlineImageBudget.estimate(encoded)).isEqualTo(encoded.length());
    }
    @Test void pixelBombIsRejectedFromHeaderBeforeRasterAllocation() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output);
        byte[] bytes = output.toByteArray();
        ByteBuffer.wrap(bytes).putInt(16, 100_000).putInt(20, 100_000);
        assertThatThrownBy(() -> InlineImageBudget.dimensions(bytes))
                .isInstanceOf(LlmApiException.class).hasMessageContaining("像素");
    }
    @Test void malformedOrUnknownEncodingIsNeverAssignedASmallFixedEstimate() {
        String encoded = Base64.getEncoder().encodeToString(new byte[10000]);
        assertThat(InlineImageBudget.estimate(encoded)).isEqualTo(encoded.length());
        assertThat(InlineImageBudget.estimate("not-valid-base64!")).isEqualTo(17);
    }
}
