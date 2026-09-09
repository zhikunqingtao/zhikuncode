package com.aicodeassistant.service;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.Usage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicMessageProjectionTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final PublicMessageProjection projection = new PublicMessageProjection();

    @Test
    void providerStateAndRedactedPayloadNeverReachPublicJson() throws Exception {
        Message.AssistantMessage internal = new Message.AssistantMessage(
                "a1", Instant.EPOCH, List.of(
                new ContentBlock.ProviderResponseStateBlock(
                        "zenmux", "openai/gpt-6-astra", "safe summary",
                        List.of(mapper.readTree("""
                                {"type":"reasoning","encrypted_content":"secret","signature":"sig"}
                                """))),
                new ContentBlock.RedactedThinkingBlock("private-signature"),
                new ContentBlock.TextBlock("answer")),
                "end_turn", Usage.zero());

        Message projected = projection.project(internal);
        String json = mapper.writeValueAsString(projected);

        assertThat(json).contains("safe summary", "answer");
        assertThat(json).doesNotContain("provider_response_state",
                "encrypted_content", "private-signature", "signature", "secret");
    }
}
