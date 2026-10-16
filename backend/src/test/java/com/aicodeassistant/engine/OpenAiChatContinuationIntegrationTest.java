package com.aicodeassistant.engine;

import com.aicodeassistant.llm.*;
import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real provider -> engine collector -> JSON round-trip -> normalizer -> next HTTP request. */
class OpenAiChatContinuationIntegrationTest {
    private static final String MODEL = "anthropic/claude-opus-5.5";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void signedParallelToolContinuationSurvivesActualCollectorAndSerialization() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            var provider = new OpenAiCompatibleProvider("zenmux", mapper,
                    new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2, 30), 5, 5, false),
                    new ApiKeyRotationManager("fixture"), "fixture", server.url("/api/v1").toString(), MODEL, List.of(MODEL));
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
                    data:{"choices":[{"delta":{"reasoning":"think","reasoning_details":[{"index":0,"type":"reasoning.text","text":"think","signature":"sig-","format":"anthropic-claude-v1","vendor":{"a":1}}]}}]}

                    data:{"choices":[{"delta":{"reasoning_details":[{"index":0,"signature":"end"}],"content":"before"}}]}

                    data:{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"late"}]}}]}

                    data:{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"early","function":{"name":"Read","arguments":"{}"}}]}}]}

                    data:{"choices":[{"delta":{"content":"between"}}]}

                    data:{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"Read","arguments":"{\\"path\\":\\"file\\"}"}}]},"finish_reason":"tool_calls"}]}

                    data:[DONE]

                    """));
            QueryMessageHandler handler = mock(QueryMessageHandler.class);
            Class<?> collectorType = Class.forName("com.aicodeassistant.engine.QueryEngine$StreamCollector");
            var constructor = collectorType.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object collector = constructor.newInstance(handler, null, List.of(), null, mapper, "zenmux", MODEL);
            var user = new Message.UserMessage("user", Instant.EPOCH,
                    List.of(new ContentBlock.TextBlock("read")), null, null);
            var normalizer = new MessageNormalizer();
            provider.streamChat(MODEL, MessageParamConverter.toMaps(normalizer.normalizeTyped(List.of(user))),
                    "system", List.of(), 1024, new ThinkingConfig.Disabled(),
                    new LlmCallContext("first", CancellationSignal.none()), (StreamChatCallback) collector);
            var build = collectorType.getDeclaredMethod("buildAssistantMessage");
            build.setAccessible(true);
            Message.AssistantMessage assistant = (Message.AssistantMessage) build.invoke(collector);
            verify(handler, times(1)).onThinkingDelta("think");
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
            Message roundTripped = mapper.readValue(mapper.writeValueAsString(assistant), Message.class);
            var result = new Message.UserMessage("result", Instant.EPOCH, List.of(
                    new ContentBlock.ToolResultBlock("early", "one", false),
                    new ContentBlock.ToolResultBlock("late", "two", false)), null, null);
            List<Map<String, Object>> history = MessageParamConverter.toMaps(normalizer.normalizeTyped(List.of(user, roundTripped, result)));
            JsonNode untouched = mapper.valueToTree(history);
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data:{\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\ndata:[DONE]\n\n"));
            provider.streamChat(MODEL, history, "system", List.of(), 1024, new ThinkingConfig.Disabled(),
                    new LlmCallContext("second", CancellationSignal.none()), new StreamChatCallback() {
                        public void onEvent(LlmStreamEvent event) { }
                        public void onComplete() { }
                        public void onError(Throwable error) { fail(error); }
                    });
            var request = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(request);
            JsonNode sent = mapper.readTree(request.getBody().readUtf8()).path("messages").get(2);
            assertEquals("assistant", sent.path("role").asText());
            assertEquals("before\nbetween", sent.at("/content/0/text").asText());
            assertEquals("early", sent.at("/tool_calls/0/id").asText());
            assertEquals("late", sent.at("/tool_calls/1/id").asText());
            assertEquals("sig-end", sent.at("/reasoning_details/0/signature").asText());
            assertEquals("think", sent.at("/reasoning_details/0/text").asText());
            assertEquals(1, sent.at("/reasoning_details/0/vendor/a").asInt());
            assertFalse(sent.has("reasoning_content"));
            assertEquals(untouched, mapper.valueToTree(history));
        }
    }
}
