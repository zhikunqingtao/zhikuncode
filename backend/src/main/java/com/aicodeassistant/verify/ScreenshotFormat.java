package com.aicodeassistant.verify;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Detects supported screenshot containers without decoding their full pixel buffers.
 * Header/trailer and ImageIO dimension checks reject obvious truncation or forged signatures;
 * a positive result is not a guarantee that every compressed pixel can be decoded.
 */
public final class ScreenshotFormat {
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final byte[] PNG_END = {0, 0, 0, 0, 73, 69, 78, 68, (byte) 174, 66, 96, (byte) 130};

    private ScreenshotFormat() { }

    /** Returns image/jpeg or image/png, or null for unsupported/invalid image headers. */
    public static String detectMime(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return null;
        String expected;
        if (bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8
                && bytes[bytes.length - 2] == (byte) 0xff && bytes[bytes.length - 1] == (byte) 0xd9) {
            expected = "JPEG";
        } else if (bytes.length >= 45
                && Arrays.equals(bytes, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)
                && Arrays.equals(bytes, bytes.length - PNG_END.length, bytes.length,
                        PNG_END, 0, PNG_END.length)) {
            expected = "PNG";
        } else {
            return null;
        }
        // Explicit in-memory input avoids ImageIO's default disk cache and pixel-sized allocation.
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                // Optional PNG text/profile metadata may expand compressed input. Only inspect
                // format/dimensions; never request getImageMetadata() or decode pixel buffers here.
                reader.setInput(input, true, true);
                if (!expected.equalsIgnoreCase(reader.getFormatName())
                        || reader.getWidth(0) <= 0 || reader.getHeight(0) <= 0) return null;
                return "JPEG".equals(expected) ? "image/jpeg" : "image/png";
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException invalidImage) {
            return null;
        }
    }
}
