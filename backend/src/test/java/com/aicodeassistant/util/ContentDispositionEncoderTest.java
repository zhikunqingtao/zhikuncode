package com.aicodeassistant.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ContentDispositionEncoderTest {

    @Test
    void encodesChineseNamesWithAsciiFallbackAndUtf8PercentEncoding() {
        String header = ContentDispositionEncoder.header("attachment", "报告.pdf", "file");

        assertThat(header).isEqualTo(
                "attachment; filename=\"??.pdf\"; filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf");
    }

    @Test
    void keepsAsciiNamesAndPercentEncodesSpecialCharacters() {
        assertThat(ContentDispositionEncoder.header("inline", "plain-report.txt", "file"))
                .isEqualTo("inline; filename=\"plain-report.txt\";"
                        + " filename*=UTF-8''plain-report.txt");

        assertThat(ContentDispositionEncoder.header("attachment", "50% \"quoted\".txt", "file"))
                .isEqualTo("attachment; filename=\"50% \\\"quoted\\\".txt\";"
                        + " filename*=UTF-8''50%25%20%22quoted%22.txt");
    }

    @Test
    void preservesRfc5987AttrCharsAndEncodesTheRest() {
        String header = ContentDispositionEncoder.header("attachment",
                "a!b#c$d&e+f-g.h^i_j`k|l~m*'.txt", "file");

        assertThat(header).isEqualTo("attachment;"
                + " filename=\"a!b#c$d&e+f-g.h^i_j`k|l~m*'.txt\";"
                + " filename*=UTF-8''a!b#c$d&e+f-g.h^i_j`k|l~m%2A%27.txt");
    }

    @Test
    void encodesEmojiWithoutSplittingTheSurrogatePair() {
        String header = ContentDispositionEncoder.header("attachment", "😀.txt", "file");

        assertThat(header).isEqualTo(
                "attachment; filename=\"?.txt\"; filename*=UTF-8''%F0%9F%98%80.txt");
    }

    @Test
    void stripsControlCharactersAndPreventsCrlfInjection() {
        String header = ContentDispositionEncoder.header("attachment",
                "evil\r\nX-Injected: yes\u0000.txt", "file");

        assertThat(header).doesNotContain("\r").doesNotContain("\n");
        assertThat(header).isEqualTo("attachment; filename=\"evilX-Injected: yes.txt\";"
                + " filename*=UTF-8''evilX-Injected%3A%20yes.txt");
    }

    @ParameterizedTest
    @ValueSource(ints = {0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C,
            0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069})
    void removesBidiControlsFromBothFilenameFormsAndFallback(int codePoint) {
        String name = "gpj" + new String(Character.toChars(codePoint)) + "exe.txt";

        assertThat(ContentDispositionEncoder.header("attachment", name, "file"))
                .isEqualTo("attachment; filename=\"gpjexe.txt\"; filename*=UTF-8''gpjexe.txt");
        assertThat(ContentDispositionEncoder.header("inline", null, name))
                .isEqualTo("inline; filename=\"gpjexe.txt\"; filename*=UTF-8''gpjexe.txt");
    }

    @Test
    void fallsBackWhenBidiControlsAreTheEntireName() {
        String controls = "\u061C\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069";

        assertThat(ContentDispositionEncoder.header("attachment", controls, "safe.txt"))
                .isEqualTo("attachment; filename=\"safe.txt\"; filename*=UTF-8''safe.txt");
        assertThat(ContentDispositionEncoder.header("attachment", controls, controls))
                .isEqualTo("attachment; filename=\"file\"; filename*=UTF-8''file");
    }

    @ParameterizedTest
    @ValueSource(strings = {"中文报告.txt", "مرحبا שלום.txt", "👩\u200D💻.txt", "✈\uFE0F.txt",
            "می\u200Cروم.txt", "word\u2060join.txt"})
    void preservesLegitimateTextAndNonBidiFormatting(String name) {
        assertThat(ContentDispositionEncoder.sanitizeDisplayName(name, "file")).isEqualTo(name);
        String header = ContentDispositionEncoder.header("inline", name, "file");
        String encodedName = header.substring(header.indexOf("filename*=UTF-8''")
                + "filename*=UTF-8''".length());

        assertThat(header).startsWith("inline; filename=\"");
        assertThat(URLDecoder.decode(encodedName, StandardCharsets.UTF_8)).isEqualTo(name);
    }

    @Test
    void sanitizesDispositionTypeAgainstHeaderInjection() {
        String header = ContentDispositionEncoder.header("inline\r\nX-Evil: 1", "a.txt", "file");

        assertThat(header).doesNotContain("\r").doesNotContain("\n");
        assertThat(header).startsWith("inlineX-Evil: 1; filename=");
    }

    @Test
    void extractsBasenameFromUnixAndWindowsPaths() {
        assertThat(ContentDispositionEncoder.sanitizeDisplayName("../../etc/passwd", "file"))
                .isEqualTo("passwd");
        assertThat(ContentDispositionEncoder.sanitizeDisplayName("C:\\temp\\报告 终稿.txt", "file"))
                .isEqualTo("报告 终稿.txt");
        assertThat(ContentDispositionEncoder.sanitizeDisplayName("/leading/slash/报告.pdf", "file"))
                .isEqualTo("报告.pdf");
    }

    @Test
    void fallsBackToTheProvidedNameWhenTheRawNameIsEmpty() {
        assertThat(ContentDispositionEncoder.sanitizeDisplayName(null, "download"))
                .isEqualTo("download");
        assertThat(ContentDispositionEncoder.sanitizeDisplayName("   ", "download"))
                .isEqualTo("download");
        assertThat(ContentDispositionEncoder.sanitizeDisplayName("trailing/", "download"))
                .isEqualTo("download");
        assertThat(ContentDispositionEncoder.sanitizeDisplayName("\u0001\u0002", "download"))
                .isEqualTo("download");
    }

    @Test
    void fallsBackToLiteralFileWhenBothNamesAreEmpty() {
        assertThat(ContentDispositionEncoder.sanitizeDisplayName(null, null)).isEqualTo("file");
        assertThat(ContentDispositionEncoder.sanitizeDisplayName("", "   ")).isEqualTo("file");
        assertThat(ContentDispositionEncoder.header("attachment", "\u0000", null))
                .isEqualTo("attachment; filename=\"file\"; filename*=UTF-8''file");
    }

    @Test
    void truncatesTo200Utf8BytesWithoutCuttingCharacters() {
        String chinese = ContentDispositionEncoder.sanitizeDisplayName("汉".repeat(100), "file");
        assertThat(chinese).isEqualTo("汉".repeat(66));
        assertThat(chinese.getBytes(StandardCharsets.UTF_8)).hasSize(198);

        String emoji = ContentDispositionEncoder.sanitizeDisplayName("😀".repeat(100), "file");
        assertThat(emoji).isEqualTo("😀".repeat(50));
        assertThat(emoji.getBytes(StandardCharsets.UTF_8)).hasSize(200);
        assertThat(emoji.codePointCount(0, emoji.length())).isEqualTo(50);

        String mixed = ContentDispositionEncoder.sanitizeDisplayName(
                "a".repeat(199) + "汉", "file");
        assertThat(mixed).isEqualTo("a".repeat(199));
    }

    @Test
    void preservesOfficeExtensionsInBothFilenameFormsForLongChineseNames() {
        for (String extension : new String[]{".docx", ".xlsx", ".pptx"}) {
            String name = "汉".repeat(70) + extension;
            String displayName = ContentDispositionEncoder.sanitizeDisplayName(name, "file");

            assertThat(displayName).isEqualTo("汉".repeat(65) + extension);
            assertThat(displayName.getBytes(StandardCharsets.UTF_8)).hasSize(200);
            assertThat(ContentDispositionEncoder.header("attachment", name, "file"))
                    .isEqualTo("attachment; filename=\"" + "?".repeat(65) + extension
                            + "\"; filename*=UTF-8''" + "%E6%B1%89".repeat(65) + extension);
        }
    }

    @Test
    void preservesOfficeExtensionsInBothFilenameFormsForLongEmojiNames() {
        for (String extension : new String[]{".docx", ".xlsx", ".pptx"}) {
            String name = "😀".repeat(50) + extension;
            String displayName = ContentDispositionEncoder.sanitizeDisplayName(name, "file");

            assertThat(displayName).isEqualTo("😀".repeat(48) + extension);
            assertThat(displayName.getBytes(StandardCharsets.UTF_8)).hasSize(197);
            assertThat(ContentDispositionEncoder.header("attachment", name, "file"))
                    .isEqualTo("attachment; filename=\"" + "?".repeat(48) + extension
                            + "\"; filename*=UTF-8''" + "%F0%9F%98%80".repeat(48) + extension);
        }
    }

    @Test
    void retainsOrdinaryTruncationForUnsafeOrOverlongSuffixes() {
        for (String extension : new String[]{".bad-name", "." + "x".repeat(17)}) {
            assertThat(ContentDispositionEncoder.sanitizeDisplayName("汉".repeat(70) + extension, "file"))
                    .isEqualTo("汉".repeat(66));
        }
    }

    @Test
    void doesNotTruncateNamesAtOrBelowTheLimit() {
        String exactly200Bytes = "汉".repeat(66) + "ab";
        assertThat(ContentDispositionEncoder.sanitizeDisplayName(exactly200Bytes, "file"))
                .isEqualTo(exactly200Bytes);
    }

    @Test
    void headerAlwaysCarriesBothAsciiAndUtf8Forms() {
        String header = ContentDispositionEncoder.header("inline", " 目录/文件 名.txt ", "file");

        assertThat(header).startsWith("inline; ");
        assertThat(header).contains("filename=\"");
        assertThat(header).contains("filename*=UTF-8''");
        assertThat(header).contains("filename*=UTF-8''%E6%96%87%E4%BB%B6%20%E5%90%8D.txt");
        assertThat(header).contains("filename=\"?? ?.txt\"");
    }
}
