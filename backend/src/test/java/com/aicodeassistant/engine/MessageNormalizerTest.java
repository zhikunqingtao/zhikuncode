package com.aicodeassistant.engine;

import com.aicodeassistant.llm.MessageParam;
import com.aicodeassistant.llm.MessageParam.ContentPart;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
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

    @Nested
    @DisplayName("压缩占位符仅在请求历史中清理")
    class CompressionReplayTests {
        private static final String MARKER = "[content compressed by system]";

        private Message.UserMessage user(String id, ContentBlock... blocks) {
            return new Message.UserMessage(id, Instant.EPOCH, List.of(blocks), null, null);
        }

        private Message.AssistantMessage assistant(ContentBlock... blocks) {
            return new Message.AssistantMessage("assistant", Instant.EPOCH, List.of(blocks), "end_turn", null);
        }

        @Test
        @DisplayName("移除无工具占位符回合及其推理，再合并相邻 user；原历史保持不变")
        @SuppressWarnings("deprecation")
        void dropsPlaceholderTurnBeforeMergingUsersWithoutMutatingHistory() throws Exception {
            var failed = assistant(new ContentBlock.ThinkingBlock("private reasoning"),
                    new ContentBlock.RedactedThinkingBlock("opaque thinking"),
                    new ContentBlock.TextBlock("  " + MARKER + "\n"),
                    new ContentBlock.TextBlock(MARKER), new ContentBlock.TextBlock("\t"));
            List<Message> history = List.of(user("u1", new ContentBlock.TextBlock("question")),
                    failed, user("u2", new ContentBlock.TextBlock("continue")));
            var mapper = new ObjectMapper().findAndRegisterModules();
            String original = mapper.writeValueAsString(history);
            var expected = List.of(new ContentPart.TextPart("question"), new ContentPart.TextPart("continue"));

            assertThat(normalizer.normalizeTyped(history))
                    .containsExactly(new MessageParam.UserParam(new ArrayList<>(expected)));
            var legacy = normalizer.normalize(history);
            assertThat(legacy).hasSize(1);
            assertThat(legacy.getFirst()).containsEntry("role", "user");
            assertThat(mapper.<JsonNode>valueToTree(legacy.getFirst().get("content")))
                    .isEqualTo(mapper.readTree("[{\"type\":\"text\",\"text\":\"question\"},{\"type\":\"text\",\"text\":\"continue\"}]"));
            assertThat(mapper.writeValueAsString(history)).isEqualTo(original);
            assertThat(history.get(1)).isSameAs(failed);
        }

        @Test
        @DisplayName("工具回合只去占位文本，保留推理、调用参数和全部结果")
        @SuppressWarnings("deprecation")
        void keepsToolCallsReasoningAndResultsIntact() throws Exception {
            var mapper = new ObjectMapper().findAndRegisterModules();
            var thinking = new ContentBlock.ThinkingBlock("tool reasoning");
            var redacted = new ContentBlock.RedactedThinkingBlock("opaque thinking");
            var firstCall = new ContentBlock.ToolUseBlock("call-1", "Read",
                    mapper.readTree("{\"path\":\"first.txt\"}"));
            var secondCall = new ContentBlock.ToolUseBlock("call-2", "Read",
                    mapper.readTree("{\"path\":\"second.txt\"}"));
            var withMarker = assistant(thinking, new ContentBlock.TextBlock(MARKER),
                    firstCall, redacted, secondCall);
            var results = user("results", new ContentBlock.ToolResultBlock("call-1", MARKER, false),
                    new ContentBlock.ToolResultBlock("call-2", "permission denied", true));
            List<Message> history = List.of(withMarker, results);
            String original = mapper.writeValueAsString(history);
            var expectedAssistant = assistant(thinking, firstCall, redacted, secondCall);

            assertThat(normalizer.normalizeTyped(history)).containsExactly(
                    MessageNormalizer.fromMessage(expectedAssistant), MessageNormalizer.fromMessage(results));
            assertThat(normalizer.normalize(history))
                    .isEqualTo(normalizer.normalize(List.of(expectedAssistant, results)));
            assertThat(mapper.writeValueAsString(history)).isEqualTo(original);
        }

        @Test
        @DisplayName("正常正文引用、代码、部分截断和其它方括号文本均保真")
        @SuppressWarnings("deprecation")
        void keepsMeaningfulAssistantTextAndOtherBracketedContent() {
            List<List<ContentBlock>> examples = List.of(
                    List.of(new ContentBlock.TextBlock("The marker " + MARKER + " means omitted history.")),
                    List.of(new ContentBlock.TextBlock("```text\n" + MARKER + "\n```")),
                    List.of(new ContentBlock.TextBlock("Useful partial answer\n...[content truncated by system]")),
                    List.of(new ContentBlock.TextBlock("[collapsed]")),
                    List.of(new ContentBlock.TextBlock(MARKER), new ContentBlock.TextBlock("Useful answer")));
            for (List<ContentBlock> blocks : examples) {
                var message = assistant(blocks.toArray(ContentBlock[]::new));
                assertThat(normalizer.normalizeTyped(List.of(message)))
                        .as("preserve assistant blocks %s", blocks)
                        .containsExactly(MessageNormalizer.fromMessage(message));
                assertThat(normalizer.normalize(List.of(message))).hasSize(1);
            }
        }

        @Test
        @DisplayName("用户原文中的同一标记不参与过滤")
        @SuppressWarnings("deprecation")
        void keepsUserPlaceholderLiteral() {
            var message = user("literal", new ContentBlock.TextBlock(MARKER));
            assertThat(normalizer.normalizeTyped(List.of(message)))
                    .containsExactly(MessageNormalizer.fromMessage(message));
            assertThat(normalizer.normalize(List.of(message))).hasSize(1);
        }

        @Test
        @DisplayName("携带 provider 私有状态或图片的助手消息不被当作空回合删除")
        @SuppressWarnings("deprecation")
        void keepsOpaqueProviderStateAndNonTextPayloads() throws Exception {
            var state = new ContentBlock.ProviderResponseStateBlock("zenmux", "model", "display",
                    List.of(new ObjectMapper().readTree("{\"type\":\"reasoning\",\"encrypted_content\":\"opaque\"}")));
            for (ContentBlock payload : List.of(state, ContentBlock.ImageBlock.fromUrl("image/png", "https://example.com/image.png"))) {
                var message = assistant(payload, new ContentBlock.ThinkingBlock("retained"),
                        new ContentBlock.TextBlock(MARKER));
                var followup = user("followup", new ContentBlock.TextBlock("continue"));
                assertThat(normalizer.normalizeTyped(List.of(message, followup))).containsExactly(
                        MessageNormalizer.fromMessage(message), MessageNormalizer.fromMessage(followup));
                var legacy = normalizer.normalize(List.of(message, followup));
                assertThat(legacy).hasSize(2);
                assertThat(new ObjectMapper().<JsonNode>valueToTree(legacy.getFirst().get("content")))
                        .isEqualTo(new ObjectMapper().valueToTree(normalizer.normalize(List.of(message)).getFirst().get("content")));
            }
        }

        @Test
        @DisplayName("实际折叠生成的占位符在标准化时清理，原消息仍可供审计")
        void cleansPlaceholderProducedByCollapse() {
            var original = assistant(new ContentBlock.ThinkingBlock("historical reasoning"),
                    new ContentBlock.TextBlock("A useful historical assistant answer that exceeds fifty characters and is eligible for collapse."));
            List<Message> history = new ArrayList<>();
            history.add(original);
            for (int i = 0; i < 31; i++) history.add(user("u" + i, new ContentBlock.TextBlock("followup " + i)));

            var collapsed = new ContextCollapseService(6, 2000, 500).progressiveCollapse(history).messages();

            assertThat(((Message.AssistantMessage) collapsed.getFirst()).content())
                    .contains(new ContentBlock.TextBlock(MARKER));
            var normalized = normalizer.normalizeTyped(collapsed);
            assertThat(normalized).hasSize(1);
            assertThat(normalized.getFirst()).isInstanceOf(MessageParam.UserParam.class);
            assertThat(((MessageParam.UserParam) normalized.getFirst()).content()).hasSize(31);
            assertThat(history.getFirst()).isSameAs(original);
            assertThat(original.content()).doesNotContain(new ContentBlock.TextBlock(MARKER));
        }
    }
}
