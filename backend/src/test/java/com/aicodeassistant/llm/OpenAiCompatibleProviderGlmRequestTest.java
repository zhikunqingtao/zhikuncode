package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GLM-5.3 / GLM-5.3-Flash 官方请求参数测试。
 * <p>
 * 官方规范（docs.bigmodel.cn/cn/guide/models/vlm/glm-5.3-flash）：
 * <ul>
 *   <li>thinking.type 仅支持 enabled（思考不可关闭），不受 ThinkingConfig.Disabled 影响</li>
 *   <li>reasoning_effort 官方推荐 max（low/high/max 三档）</li>
 *   <li>clear_thinking=false 开启保留式思考（需完整回传 reasoning_content）</li>
 *   <li>流式调用建议 stream 与 tool_stream 同时开启</li>
 * </ul>
 */
@DisplayName("GLM-5.3-Flash 官方请求参数测试")
class OpenAiCompatibleProviderGlmRequestTest {

    private static final String MODEL = "glm-5.3-flash";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OpenAiCompatibleProvider provider;

    @BeforeEach
    void setUp() {
        LlmHttpProperties http = new LlmHttpProperties(
                new LlmHttpProperties.PoolProperties(2, 30), 10, 10, true);
        provider = new OpenAiCompatibleProvider(
                "zhipu-test", objectMapper, http, new ApiKeyRotationManager("key"),
                "key", "https://open.bigmodel.cn/api/paas/v4", "glm-5.3",
                List.of("glm-5.3", MODEL));
    }

    private JsonNode invokeBuildRequest(String model, List<Map<String, Object>> messages,
                                        ThinkingConfig thinkingConfig) throws Exception {
        Method buildRequest = OpenAiCompatibleProvider.class.getDeclaredMethod(
                "buildOpenAiRequest", String.class, List.class, String.class,
                List.class, int.class, ThinkingConfig.class);
        buildRequest.setAccessible(true);
        return (JsonNode) buildRequest.invoke(
                provider, model, messages, "", List.of(), 2048, thinkingConfig);
    }

    @Test
    @DisplayName("tc001: glm-5.3-flash 下发官方推荐思考参数")
    void glm53Flash_officialThinkingParams() throws Exception {
        Map<String, Object> userMessage = Map.of(
                "role", "user", "content", "写一个 Python 快速排序");
        JsonNode body = invokeBuildRequest(MODEL, List.of(userMessage),
                new ThinkingConfig.Disabled());

        assertEquals(MODEL, body.path("model").asText());
        assertTrue(body.path("stream").asBoolean());
        assertEquals("enabled", body.path("thinking").path("type").asText());
        assertFalse(body.path("thinking").path("clear_thinking").asBoolean());
        assertEquals("max", body.path("reasoning_effort").asText());
        assertTrue(body.path("tool_stream").asBoolean());
    }

    @Test
    @DisplayName("tc002: 强制思考不受 ThinkingConfig.Disabled 影响")
    void glm53Flash_thinkingCannotBeDisabled() throws Exception {
        Map<String, Object> userMessage = Map.of(
                "role", "user", "content", "写一个 Python 快速排序");
        JsonNode body = invokeBuildRequest(MODEL, List.of(userMessage),
                new ThinkingConfig.Disabled());

        assertEquals("enabled", body.path("thinking").path("type").asText());
    }

    @Test
    @DisplayName("tc003: glm-5.3 同样下发强制思考参数")
    void glm53_sameForcedThinkingParams() throws Exception {
        Map<String, Object> userMessage = Map.of(
                "role", "user", "content", "写一个 Python 快速排序");
        JsonNode body = invokeBuildRequest("glm-5.3", List.of(userMessage),
                new ThinkingConfig.Enabled(10_000));

        assertEquals("enabled", body.path("thinking").path("type").asText());
        assertFalse(body.path("thinking").path("clear_thinking").asBoolean());
        assertEquals("max", body.path("reasoning_effort").asText());
    }

    @Test
    @DisplayName("tc004: assistant thinking 块回传为 reasoning_content（保留式思考前提）")
    void glm53Flash_reasoningContentPassthrough() throws Exception {
        Map<String, Object> assistant = Map.of(
                "role", "assistant",
                "content", List.of(
                        Map.of("type", "thinking", "thinking", "先分析排序算法"),
                        Map.of("type", "text", "text", "以下是快速排序实现")));
        Map<String, Object> user = Map.of("role", "user", "content", "继续");

        JsonNode body = invokeBuildRequest(MODEL, List.of(assistant, user),
                new ThinkingConfig.Enabled(10_000));

        JsonNode assistantMsg = body.path("messages").get(0);
        assertEquals("assistant", assistantMsg.path("role").asText());
        assertEquals("先分析排序算法", assistantMsg.path("reasoning_content").asText());
        assertEquals("以下是快速排序实现", assistantMsg.path("content").asText());
    }
}
