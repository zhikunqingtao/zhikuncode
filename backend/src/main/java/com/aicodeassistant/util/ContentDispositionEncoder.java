package com.aicodeassistant.util;

import java.nio.charset.StandardCharsets;

/**
 * 无状态 {@code Content-Disposition} 头编码器。
 *
 * <p><b>职责边界：仅负责 header 编码</b>——把显示名同时编码为 ASCII 回退
 * {@code filename="…"} 与 RFC 5987 {@code filename*=UTF-8''…}。
 * 本类不决定 MIME 类型、不检查下载权限、不参与发布类型/策略；
 * {@code inline}/{@code attachment} 必须由调用方明确指定，下载授权由调用方保证。</p>
 *
 * <p>显示名处理：取 basename（{@code '/'}、{@code '\'} 之后）、移除 ISO 控制字符
 * （因此 CR/LF 注入不可能存活）及双向控制字符、trim、按 UTF-8 字节截断到
 * {@value #MAX_DISPLAY_NAME_BYTES} 字节且不切断任何字符（含 emoji/代理对），
 * 超长名保留最后一个安全短扩展名。</p>
 */
public final class ContentDispositionEncoder {

    /** 显示名允许的最大 UTF-8 字节数。 */
    public static final int MAX_DISPLAY_NAME_BYTES = 200;

    /** 名称与回退名都为空时的最终兜底显示名。 */
    public static final String DEFAULT_FALLBACK_NAME = "file";

    private ContentDispositionEncoder() {
        // 无状态工具类，不可实例化
    }

    /**
     * 净化下载显示名：basename → 移除 ISO/Bidi 控制字符 → trim → UTF-8 安全截断。
     *
     * @param rawName  原始名称（可为 null/空白，可含目录分隔符）
     * @param fallback 净化结果为空时使用的回退名（也经同样净化；同样为空则用 {@value #DEFAULT_FALLBACK_NAME}）
     * @return 非空、无 ISO/Bidi 控制字符、UTF-8 长度 ≤ {@value #MAX_DISPLAY_NAME_BYTES} 字节的显示名
     */
    public static String sanitizeDisplayName(String rawName, String fallback) {
        String name = clean(rawName);
        if (name.isEmpty()) {
            name = clean(fallback);
        }
        return name.isEmpty() ? DEFAULT_FALLBACK_NAME : name;
    }

    /**
     * 生成 {@code Content-Disposition} 头值：
     * {@code type; filename="<ASCII 回退>"; filename*=UTF-8''<百分号编码>}。
     *
     * <p>ASCII 回退中的非 ASCII 字符替换为 {@code '?'}，quoted-string 内的
     * {@code "} 与 {@code \} 会转义；RFC 5987 部分按 UTF-8 字节逐字节百分号编码
     * （大写 hex），仅保留 attr-char。</p>
     *
     * @param dispositionType {@code inline} 或 {@code attachment}（由调用方决定；空白时兜底 attachment）
     * @param rawName         原始显示名
     * @param fallbackName    显示名净化后为空时的回退名
     */
    public static String header(String dispositionType, String rawName, String fallbackName) {
        String type = stripControlCharacters(dispositionType == null ? "" : dispositionType).trim();
        if (type.isEmpty()) {
            type = "attachment";
        }
        String displayName = sanitizeDisplayName(rawName, fallbackName);
        return type + "; filename=\"" + asciiFallback(displayName)
                + "\"; filename*=UTF-8''" + rfc5987(displayName);
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String name = stripControlCharacters(basename(value)).trim();
        String truncated = truncateUtf8(name, MAX_DISPLAY_NAME_BYTES);
        if (!truncated.equals(name)) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                String extension = name.substring(dot);
                if (extension.matches("\\.[A-Za-z0-9]{1,16}")) {
                    return truncateUtf8(name.substring(0, dot), MAX_DISPLAY_NAME_BYTES - extension.length())
                            + extension;
                }
            }
        }
        return truncated;
    }

    /** 取路径 basename：同时识别 '/' 与 '\'（Windows 路径）分隔符。 */
    private static String basename(String value) {
        int separator = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return separator >= 0 ? value.substring(separator + 1) : value;
    }

    /** 移除 ISO 与 Bidi 控制字符；保留 ZWJ/ZWNJ 等其他格式字符和完整代码点。 */
    private static String stripControlCharacters(String value) {
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint) && !isBidiControl(codePoint))
                .forEach(out::appendCodePoint);
        return out.toString();
    }

    /** Unicode Bidi_Control 集合；不能过滤整个 FORMAT 类别，以免破坏组合 emoji 等合法名称。 */
    private static boolean isBidiControl(int codePoint) {
        return codePoint == 0x061C || codePoint == 0x200E || codePoint == 0x200F
                || (codePoint >= 0x202A && codePoint <= 0x202E)
                || (codePoint >= 0x2066 && codePoint <= 0x2069);
    }

    /** 按 UTF-8 字节数截断到上限，绝不切断代码点（含代理对/emoji）。 */
    private static String truncateUtf8(String value, int maxBytes) {
        StringBuilder out = new StringBuilder(value.length());
        int usedBytes = 0;
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            int codePointBytes = utf8Length(codePoint);
            if (usedBytes + codePointBytes > maxBytes) {
                break;
            }
            out.appendCodePoint(codePoint);
            usedBytes += codePointBytes;
            index += Character.charCount(codePoint);
        }
        return out.toString();
    }

    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }

    /** ASCII 回退：非 ASCII 一律替换为 '?'，并转义 quoted-string 内的 '"' 与 '\'。 */
    private static String asciiFallback(String value) {
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            boolean asciiPrintable = codePoint >= 0x20 && codePoint <= 0x7E;
            if (asciiPrintable) {
                char character = (char) codePoint;
                if (character == '"' || character == '\\') {
                    out.append('\\');
                }
                out.append(character);
            } else {
                out.append('?');
            }
        });
        return out.toString();
    }

    /** RFC 5987 ext-value：UTF-8 字节逐字节百分号编码（大写 hex），仅保留 attr-char。 */
    private static String rfc5987(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (byte rawByte : value.getBytes(StandardCharsets.UTF_8)) {
            int unsignedByte = rawByte & 0xFF;
            if (isAttrChar(unsignedByte)) {
                out.append((char) unsignedByte);
            } else {
                out.append('%')
                        .append(hexDigit(unsignedByte >>> 4))
                        .append(hexDigit(unsignedByte & 0xF));
            }
        }
        return out.toString();
    }

    /** RFC 5987 attr-char：ALPHA / DIGIT / !#$&+-.^_`|~（不含 %、引号、空格、'）。 */
    private static boolean isAttrChar(int character) {
        return (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '!' || character == '#' || character == '$'
                || character == '&' || character == '+' || character == '-'
                || character == '.' || character == '^' || character == '_'
                || character == '`' || character == '|' || character == '~';
    }

    private static char hexDigit(int value) {
        return Character.toUpperCase(Character.forDigit(value, 16));
    }
}
