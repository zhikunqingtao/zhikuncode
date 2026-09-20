package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class SystemMarkerDetectionTest {

    private static final String TRUNCATED = "[content truncated by system]";
    private static final String COMPRESSED = "[content compressed by system]";

    @ParameterizedTest(name = "{0}")
    @MethodSource("markerSuffixes")
    void preservesExistingSuffixDetection(String description, String text, boolean expected) {
        assertThat(QueryEngine.isSystemMarkerTerminated(message(new ContentBlock.TextBlock(text))))
                .isEqualTo(expected);
    }

    static Stream<Arguments> markerSuffixes() {
        return Stream.of(
                Arguments.of("正文后的截断标记", "正文\n..." + TRUNCATED, true),
                Arguments.of("纯压缩标记", COMPRESSED, true),
                Arguments.of("ASCII 大小写不敏感", "正文...[CONTENT TRUNCATED BY SYSTEM]", true),
                Arguments.of("连续标记与 ASCII 空白", "正文... \t" + TRUNCATED + "\n" + COMPRESSED + " \t\r\n\f\u000B", true),
                Arguments.of("中间引用后还有正文", "引用 " + TRUNCATED + " 后继续说明", false),
                Arguments.of("标记内部不放宽空格", "正文[content  truncated by system]", false),
                // Java 默认正则的 $ 允许末尾单个 Unicode 换行符，但 \\s 仅匹配 ASCII 空白。
                Arguments.of("末尾单个 NEL", "正文" + TRUNCATED + "\u0085", true),
                Arguments.of("末尾单个行分隔符", "正文" + TRUNCATED + "\n\u2028", true),
                Arguments.of("末尾单个段分隔符", "正文" + TRUNCATED + "\u2029", true),
                Arguments.of("多个 Unicode 换行符不接受", "正文" + TRUNCATED + "\u2028\u2029", false),
                Arguments.of("Unicode 换行符后还有空格不接受", "正文" + TRUNCATED + "\u0085 ", false),
                Arguments.of("非 ASCII 空格不接受", "正文" + TRUNCATED + "\u00A0", false));
    }

    @Test
    void checksAnyTextBlockButIgnoresThinkingAndEmptyContent() {
        assertThat(QueryEngine.isSystemMarkerTerminated(message(
                new ContentBlock.TextBlock("正文" + TRUNCATED),
                new ContentBlock.TextBlock("后续块")))).isTrue();
        assertThat(QueryEngine.isSystemMarkerTerminated(message(
                new ContentBlock.ThinkingBlock(TRUNCATED),
                new ContentBlock.TextBlock("正常正文")))).isFalse();
        assertThat(QueryEngine.isSystemMarkerTerminated(message(
                new ContentBlock.TextBlock(null),
                new ContentBlock.TextBlock(" \t\r\n")))).isFalse();
        assertThat(QueryEngine.isSystemMarkerTerminated(message())).isFalse();
        assertThat(QueryEngine.isSystemMarkerTerminated(
                new Message.AssistantMessage("test", Instant.EPOCH, null, "end_turn", null))).isFalse();
        assertThat(QueryEngine.isSystemMarkerTerminated(null)).isFalse();
    }

    @Test
    void handlesLongTrailingWhitespaceWithoutBacktracking() {
        String spaces = " ".repeat(100_000);
        assertTimeout(Duration.ofSeconds(2), () -> {
            assertThat(QueryEngine.isSystemMarkerTerminated(message(
                    new ContentBlock.TextBlock("正文" + spaces)))).isFalse();
            assertThat(QueryEngine.isSystemMarkerTerminated(message(
                    new ContentBlock.TextBlock("正文" + TRUNCATED + spaces)))).isTrue();
        });
    }

    @Test
    void handlesRepeatedMarkersWithoutStackOverflow() {
        String text = "正文" + ("..." + TRUNCATED).repeat(2_500);
        assertTimeout(Duration.ofSeconds(2), () -> {
            assertThat(QueryEngine.isSystemMarkerTerminated(message(new ContentBlock.TextBlock(text))))
                    .isTrue();
            assertThat(QueryEngine.isSystemMarkerTerminated(message(new ContentBlock.TextBlock(text + "继续正文"))))
                    .isFalse();
        });
    }

    private static Message.AssistantMessage message(ContentBlock... blocks) {
        return new Message.AssistantMessage("test", Instant.EPOCH, List.of(blocks), "end_turn", null);
    }
}
