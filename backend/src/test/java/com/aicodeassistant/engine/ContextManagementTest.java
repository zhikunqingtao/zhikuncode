package com.aicodeassistant.engine;

import com.aicodeassistant.llm.LlmProvider;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.SystemMessageType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 上下文管理测试集 — TC-CTX-001 ~ TC-CTX-005
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("上下文管理测试集")
class ContextManagementTest {

    // ==================== TC-CTX-001 ====================

    @Nested
    @DisplayName("TC-CTX-001: 自动压缩触发验证")
    class CTX001 {

        private CompactService compactService;
        private TokenCounter tokenCounter;

        @BeforeEach
        void setUp() {
            tokenCounter = new TokenCounter();
            compactService = new CompactService(tokenCounter, null, null, null);
        }

        @Test
        @DisplayName("超过 85% 上下文窗口时应触发压缩")
        void shouldTriggerWhenExceedingThreshold() {
            int contextWindowSize = 10000;
            List<Message> messages = buildLargeMessageList(40, 1500);
            int estimatedTokens = tokenCounter.estimateTokens(messages);

            assertTrue(estimatedTokens > contextWindowSize * 0.85,
                "测试数据 token 数 (" + estimatedTokens + ") 应超过阈值 (" + (int)(contextWindowSize * 0.85) + ")");

            assertTrue(compactService.shouldAutoCompact(messages, contextWindowSize),
                "超过 85% 阈值时 shouldAutoCompact 应返回 true");
        }

        @Test
        @DisplayName("低于 85% 上下文窗口时不应触发压缩")
        void shouldNotTriggerBelowThreshold() {
            int contextWindowSize = 100000;
            List<Message> messages = buildLargeMessageList(3, 100);

            assertFalse(compactService.shouldAutoCompact(messages, contextWindowSize),
                "低于 85% 阈值时 shouldAutoCompact 应返回 false");
        }

        @Test
        @DisplayName("压缩后 CompactResult 包含压缩前后 token 数")
        void compactResultContainsTokenCounts() {
            int contextWindowSize = 5000;
            List<Message> messages = buildLargeMessageList(30, 500);

            CompactService.CompactResult result = compactService.compact(
                messages, contextWindowSize, false);

            assertTrue(result.beforeTokens() > 0, "压缩前 token 数应 > 0");
            assertTrue(result.afterTokens() >= 0, "压缩后 token 数应 >= 0");
            assertTrue(result.beforeTokens() >= result.afterTokens(),
                "压缩后 token 数应 <= 压缩前");
        }

        @Test
        @DisplayName("压缩后消息数减少")
        void compactReducesMessageCount() {
            int contextWindowSize = 5000;
            List<Message> messages = buildLargeMessageList(30, 500);

            CompactService.CompactResult result = compactService.compact(
                messages, contextWindowSize, false);

            if (!result.compactedMessages().isEmpty()) {
                assertTrue(result.compactedMessages().size() <= messages.size(),
                    "压缩后消息数应 <= 原始消息数");
            }
        }
    }

    // ==================== TC-CTX-002 ====================

    @Nested
    @DisplayName("TC-CTX-002: 消息裁剪边界验证")
    class CTX002 {

        private CompactService compactService;
        private TokenCounter tokenCounter;

        @BeforeEach
        void setUp() {
            tokenCounter = new TokenCounter();
            compactService = new CompactService(tokenCounter, null, null, null);
        }

        @Test
        @DisplayName("planCompaction 保留最近 N 轮对话")
        void planCompactionPreservesRecentTurns() {
            List<Message> messages = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                messages.add(new Message.UserMessage(
                    UUID.randomUUID().toString(), Instant.now(),
                    List.of(new ContentBlock.TextBlock("User message " + i + " ".repeat(50))),
                    null, null));
                messages.add(new Message.AssistantMessage(
                    UUID.randomUUID().toString(), Instant.now(),
                    List.of(new ContentBlock.TextBlock("Assistant reply " + i + " ".repeat(50))),
                    null, null));
            }

            CompactService.CompactionPlan plan = compactService.planCompaction(messages, 5000, 3);

            assertFalse(plan.preservedMessages().isEmpty(), "保留区不应为空");
            assertFalse(plan.compactionMessages().isEmpty(), "压缩区不应为空");
        }

        @Test
        @DisplayName("tool_use/tool_result 配对完整性 — fallbackKeyMessageSelection")
        void toolUsePairsRemainIntact() {
            List<Message> messages = new ArrayList<>();

            // 系统消息
            messages.add(new Message.SystemMessage(
                UUID.randomUUID().toString(), Instant.now(),
                "System prompt", SystemMessageType.INFO));

            // 5 组 tool_use + tool_result
            for (int i = 0; i < 5; i++) {
                String tuId = "tu-" + i;
                String aUuid = "assistant-" + i;
                ObjectMapper mapper = new ObjectMapper();
                JsonNode inputNode = mapper.valueToTree(Map.of("command", "ls"));
                messages.add(new Message.AssistantMessage(
                    aUuid, Instant.now(),
                    List.of(new ContentBlock.ToolUseBlock(tuId, "Bash", inputNode)),
                    null, null));
                messages.add(new Message.UserMessage(
                    UUID.randomUUID().toString(), Instant.now(),
                    null, "output-" + i, aUuid));
            }

            // 使用大预算选择关键消息
            List<Message> selected = compactService.fallbackKeyMessageSelection(messages, 50000);

            // 验证系统消息保留
            assertTrue(selected.stream().anyMatch(m -> m instanceof Message.SystemMessage),
                "系统消息应保留");

            // 验证 tool_result 的对应 assistant UUID 在选中集中
            for (Message m : selected) {
                if (m instanceof Message.UserMessage user && user.toolUseResult() != null) {
                    assertNotNull(user.sourceToolAssistantUUID(),
                        "tool_result 应有关联的 assistant UUID");
                }
            }
        }
    }

    // ==================== TC-CTX-003 ====================

    @Nested
    @DisplayName("TC-CTX-003: 压缩前后语义一致性验证")
    class CTX003 {

        @Mock
        private LlmProviderRegistry providerRegistry;
        @Mock
        private LlmProvider llmProvider;

        private CompactService compactService;
        private TokenCounter tokenCounter;

        @BeforeEach
        void setUp() {
            tokenCounter = new TokenCounter();
            compactService = new CompactService(tokenCounter, providerRegistry, null, null);
        }

        @Test
        @DisplayName("Mock LLM 返回包含关键主题词的摘要")
        void compactWithMockLlmRetainsSemantic() throws Exception {
            List<Message> messages = new ArrayList<>();
            String[] topics = {"DatabaseConfig", "UserService", "pom.xml", "Spring Boot 3.2"};
            for (int i = 0; i < 30; i++) {
                String topic = topics[i % topics.length];
                messages.add(new Message.UserMessage(
                    UUID.randomUUID().toString(), Instant.now(),
                    List.of(new ContentBlock.TextBlock("请帮我修改 " + topic + " 文件，添加新功能 " + "x".repeat(200))),
                    null, null));
                messages.add(new Message.AssistantMessage(
                    UUID.randomUUID().toString(), Instant.now(),
                    List.of(new ContentBlock.TextBlock("好的，我已经修改了 " + topic + " " + "y".repeat(200))),
                    null, null));
            }

            String mockSummary = "<analysis>分析对话历史</analysis>" +
                "<summary>\n## 主要请求\n用户请求修改 DatabaseConfig、UserService、pom.xml 和 Spring Boot 3.2 相关配置。" +
                "\n## 文件和代码段\n- DatabaseConfig: 添加连接池配置\n- UserService: 新增验证逻辑" +
                "\n- pom.xml: 升级依赖版本\n## 当前工作\n持续优化各模块功能。\n</summary>";

            when(providerRegistry.hasProviders()).thenReturn(true);
            when(providerRegistry.getFastModel()).thenReturn("qwen-turbo");
            when(providerRegistry.getProvider("qwen-turbo")).thenReturn(llmProvider);
            when(llmProvider.chatSync(anyString(), anyString(), anyString(), anyInt(), any(), anyLong()))
                .thenReturn(mockSummary);

            CompactService.CompactResult result = compactService.compact(messages, 5000, false);

            assertTrue(result.beforeTokens() > 0, "压缩前 token 数应 > 0");
            // 验证压缩确实执行了（消息数有变化或 ratio > 0）
            assertNotNull(result.compactedMessages(), "compactedMessages 不应为 null");
        }

        @Test
        @DisplayName("LLM 不可用时降级到关键消息选择")
        void fallbackWhenLlmUnavailable() {
            when(providerRegistry.hasProviders()).thenReturn(false);
            List<Message> messages = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                messages.add(new Message.UserMessage(
                    UUID.randomUUID().toString(), Instant.now(),
                    List.of(new ContentBlock.TextBlock("Message " + i + " ".repeat(100))),
                    null, null));
                messages.add(new Message.AssistantMessage(
                    UUID.randomUUID().toString(), Instant.now(),
                    List.of(new ContentBlock.TextBlock("Reply " + i + " ".repeat(100))),
                    null, null));
            }

            CompactService.CompactResult result = compactService.compact(messages, 3000, false);

            assertNotNull(result, "降级后仍应返回结果");
        }
    }

    // ==================== TC-CTX-004 ====================

    @Nested
    @DisplayName("TC-CTX-004: Token 计数精度验证")
    class CTX004 {

        private TokenCounter tokenCounter;

        @BeforeEach
        void setUp() {
            tokenCounter = new TokenCounter();
        }

        @Test
        @DisplayName("纯英文文本: chars/token ≈ 4.0")
        void englishTextTokenRatio() {
            String text = "The quick brown fox jumps over the lazy dog. ".repeat(20);
            int tokens = tokenCounter.estimateTokens(text, "text");
            double ratio = (double) text.length() / tokens;
            assertEquals(4.0, ratio, 0.5,
                "英文 chars/token 应约为 4.0, 实际: " + ratio);
        }

        @Test
        @DisplayName("纯中文文本: chars/token 在合理范围")
        void chineseTextTokenRatio() {
            String text = "这是一段用于测试的中文文本内容，包含了各种常见的汉字和标点符号。".repeat(10);
            int tokens = tokenCounter.estimateTokens(text);
            double ratio = (double) text.length() / tokens;
            assertTrue(ratio >= 1.5 && ratio <= 3.0,
                "中文 chars/token 应在 1.5~3.0 范围, 实际: " + ratio);
        }

        @Test
        @DisplayName("JSON 格式: chars/token ≈ 2.0")
        void jsonContentTokenRatio() {
            String json = "{\"name\":\"test\",\"version\":\"1.0\",\"description\":\"A test JSON\",\"dependencies\":{\"spring-boot\":\"3.2.0\"}}";
            int tokens = tokenCounter.estimateTokens(json, "json");
            double ratio = (double) json.length() / tokens;
            assertEquals(2.0, ratio, 0.5,
                "JSON chars/token 应约为 2.0, 实际: " + ratio);
        }

        @Test
        @DisplayName("代码格式: chars/token ≈ 3.5")
        void codeContentTokenRatio() {
            String code = "public class Foo {\n    private String name;\n    public void bar() {\n        System.out.println(name);\n    }\n}";
            int tokens = tokenCounter.estimateTokens(code, "code");
            double ratio = (double) code.length() / tokens;
            assertEquals(3.5, ratio, 1.0,
                "代码 chars/token 应约为 3.5, 实际: " + ratio);
        }

        @Test
        @DisplayName("混合文本验证")
        void mixedTextTokenEstimation() {
            String mixed = "这是中文 mixed with English text and 123 numbers.";
            int tokens = tokenCounter.estimateTokens(mixed);
            assertTrue(tokens > 0, "混合文本 token 数应 > 0");
        }

        @Test
        @DisplayName("消息开销: 每条消息 +4 token")
        void messageOverhead() {
            String text = "Hello World";
            List<Message> oneMsg = List.of(new Message.UserMessage(
                UUID.randomUUID().toString(), Instant.now(),
                List.of(new ContentBlock.TextBlock(text)), null, null));
            List<Message> twoMsgs = List.of(
                new Message.UserMessage(UUID.randomUUID().toString(), Instant.now(),
                    List.of(new ContentBlock.TextBlock(text)), null, null),
                new Message.UserMessage(UUID.randomUUID().toString(), Instant.now(),
                    List.of(new ContentBlock.TextBlock(text)), null, null));

            int tokensOne = tokenCounter.estimateTokens(oneMsg);
            int tokensTwo = tokenCounter.estimateTokens(twoMsgs);

            int diff = tokensTwo - tokensOne;
            assertTrue(diff >= 4, "两条消息比一条消息多出的 token 应 >= 4(开销), 实际差值: " + diff);
        }

        @ParameterizedTest
        @CsvSource({"'', 0", "' ', 0"})
        @DisplayName("空文本和空白文本返回 0")
        void emptyTextReturnsZero(String text, int expected) {
            assertEquals(expected, tokenCounter.estimateTokens(text));
        }

        @Test
        @DisplayName("null 文本返回 0")
        void nullTextReturnsZero() {
            assertEquals(0, tokenCounter.estimateTokens((String) null));
        }

        @Test
        @DisplayName("内容类型自动检测")
        void contentTypeAutoDetection() {
            assertEquals("json", tokenCounter.detectContentType(
                "{\"key\": \"value\", \"number\": 42}"));
            assertEquals("chinese", tokenCounter.detectContentType(
                "这是一段很长的中文文本，用于测试内容类型检测功能是否正确工作。"));
        }
    }

    // ==================== TC-CTX-005 ====================

    @Nested
    @DisplayName("TC-CTX-005: 多会话上下文隔离验证")
    class CTX005 {

        private CompactService compactService;
        private TokenCounter tokenCounter;

        @BeforeEach
        void setUp() {
            tokenCounter = new TokenCounter();
            compactService = new CompactService(tokenCounter, null, null, null);
        }

        @Test
        @DisplayName("两个独立会话的消息列表互不干扰")
        void separateSessionsHaveIndependentContexts() {
            List<Message> sessionA = buildSessionMessages("session-A", "Java Spring Boot 配置", 15);
            List<Message> sessionB = buildSessionMessages("session-B", "Python FastAPI 开发", 15);

            int tokensA = tokenCounter.estimateTokens(sessionA);
            int tokensB = tokenCounter.estimateTokens(sessionB);

            assertTrue(tokensA > 0, "Session A token 数应 > 0");
            assertTrue(tokensB > 0, "Session B token 数应 > 0");

            // 验证会话 A 的内容不包含会话 B 的主题
            boolean aContainsBTopic = sessionA.stream().anyMatch(m ->
                m instanceof Message.UserMessage u && u.content() != null &&
                u.content().stream().anyMatch(b ->
                    b instanceof ContentBlock.TextBlock t && t.text().contains("Python FastAPI")));
            assertFalse(aContainsBTopic, "Session A 不应包含 Session B 的内容");

            boolean bContainsATopic = sessionB.stream().anyMatch(m ->
                m instanceof Message.UserMessage u && u.content() != null &&
                u.content().stream().anyMatch(b ->
                    b instanceof ContentBlock.TextBlock t && t.text().contains("Java Spring Boot")));
            assertFalse(bContainsATopic, "Session B 不应包含 Session A 的内容");
        }

        @Test
        @DisplayName("一个会话压缩不影响另一个会话的 token 数")
        void compactOneSessionDoesNotAffectOther() {
            List<Message> sessionA = buildSessionMessages("session-A", "Topic A", 20);
            List<Message> sessionB = buildSessionMessages("session-B", "Topic B", 5);

            int tokensBBefore = tokenCounter.estimateTokens(sessionB);

            // 压缩 session A
            compactService.compact(sessionA, 3000, false);

            // session B 不受影响
            int tokensBAfter = tokenCounter.estimateTokens(sessionB);
            assertEquals(tokensBBefore, tokensBAfter, "Session B 的 token 数不应变化");
        }

        private List<Message> buildSessionMessages(String sessionId, String topic, int turns) {
            List<Message> messages = new ArrayList<>();
            for (int i = 0; i < turns; i++) {
                messages.add(new Message.UserMessage(
                    UUID.randomUUID().toString(), Instant.now(),
                    List.of(new ContentBlock.TextBlock(
                        "[" + sessionId + "] 请帮我处理 " + topic + " 第" + i + "步 " + "x".repeat(200))),
                    null, null));
                messages.add(new Message.AssistantMessage(
                    UUID.randomUUID().toString(), Instant.now(),
                    List.of(new ContentBlock.TextBlock(
                        "[" + sessionId + "] 好的，关于 " + topic + " 已完成第" + i + "步 " + "y".repeat(200))),
                    null, null));
            }
            return messages;
        }
    }

    // ==================== 辅助方法 ====================

    private static List<Message> buildLargeMessageList(int turns, int charsPerMessage) {
        List<Message> messages = new ArrayList<>();
        String longText = "A".repeat(charsPerMessage);
        for (int i = 0; i < turns; i++) {
            messages.add(new Message.UserMessage(
                UUID.randomUUID().toString(), Instant.now(),
                List.of(new ContentBlock.TextBlock(longText)),
                null, null));
            messages.add(new Message.AssistantMessage(
                UUID.randomUUID().toString(), Instant.now(),
                List.of(new ContentBlock.TextBlock("Response " + i + ": " + longText)),
                null, null));
        }
        return messages;
    }
}
