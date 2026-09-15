package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.aicodeassistant.engine.CompactService;
import com.aicodeassistant.engine.TokenCounter;
import com.aicodeassistant.engine.SideQueryService;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.SystemMessageType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Explicitly enabled real Token Plan calls. Sends synthetic data only; never logs credentials. */
@EnabledIfEnvironmentVariable(named = "BAILIAN_DEEPSEEK_LIVE_TEST", matches = "true")
@Timeout(120)
class BailianDeepSeekLiveTest {
    private static final String MODEL = "deepseek-v4.1-flash";
    private final ObjectMapper mapper = new ObjectMapper();
    private OpenAiCompatibleProvider provider;

    @BeforeEach
    void setUp() {
        String key = System.getenv("LLM_PROVIDER_DASHSCOPE_TOKEN_PLAN_API_KEY");
        assertNotNull(key, "Token Plan key required");
        assertFalse(key.isBlank(), "Token Plan key required");
        provider = new OpenAiCompatibleProvider("dashscope-token-plan", mapper,
                new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2, 30), 15, 30, true),
                new ApiKeyRotationManager(key), key,
                "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", MODEL, List.of(MODEL));
    }

    @Test
    void textAndThinkingStream() {
        Capture result = call(List.of(Map.of("role", "user", "content", "计算 17 * 23，只输出结果。")), List.of());
        assertEquals("391", result.text.toString().trim());
        assertFalse(result.thinking.isEmpty(), "Reasoning must be received through the Java SSE parser");
        System.out.println("BAILIAN_LIVE text: " + result.text);
    }

    @Test
    void fourImagesThroughInternalImageConversion() throws Exception {
        BufferedImage image = new BufferedImage(360, 240, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 360, 240);
        g.setColor(Color.RED); g.fillRect(25, 60, 100, 120);
        g.setColor(Color.BLUE); g.fillRect(210, 25, 100, 70); g.fillRect(210, 145, 100, 70);
        g.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        Map<String, Object> imageBlock = Map.of("type", "image", "source", Map.of(
                "type", "base64", "media_type", "image/png", "data", Base64.getEncoder().encodeToString(bytes.toByteArray())));
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(Map.of("type", "text", "text", "一共有几张图片？每张左侧和右侧各有什么颜色、几个色块？用阿拉伯数字简短回答。"));
        for (int i = 0; i < 4; i++) blocks.add(imageBlock);
        Capture result = call(List.of(Map.of("role", "user", "content", blocks)), List.of());
        String text = result.text.toString();
        assertTrue(text.contains("4") && text.contains("红") && text.contains("蓝")
                && text.contains("1") && text.contains("2"), text);
        System.out.println("BAILIAN_LIVE vision: " + text);
    }

    @Test
    void toolRoundTripKeepsReasoningAndToolResults() throws Exception {
        var tools = List.<Map<String, Object>>of(Map.of("type", "function", "function", Map.of("name", "lookup_test_code",
                "description", "Get the verification code for a test item.",
                "parameters", Map.of("type", "object", "properties", Map.of("item", Map.of("type", "string")),
                        "required", List.of("item")))));
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "user", "content", "调用 lookup_test_code 查询 item=probe 的验证码，然后仅回复工具返回的验证码，不要猜测。"));
        Capture first = call(messages, tools);
        assertEquals("lookup_test_code", first.toolName);
        assertEquals("probe", mapper.readTree(first.arguments.toString()).path("item").asText());
        messages.add(Map.of("role", "assistant", "content", List.of(
                Map.of("type", "thinking", "thinking", first.thinking.toString()),
                Map.of("type", "tool_use", "id", first.toolId, "name", first.toolName,
                        "input", mapper.readValue(first.arguments.toString(), Map.class)))));
        messages.add(Map.of("role", "user", "content", List.of(Map.of("type", "tool_result", "tool_use_id", first.toolId,
                "content", "{\"code\":\"BAILIAN-7429\"}"))));
        Capture second = call(messages, tools);
        assertTrue(second.text.toString().contains("BAILIAN-7429"), second.text.toString());
        System.out.println("BAILIAN_LIVE tools: " + second.text);
    }

    @Test
    void syncSummaryUsesCompactBudgetAndTimeout() {
        String response = provider.chatSync(MODEL,
                "为对话生成摘要。仅输出 <summary> 标签包裹的摘要，保留文件路径及待办。",
                "用户要求修改 /tmp/demo.txt，将重试次数从 2 改为 3。助手已修改，尚未运行测试。",
                4096, null, 90_000L);
        assertTrue(response.contains("<summary>") && response.contains("/tmp/demo.txt")
                && response.contains("3") && response.contains("测试"), response);
        System.out.println("BAILIAN_LIVE compact_sync: " + response);
    }

    @Test
    void compactionWithConfiguredModel() {
        var registry = new LlmProviderRegistry(List.of(provider), null);
        var service = new CompactService(new TokenCounter(null, null, null), registry, null, null);
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            messages.add(new Message.UserMessage(UUID.randomUUID().toString(), Instant.now(),
                    List.of(new ContentBlock.TextBlock("修改 /tmp/demo.py：重试次数由 2 改为 3，保留超时设置。"
                            + "测试日志重复信息：网络请求超时，等待下一次重试。".repeat(100))), null, null));
            messages.add(new Message.AssistantMessage(UUID.randomUUID().toString(), Instant.now(),
                    List.of(new ContentBlock.TextBlock("已修改 /tmp/demo.py，重试次数为 3。测试尚未执行，需要保留此待办。")),
                    "end_turn", null));
        }
        var result = service.compact(messages, 1000000, true);
        assertTrue(result.afterTokens() < result.beforeTokens(), "Real compaction must save tokens");
        Message.SystemMessage summary = result.compactedMessages().stream()
                .filter(m -> m instanceof Message.SystemMessage s && s.type() == SystemMessageType.COMPACT_SUMMARY)
                .map(m -> (Message.SystemMessage) m).findFirst().orElseThrow();
        assertTrue(summary.content().contains("/tmp/demo.py"), summary.content());
        assertFalse(summary.content().startsWith("[对话历史已压缩]"), "Must use the LLM summary, not local fallback");
        System.out.println("BAILIAN_LIVE CompactService: " + result.beforeTokens() + " -> " + result.afterTokens() + " tokens");
    }

    private Capture call(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        Capture result = new Capture();
        provider.streamChat(MODEL, messages, "", tools, 2048, new ThinkingConfig.Enabled(10_000),
                LlmCallContext.unscoped(), result);
        assertNull(result.error, () -> "Live provider call failed: " + result.error);
        assertTrue(result.completed, "SSE must complete");
        return result;
    }

    public record ProbeResult(String code, int count) {}

    @Test
    void fastModelSideQueriesWithProductionBudgets() {
        var registry = new LlmProviderRegistry(List.of(provider), null);
        assertEquals(MODEL, registry.getFastModel());
        var side = new SideQueryService(registry, mapper);
        String title = side.generateTitle("Fix a Python function that retries network requests three times.");
        assertNotNull(title, "64-token title request must return text");
        assertFalse(title.isBlank());
        String commit = side.generateCommitMessage("diff --git a/demo.py b/demo.py\n-RETRIES = 2\n+RETRIES = 3\n");
        assertNotNull(commit, "128-token commit request must return text");
        assertTrue(commit.contains(":"), commit);
        ProbeResult structured = side.queryStructured("Extract the supplied fields exactly.",
                "code=FAST-7429; count=3", ProbeResult.class);
        assertEquals(new ProbeResult("FAST-7429", 3), structured);
        System.out.println("BAILIAN_LIVE fast queries: title=" + title + "; commit=" + commit + "; structured=" + structured);
    }

    private static class Capture implements StreamChatCallback {
        final StringBuilder text = new StringBuilder(), thinking = new StringBuilder(), arguments = new StringBuilder();
        String toolId, toolName;
        Throwable error;
        boolean completed;
        @Override public void onEvent(LlmStreamEvent event) {
            if (event instanceof LlmStreamEvent.TextDelta e) text.append(e.text());
            if (event instanceof LlmStreamEvent.ThinkingDelta e) thinking.append(e.thinking());
            if (event instanceof LlmStreamEvent.ToolUseStart e) { toolId = e.id(); toolName = e.name(); }
            if (event instanceof LlmStreamEvent.ToolInputDelta e) arguments.append(e.jsonDelta());
        }
        @Override public void onComplete() { completed = true; }
        @Override public void onError(Throwable e) { error = e; }
    }
}
