package com.aicodeassistant.llm.impl;

import com.aicodeassistant.llm.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Per-request OpenRouter reasoning state, isolated from Responses API continuation items. */
final class OpenRouterReasoning {
    private final List<JsonNode> details = new ArrayList<>();

    void accept(JsonNode chunk, StreamChatCallback callback) {
        if (chunk.hasNonNull("error")) {
            var error = chunk.get("error");
            int code = error.path("code").asInt(502);
            throw new LlmApiException("OpenRouter stream error: " + error.path("message").asText("Unknown error"),
                    code == 429 || code >= 500, code);
        }
        JsonNode delta = chunk.path("choices").path(0).path("delta");
        StringBuilder visible = new StringBuilder();
        if (delta.path("reasoning_details").isArray()) {
            for (JsonNode detail : delta.get("reasoning_details")) {
                // Preserve the exact sequence, signatures and encrypted data for tool continuation.
                details.add(detail.deepCopy());
                if ("reasoning.text".equals(detail.path("type").asText()))
                    visible.append(detail.path("text").asText(""));
                if ("reasoning.summary".equals(detail.path("type").asText()))
                    visible.append(detail.path("summary").asText(""));
            }
            if (!delta.get("reasoning_details").isEmpty())
                callback.onEvent(new LlmStreamEvent.ProviderProgress());
        }
        // Gateways may expose the same text in both fields: display it only once.
        String text = delta.hasNonNull("reasoning") ? delta.get("reasoning").asText()
                : delta.hasNonNull("reasoning_content") ? delta.get("reasoning_content").asText()
                : visible.toString();
        if (!text.isEmpty()) callback.onEvent(new LlmStreamEvent.ThinkingDelta(text));
    }

    void complete(ObjectMapper mapper, StreamChatCallback callback) {
        if (details.isEmpty()) return;
        ObjectNode state = mapper.createObjectNode().put("type", "openrouter_reasoning");
        state.set("reasoning_details", mapper.valueToTree(details));
        callback.onEvent(new LlmStreamEvent.ProviderResponseState(List.of(state)));
    }

    static void replay(ObjectMapper mapper, ObjectNode message, List<?> blocks, String model) {
        for (Object item : blocks) {
            if (!(item instanceof Map<?, ?> block)
                    || !"provider_response_state".equals(block.get("type"))
                    || !"openrouter".equals(block.get("provider"))
                    || !model.equals(block.get("model"))) continue;
            JsonNode output = mapper.valueToTree(block.get("output"));
            if (output == null || !output.isArray()) continue;
            for (JsonNode state : output) {
                if ("openrouter_reasoning".equals(state.path("type").asText())
                        && state.path("reasoning_details").isArray())
                    message.set("reasoning_details", state.get("reasoning_details").deepCopy());
            }
        }
    }
}
