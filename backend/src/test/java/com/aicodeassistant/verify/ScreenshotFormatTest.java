package com.aicodeassistant.verify;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenshotFormatTest {
    static byte[] image(String format) throws IOException {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x12ab34);
        try (var output = new ByteArrayOutputStream()) {
            assertThat(ImageIO.write(image, format, output)).isTrue();
            return output.toByteArray();
        }
    }

    @Test
    void detectsRealPngAndJpegWithoutChangingBytes() throws Exception {
        byte[] jpeg = image("jpeg");
        byte[] png = image("png");
        assertThat(ScreenshotFormat.detectMime(jpeg)).isEqualTo("image/jpeg");
        assertThat(ScreenshotFormat.detectMime(png)).isEqualTo("image/png");
        assertThat(jpeg).containsExactly(image("jpeg"));
        assertThat(png).containsExactly(image("png"));
    }

    @Test
    void rejectsMissingTrailersAndUnsupportedImages() throws Exception {
        for (String format : new String[]{"jpeg", "png"}) {
            byte[] image = image(format);
            assertThat(ScreenshotFormat.detectMime(Arrays.copyOf(image, image.length - 1))).isNull();
        }
        assertThat(ScreenshotFormat.detectMime(image("gif"))).isNull();
        assertThat(ScreenshotFormat.detectMime("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes())).isNull();
        assertThat(ScreenshotFormat.detectMime(null)).isNull();
        assertThat(ScreenshotFormat.detectMime(new byte[0])).isNull();
    }

    @Test
    void rejectsSignaturesAndTrailersWithoutImageMetadata() throws Exception {
        byte[] forgedPng = new byte[60];
        byte[] png = image("png");
        System.arraycopy(png, 0, forgedPng, 0, 8);
        System.arraycopy(png, png.length - 12, forgedPng, forgedPng.length - 12, 12);
        assertThat(ScreenshotFormat.detectMime(forgedPng)).isNull();
        assertThat(ScreenshotFormat.detectMime(new byte[]{(byte) 0xff, (byte) 0xd8, 1, 2,
                (byte) 0xff, (byte) 0xd9})).isNull();
    }

    @Test
    void doesNotInflateOptionalPngTextMetadata() throws Exception {
        byte[] png = image("png");
        // A valid PNG image with an irrelevant text chunk containing a broken zlib stream.
        // Inflating ancillary metadata would fail; MIME detection only needs the image header.
        byte[] chunkTypeAndData = {'z', 'T', 'X', 't', 'k', 0, 0, 1, 2, 3};
        CRC32 crc = new CRC32();
        crc.update(chunkTypeAndData);
        try (var buffer = new ByteArrayOutputStream(); var output = new DataOutputStream(buffer)) {
            output.write(png, 0, 33); // PNG signature plus IHDR chunk.
            output.writeInt(chunkTypeAndData.length - 4);
            output.write(chunkTypeAndData);
            output.writeInt((int) crc.getValue());
            output.write(png, 33, png.length - 33);
            assertThat(ScreenshotFormat.detectMime(buffer.toByteArray())).isEqualTo("image/png");
        }
    }
}
