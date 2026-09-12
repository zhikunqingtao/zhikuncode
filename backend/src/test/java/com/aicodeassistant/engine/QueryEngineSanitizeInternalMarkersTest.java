package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QueryEngine.sanitizeInternalMarkers 单元测试：
 * 验证 LLM 模仿的内部压缩标记被剥离，且不产生空 TextBlock 或空 content 消息。
 */
class QueryEngineSanitizeInternalMarkersTest {

    private static Message.AssistantMessage msgOf(ContentBlock... blocks) {
        return new Message.AssistantMessage(
                "uuid-test", Instant.EPOCH, List.of(blocks), "end_turn", null);
    }

    private static String firstText(Message.AssistantMessage msg) {
        return ((ContentBlock.TextBlock) msg.content().get(0)).text();
    }

    @Test
    void stripsSinglePrefixMarker() {
        var result = QueryEngine.sanitizeInternalMarkers(
                msgOf(new ContentBlock.TextBlock("[skeleton] 正文内容")));
        assertThat(firstText(result)).isEqualTo("正文内容");
    }

    @Test
    void stripsContentCompressedBySystemPrefix() {
        var result = QueryEngine.sanitizeInternalMarkers(
                msgOf(new ContentBlock.TextBlock("[content compressed by system] 正文内容")));
        assertThat(firstText(result)).isEqualTo("正文内容");
    }

    @Test
    void stripsConsecutiveRepeatedMarkers() {
        var result = QueryEngine.sanitizeInternalMarkers(
                msgOf(new ContentBlock.TextBlock("[skeleton] [skeleton] [skeleton] 正文内容")));
        assertThat(firstText(result)).isEqualTo("正文内容");
    }

    @Test
    void stripsMarkersAtStartOfMiddleLines() {
        var result = QueryEngine.sanitizeInternalMarkers(
                msgOf(new ContentBlock.TextBlock("第一行\n[content compressed by system]\n第三行")));
        String text = firstText(result);
        assertThat(text).doesNotContain("[content compressed by system]");
        assertThat(text).contains("第一行");
        assertThat(text).contains("第三行");
    }

    @Test
    void stripsSuffixMarker() {
        var result = QueryEngine.sanitizeInternalMarkers(
                msgOf(new ContentBlock.TextBlock("正文...[collapsed: 123 chars]")));
        assertThat(firstText(result)).isEqualTo("正文");
    }

    @Test
    void dropsEmptiedTextBlockButKeepsToolUseBlock() {
        var toolUse = new ContentBlock.ToolUseBlock(
                "tu-1", "Read", JsonNodeFactory.instance.objectNode());
        var result = QueryEngine.sanitizeInternalMarkers(
                msgOf(new ContentBlock.TextBlock("[content compressed by system]"), toolUse));
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0)).isInstanceOf(ContentBlock.ToolUseBlock.class);
        assertThat(result.content()).noneMatch(b ->
                b instanceof ContentBlock.TextBlock t && t.text().isEmpty());
    }

    @Test
    void returnsOriginalMessageWhenAllBlocksStrippedEmpty() {
        var original = msgOf(new ContentBlock.TextBlock("[content compressed by system]"));
        var result = QueryEngine.sanitizeInternalMarkers(original);
        assertThat(result).isSameAs(original);
        assertThat(result.content()).isNotEmpty();
    }

    @Test
    void returnsSameReferenceForCleanText() {
        var original = msgOf(new ContentBlock.TextBlock("正常输出，没有任何标记。"));
        var result = QueryEngine.sanitizeInternalMarkers(original);
        assertThat(result).isSameAs(original);
    }

    @Test
    void stripsCollapsedAndToolResultClearedPrefixes() {
        var collapsed = QueryEngine.sanitizeInternalMarkers(
                msgOf(new ContentBlock.TextBlock("[collapsed] 正文一")));
        assertThat(firstText(collapsed)).isEqualTo("正文一");

        var cleared = QueryEngine.sanitizeInternalMarkers(
                msgOf(new ContentBlock.TextBlock("[tool result cleared — too old] 正文二")));
        assertThat(firstText(cleared)).isEqualTo("正文二");
    }

    @Test
    void leavesLegitimateBracketsInBodyUntouched() {
        var original = msgOf(new ContentBlock.TextBlock("数组 [1, 2, 3] 说明文字"));
        var result = QueryEngine.sanitizeInternalMarkers(original);
        assertThat(result).isSameAs(original);
        assertThat(firstText(result)).isEqualTo("数组 [1, 2, 3] 说明文字");
    }
}
