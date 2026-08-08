package com.aicodeassistant.observability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Produces bounded, non-plaintext values for logs and supplemental observations. */
public final class SafeLogValue {
    private static final int MAX_HASH_CHARS = 16 * 1024;

    private SafeLogValue() {}

    public static int length(CharSequence value) {
        try {
            return value == null ? 0 : value.length();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public static String fingerprint(CharSequence value) {
        if (value == null) return "none";
        try {
            int end = Math.min(value.length(), MAX_HASH_CHARS);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.subSequence(0, end).toString().getBytes(StandardCharsets.UTF_8));
            String hash = HexFormat.of().formatHex(digest);
            return value.length() > end ? hash + ":prefix" : hash;
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    /** Fingerprints a bounded prefix of multiple values without joining the full input. */
    public static String fingerprintParts(Iterable<?> values) {
        if (values == null) return "none";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int remaining = MAX_HASH_CHARS;
            boolean truncated = false;
            java.util.Iterator<?> iterator = values.iterator();
            while (iterator.hasNext()) {
                Object value = iterator.next();
                CharSequence chars = value instanceof CharSequence sequence
                        ? sequence : value == null ? "null" : value.getClass().getSimpleName();
                int take = Math.min(chars.length(), remaining);
                if (take > 0) {
                    digest.update(chars.subSequence(0, take).toString()
                            .getBytes(StandardCharsets.UTF_8));
                    remaining -= take;
                }
                digest.update((byte) 0);
                if (take < chars.length()) truncated = true;
                if (remaining == 0) {
                    truncated = truncated || iterator.hasNext();
                    break;
                }
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            return truncated ? hash + ":prefix" : hash;
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    public static long totalLength(Iterable<?> values) {
        if (values == null) return 0L;
        try {
            long total = 0L;
            for (Object value : values) {
                if (value instanceof CharSequence chars) {
                    total = Math.addExact(total, chars.length());
                }
            }
            return total;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    public static String errorType(Throwable error) {
        return error == null ? "none" : error.getClass().getSimpleName();
    }
}
