package com.aicodeassistant.llm;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.util.Base64;

/** Shared, bounded estimate for inline raster images, independent of their wire encoding.
 * This is a local admission estimate, not a provider billing/tokenization formula.
 * Unknown encodings and animations retain the previous conservative byte-based estimate.
 */
public final class InlineImageBudget {
    public static final long MAX_PIXELS = 40_000_000L;
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_ENCODED = ((MAX_BYTES + 2) / 3) * 4;

    private InlineImageBudget() {}

    /** Recognize image parts structurally; ordinary text/data and documents are not exempted. */
    public static Integer estimatePart(Object part) {
        String type = string(field(part, "type"));
        String encoded = null;
        if ("image".equals(type)) {
            Object source = field(part, "source");
            if ("base64".equals(string(field(source, "type")))
                    && string(field(source, "media_type")).startsWith("image/"))
                encoded = string(field(source, "data"));
        } else if ("image_url".equals(type) || "input_image".equals(type)) {
            Object imageUrl = field(part, "image_url");
            String url = "image_url".equals(type) ? string(field(imageUrl, "url")) : string(imageUrl);
            int comma = url.indexOf(',');
            if (url.startsWith("data:image/") && comma > 0 && url.substring(0, comma).endsWith(";base64"))
                encoded = url.substring(comma + 1);
        }
        return encoded == null ? null : estimate(encoded) + 16;
    }

    private static Object field(Object value, String name) {
        if (value instanceof java.util.Map<?, ?> map) return map.get(name);
        if (value instanceof com.fasterxml.jackson.databind.JsonNode node) return node.get(name);
        return null;
    }

    private static String string(Object value) {
        if (value instanceof String text) return text;
        if (value instanceof com.fasterxml.jackson.databind.JsonNode node && node.isTextual()) return node.textValue();
        return "";
    }

    public static int estimate(String encoded) {
        if (encoded == null || encoded.isEmpty()) return 0;
        if (encoded.length() > MAX_ENCODED) throw invalid("图片超过 10 MiB 安全限制");
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            int[] size = dimensions(bytes);
            if (size == null || isAnimated(bytes)) return encoded.length();
            // Reserve one token per 16x16 patch plus 1024 tokens of overhead.
            // Deliberately more conservative than the existing Claude pixels/750 estimate.
            return Math.toIntExact(1024L + ((size[0] + 15L) / 16) * ((size[1] + 15L) / 16));
        } catch (IllegalArgumentException invalidEncoding) {
            return encoded.length();
        }
    }

    /** Reads headers only; never allocates a decoded raster. null means an unreadable header. */
    public static int[] dimensions(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw invalid("图片超过 10 MiB 安全限制");
        if (bytes.length >= 30 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            if (bytes[12] != 'V' || bytes[13] != 'P' || bytes[14] != '8') return null;
            if (bytes[15] == 'X') {
                return checked(1 + le24(bytes, 24), 1 + le24(bytes, 27));
            }
            if (bytes[15] == 'L' && bytes[20] == 0x2f) {
                return checked(1 + ((bytes[21] & 255) | (bytes[22] & 63) << 8),
                        1 + ((bytes[22] & 255) >> 6 | (bytes[23] & 255) << 2 | (bytes[24] & 15) << 10));
            }
            if (bytes[15] == ' ' && bytes[23] == (byte) 0x9d && bytes[24] == 1 && bytes[25] == 0x2a) {
                return checked(((bytes[26] & 255) | (bytes[27] & 255) << 8) & 0x3fff,
                        ((bytes[28] & 255) | (bytes[29] & 255) << 8) & 0x3fff);
            }
            return null;
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return checked(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (java.io.IOException | IllegalArgumentException malformed) {
            return null;
        }
    }

    private static boolean isAnimated(byte[] bytes) {
        // GIF and animated WebP/PNG may be processed as video. Do not estimate them as one frame.
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return true;
        if (bytes.length >= 30 && bytes[0] == 'R' && bytes[8] == 'W' && bytes[15] == 'X'
                && (bytes[20] & 2) != 0) return true;
        if (bytes.length > 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P') {
            for (int p = 8; p + 12 <= bytes.length;) {
                long length = ((bytes[p] & 255L) << 24) | ((bytes[p + 1] & 255L) << 16)
                        | ((bytes[p + 2] & 255L) << 8) | (bytes[p + 3] & 255L);
                if (bytes[p + 4] == 'I' && bytes[p + 5] == 'E' && bytes[p + 6] == 'N' && bytes[p + 7] == 'D') break;
                if (bytes[p + 4] == 'a' && bytes[p + 5] == 'c' && bytes[p + 6] == 'T' && bytes[p + 7] == 'L') return true;
                if (length > bytes.length - p - 12L) break;
                p += (int) length + 12;
            }
        }
        return false;
    }

    private static int le24(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | (bytes[offset + 1] & 255) << 8 | (bytes[offset + 2] & 255) << 16;
    }

    private static int[] checked(int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS)
            throw invalid("图片像素超过安全限制");
        return new int[]{width, height};
    }

    private static LlmApiException invalid(String reason) {
        return new LlmApiException(reason, false, 0, "IMAGE_INPUT_INVALID", 0);
    }
}
