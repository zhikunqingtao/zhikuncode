package com.aicodeassistant.engine;

import com.aicodeassistant.llm.MessageParam;
import com.aicodeassistant.llm.MessageParam.ContentPart;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MessageNormalizer 测试 — 覆盖 fromMessage() + normalizeTyped() 全路径。
 */
class MessageNormalizerTest {

    private final MessageNormalizer normalizer = new MessageNormalizer();

    // ═══════════════ fromMessage() ═══════════════

    @Nested
    @DisplayName("fromMessage() 单条转换")
    class FromMessageTests {

        @Test
        @DisplayName("UserMessage → UserParam")
        void userMessage_convertsToUserParam() {
            Message.UserMessage user = new Message.UserMessage(
                    UUID.randomUUID().toString(), Instant.now(),
                    List.of(new ContentBlock.TextBlock("Hello")),
                    null, null);

            MessageParam result = MessageNormalizer.fromMessage(user);

            assertThat(result).isInstanceOf(MessageParam.UserParam.class);
            MessageParam.UserParam up = (MessageParam.UserParam) result;
            assertThat(up.content()).hasSize(1);
            assertThat(up.content().getFirst()).isInstanceOf(ContentPart.TextPart.class);
            assertThat(((ContentPart.TextPart) up.content().getFirst()).text()).isEqualTo("Hello");
        }

        @Test
        @DisplayName("AssistantMessage → AssistantParam")
        void assistantMessage_convertsToAssistantParam() {
            Message.AssistantMessage assistant = new Message.AssistantMessage(
                    UUID.randomUUID().toString(), Instant.now(),
                    List.of(new ContentBlock.TextBlock("Hi there")),
                    "end_turn", null);

            MessageParam result = MessageNormalizer.fromMessage(assistant);

            assertThat(result).isInstanceOf(MessageParam.AssistantParam.class);
            MessageParam.AssistantParam ap = (MessageParam.AssistantParam) result;
            assertThat(ap.content()).hasSize(1);
            assertThat(((ContentPart.TextPart) ap.content().getFirst()).text()).isEqualTo("Hi there");
        }

        @Test
        @DisplayName("SystemMessage → null")
        void systemMessage_returnsNull() {
            Message.SystemMessage sys = new Message.SystemMessage(
                    UUID.randomUUID().toString(), Instant.now(),
                    "System info", null);

            assertThat(MessageNormalizer.fromMessage(sys)).isNull();
        }

        @Test
        @DisplayName("ToolUseBlock 正确转换")
        void toolUseBlock_convertedCorrectly() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = mapper.createObjectNode();
            input.put("path", "/test.txt");

            Message.AssistantMessage msg = new Message.AssistantMessage(
                    UUID.randomUUID().toString(), Instant.now(),
                    List.of(new ContentBlock.ToolUseBlock("tu-1", "read_file", input)),
                    "tool_use", null);

            MessageParam result = MessageNormalizer.fromMessage(msg);
            assertThat(result).isInstanceOf(MessageParam.AssistantParam.class);
            MessageParam.AssistantParam ap = (MessageParam.AssistantParam) result;
            assertThat(ap.content()).hasSize(1);
            assertThat(ap.content().getFirst()).isInstanceOf(ContentPart.ToolUsePart.class);
            ContentPart.ToolUsePart tup = (ContentPart.ToolUsePart) ap.content().getFirst();
            assertThat(tup.id()).isEqualTo("tu-1");
            assertThat(tup.name()).isEqualTo("read_file");
            assertThat(tup.input()).containsEntry("path", "/test.txt");
        }

        @Test
        @DisplayName("ToolResultBlock 正确转换")
        void toolResultBlock_convertedCorrectly() {
            Message.UserMessage msg = new Message.UserMessage(
                    UUID.randomUUID().toString(), Instant.now(),
                    List.of(new ContentBlock.ToolResultBlock("tu-1", "file content", false)),
                    null, null);

            MessageParam result = MessageNormalizer.fromMessage(msg);
            assertThat(result).isInstanceOf(MessageParam.UserParam.class);
            MessageParam.UserParam up = (MessageParam.UserParam) result;
            assertThat(up.content()).hasSize(1);
            ContentPart.ToolResultPart trp = (ContentPart.ToolResultPart) up.content().getFirst();
            assertThat(trp.toolUseId()).isEqualTo("tu-1");
            assertThat(trp.content()).isEqualTo("file content");
            assertThat(trp.isError()).isFalse();
        }
    }

    // ═══════════════ normalizeTyped() ═══════════════

    @Nested
    @DisplayName("normalizeTyped() 管线")
    class NormalizeTypedTests {

        @Test
        @DisplayName("过滤 SystemMessage")
        void filtersSystemMessages() {
            List<Message> messages = List.of(
                    new Message.SystemMessage("s1", Instant.now(), "sys", null),
                    new Message.UserMessage("u1", Instant.now(),
                            List.of(new ContentBlock.TextBlock("hi")), null, null)
            );

            List<MessageParam> result = normalizer.normalizeTyped(messages);
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().role()).isEqualTo("user");
        }

        @Test
        @DisplayName("合并连续 user 消息")
        void mergesConsecutiveUserMessages() {
            List<Message> messages = List.of(
                    new Message.UserMessage("u1", Instant.now(),
                            List.of(new ContentBlock.TextBlock("hello")), null, null),
                    new Message.UserMessage("u2", Instant.now(),
                            List.of(new ContentBlock.TextBlock("world")), null, null)
            );

            List<MessageParam> result = normalizer.normalizeTyped(messages);
            assertThat(result).hasSize(1);
            MessageParam.UserParam up = (MessageParam.UserParam) result.getFirst();
            assertThat(up.content()).hasSize(2);
        }

        @Test
        @DisplayName("过滤空内容 assistant 消息")
        void filtersEmptyAssistant() {
            List<Message> messages = List.of(
                    new Message.UserMessage("u1", Instant.now(),
                            List.of(new ContentBlock.TextBlock("hi")), null, null),
                    new Message.AssistantMessage("a1", Instant.now(),
                            List.of(new ContentBlock.TextBlock("")), "end_turn", null)
            );

            List<MessageParam> result = normalizer.normalizeTyped(messages);
            assertThat(result).hasSize(1); // only user message
        }

        @Test
        @DisplayName("orphan thinking-only assistant 被过滤")
        void filtersOrphanThinkingOnly() {
            List<Message> messages = List.of(
                    new Message.UserMessage("u1", Instant.now(),
                            List.of(new ContentBlock.TextBlock("hi")), null, null),
                    new Message.AssistantMessage("a1", Instant.now(),
                            List.of(new ContentBlock.ThinkingBlock("hmm...")),
                            "end_turn", null),
                    new Message.AssistantMessage("a2", Instant.now(),
                            List.of(new ContentBlock.TextBlock("response")),
                            "end_turn", null)
            );

            List<MessageParam> result = normalizer.normalizeTyped(messages);
            // a1 (thinking-only) should be filtered
            assertThat(result).hasSize(2); // user + a2
        }
    }
}
