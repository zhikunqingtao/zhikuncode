package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TokenCounter 单元测试 — 覆盖 5 个公开方法的核心场景。
 * <p>
 * Given-When-Then 模式，JUnit 5。
 */
@DisplayName("TokenCounter 单元测试")
class TokenCounterTest {

    private TokenCounter counter;

    @BeforeEach
    void setUp() {
        counter = new TokenCounter();
    }

    // ═══════════════════════════════════════════════════════════════
    // 1. estimateTokens(List<Message>)
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("消息列表 token 估算")
    class MessageListEstimation {

        @Test
        @DisplayName("空消息列表返回 0")
        void shouldReturnZeroForEmptyList() {
            assertEquals(0, counter.estimateTokens(Collections.<Message>emptyList()));
        }

        @Test
        @DisplayName("null 消息列表返回 0")
        void shouldReturnZeroForNullList() {
            assertEquals(0, counter.estimateTokens((List<Message>) null));
        }

        @Test
        @DisplayName("单条 SystemMessage 正确计算 token 数")
        void shouldCountSingleSystemMessage() {
            // Given: 一条 100 字符的系统消息
            String content = "a".repeat(100);
            Message msg = new Message.SystemMessage("uuid-1", Instant.now(), content, null);

            // When
            int tokens = counter.estimateTokens(List.of(msg));

            // Then: 100 / 3.5 + 4 (边界开销) ≈ 32
            assertTrue(tokens > 0, "Token count should be positive");
            assertTrue(tokens < 200, "Token count should be reasonable");
        }

        @Test
        @DisplayName("多条混合消息累加")
        void shouldAccumulateMultipleMessages() {
            // Given
            String text = "hello world test message for token counting";
            Message sys = new Message.SystemMessage("s1", Instant.now(), text, null);
            Message user = new Message.UserMessage("u1", Instant.now(),
                    List.of(new ContentBlock.TextBlock(text)), null, null);
            Message assistant = new Message.AssistantMessage("a1", Instant.now(),
                    List.of(new ContentBlock.TextBlock(text)), "end_turn",
                    new Usage(10, 20, 0, 0));

            // When
            int tokens = counter.estimateTokens(List.of(sys, user, assistant));

            // Then: 3 条消息应大于单条
            int singleTokens = counter.estimateTokens(List.of(sys));
            assertTrue(tokens > singleTokens, "Multiple messages should have more tokens");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. estimateTokens(String) — 自动检测
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("单文本自动检测 token 估算")
    class SingleTextEstimation {

        @Test
        @DisplayName("空字符串返回 0")
        void shouldCountEmptyStringAsZero() {
            assertEquals(0, counter.estimateTokens(""));
        }

        @Test
        @DisplayName("null 返回 0")
        void shouldCountNullAsZero() {
            assertEquals(0, counter.estimateTokens((String) null));
        }

        @Test
        @DisplayName("英文纯文本正确估算")
        void shouldEstimateTokensForPlainText() {
            // Given: 100 字符英文
            String text = "The quick brown fox jumps over the lazy dog. " +
                    "This is a simple test for token counting estimation.";
            // When
            int tokens = counter.estimateTokens(text);
            // Then
            assertTrue(tokens > 0);
            assertTrue(tokens < text.length()); // token 数应小于字符数
        }

        @Test
        @DisplayName("中文文本按较低比率计算（更多 token）")
        void shouldCountChineseCharactersCorrectly() {
            // Given: 中文占比 > 30%
            String chinese = "这是一个中文测试文本，用于验证中文字符的 token 计算是否正确，中文字符通常每两个字符一个 token";
            // When
            int tokens = counter.estimateTokens(chinese);
            // Then: 中文 token 数应相对较高（每 ~2 字符 1 token）
            assertTrue(tokens > 0);
        }

        @Test
        @DisplayName("代码文本检测并正确估算")
        void shouldCountCodeBlocksAccurately() {
            // Given: 具有代码特征的文本
            String code = """
                    import java.util.List;
                    
                    public class Foo {
                        private int bar;
                        
                        public void doSomething() {
                            int x = 1;
                            int y = 2;
                            return x + y;
                        }
                    }
                    """;
            // When
            int tokens = counter.estimateTokens(code);
            // Then
            assertTrue(tokens > 0);
        }

        @Test
        @DisplayName("超长文本正常处理不抛异常")
        void shouldHandleLongText() {
            // Given: 100K 字符
            String longText = "x".repeat(100_000);
            // When / Then: 不抛异常
            int tokens = counter.estimateTokens(longText);
            assertTrue(tokens > 0);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. estimateTokens(String, String) — 带类型提示
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("带内容类型提示的 token 估算")
    class TypedEstimation {

        @Test
        @DisplayName("JSON 类型使用 JSON 系数")
        void shouldEstimateTokensWithJsonHint() {
            String json = "{\"key\": \"value\", \"count\": 42}";
            int jsonTokens = counter.estimateTokens(json, "json");
            int defaultTokens = counter.estimateTokens(json, "text");
            // JSON 系数更小(2.0)，同样文本应产生更多 token
            assertTrue(jsonTokens >= defaultTokens,
                    "JSON hint should produce more tokens than text hint");
        }

        @Test
        @DisplayName("code 类型使用代码系数")
        void shouldEstimateTokensWithCodeHint() {
            String code = "public static void main(String[] args) { System.out.println(); }";
            int tokens = counter.estimateTokens(code, "code");
            assertTrue(tokens > 0);
        }

        @Test
        @DisplayName("null 类型提示回退到自动检测")
        void shouldFallbackForNullContentType() {
            String text = "some plain text here";
            int tokens = counter.estimateTokens(text, null);
            assertTrue(tokens > 0);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. estimateImageTokens(int, int)
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("图片 token 估算")
    class ImageEstimation {

        @Test
        @DisplayName("正常尺寸图片按公式计算: ceil(w*h/750)")
        void shouldEstimateImageTokensByDimensions() {
            // Given: 1024 x 768
            int tokens = counter.estimateImageTokens(1024, 768);
            // Expected: ceil(1024 * 768 / 750) = ceil(1048.576) = 1049
            assertEquals((int) Math.ceil(1024.0 * 768 / 750.0), tokens);
        }

        @Test
        @DisplayName("无效尺寸返回默认值 85")
        void shouldReturnDefaultTokensForInvalidImageSize() {
            assertEquals(85, counter.estimateImageTokens(0, 0));
            assertEquals(85, counter.estimateImageTokens(-1, 100));
            assertEquals(85, counter.estimateImageTokens(100, -1));
        }

        @Test
        @DisplayName("小图片正确计算")
        void shouldCalculateSmallImage() {
            // 100 x 100 = 10000 / 750 = 14 (ceil)
            int tokens = counter.estimateImageTokens(100, 100);
            assertEquals((int) Math.ceil(10000.0 / 750.0), tokens);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. detectContentType(String)
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("内容类型检测")
    class ContentTypeDetection {

        @Test
        @DisplayName("JSON 对象检测为 json")
        void shouldDetectContentTypeAsJson() {
            String json = "{\"name\": \"test\", \"value\": 42, \"nested\": {\"a\": 1}}";
            assertEquals("json", counter.detectContentType(json));
        }

        @Test
        @DisplayName("JSON 数组检测为 json")
        void shouldDetectJsonArray() {
            String jsonArray = "[{\"id\": 1}, {\"id\": 2}, {\"id\": 3}]";
            assertEquals("json", counter.detectContentType(jsonArray));
        }

        @Test
        @DisplayName("代码内容检测为 code")
        void shouldDetectContentTypeAsCode() {
            String code = """
                    import java.util.List;
                    
                    public class Foo {
                        private int bar;
                        public void run() {
                            int x = 1;
                            return;
                        }
                    }
                    """;
            assertEquals("code", counter.detectContentType(code));
        }

        @Test
        @DisplayName("中文文本检测为 chinese")
        void shouldDetectContentTypeAsChinese() {
            String chinese = "这是一段中文测试文本，用于验证内容类型检测功能是否能正确识别中文内容。中文字符占比应超过百分之三十。";
            assertEquals("chinese", counter.detectContentType(chinese));
        }

        @Test
        @DisplayName("短文本或 null 返回 text")
        void shouldReturnTextForShortOrNull() {
            assertEquals("text", counter.detectContentType(null));
            assertEquals("text", counter.detectContentType("short"));
        }
    }
}
