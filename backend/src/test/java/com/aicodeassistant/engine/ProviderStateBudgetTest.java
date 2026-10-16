package com.aicodeassistant.engine;

import com.aicodeassistant.llm.MessageParamConverter;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** No services or stored sessions: private continuation data must not displace public history. */
class ProviderStateBudgetTest {
    private static final String MODEL = "anthropic/claude-opus-5.5";
    private final ObjectMapper mapper = new ObjectMapper();
    private final TokenCounter counter = new TokenCounter(null, null, null);
    private final TokenBudgetGuard guard = new TokenBudgetGuard();

    @Test
    void hugeSignatureMetadataAndDisplayCopyDoNotTriggerCleaningOrDegradation() throws Exception {
        ObjectNode item = reasoningItem(true);
        ((ObjectNode) item.at("/reasoning_details/0")).put("signature", "s".repeat(150_000));
        ((ObjectNode) item.get("replay")).put("local_metadata", "m".repeat(150_000));
        var saved = state("zenmux", MODEL, List.of(item));
        var content = new ArrayList<>(publicContent());
        content.add(new ContentBlock.ThinkingBlock("display only ".repeat(20_000)));
        content.add(saved);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", png);
        var image = new ContentBlock.ImageBlock("image/png",
                Base64.getEncoder().encodeToString(png.toByteArray()), 1, 1);
        Message oldUser = new Message.UserMessage("old-image", Instant.EPOCH, List.of(image), null, null);
        List<Message> source = new ArrayList<>(List.of(oldUser));
        source.addAll(history(content));
        List<Message> control = new ArrayList<>(List.of(oldUser));
        control.addAll(history(publicContent()));

        assertEquals(counter.estimateTokens(control), counter.estimateTokens(source));
        assertEquals(counter.estimateTokens(control, MODEL), counter.estimateTokens(source, MODEL));
        // A decoded image alone reserves 1024 tokens. The public fixture fits 2000;
        // the large private state and its display copy must not change that decision.
        assertTrue(guard.estimateTokens(control, 3.5) < 2000);
        var phase1 = guard.enforcePhase1(source, 2000, 3.5);
        assertFalse(phase1.trimmed());
        assertEquals(guard.estimateTokens(control, 3.5), phase1.tokensBefore());
        assertEquals(source, phase1.messages());
        assertSame(oldUser, phase1.messages().getFirst());
        assertSame(image, ((Message.UserMessage) phase1.messages().getFirst()).content().getFirst());

        var originalApi = api(source);
        JsonNode before = mapper.valueToTree(originalApi);
        var phase2 = guard.enforcePhase2(originalApi, 2000, Map.of(), Set.of(), 3.5);
        assertTrue(phase2.fitsBudget());
        assertEquals("无需降级", phase2.reductionSummary());
        assertEquals(guard.estimateApiTokens(api(control), 3.5), phase2.estimatedTokens());
        assertSame(originalApi, phase2.apiMessages());
        assertEquals(before, mapper.valueToTree(originalApi));
        assertEquals(item, saved.outputItems().getFirst());
        assertEquals(content, ((Message.AssistantMessage) source.get(1)).content());
    }

    @Test
    void supplementalStateCannotHideLongPublicAnswerFromEitherBudgetStage() {
        for (boolean signed : List.of(false, true)) {
            var saved = state("zenmux", MODEL, List.of(reasoningItem(signed)));
            var text = new ContentBlock.TextBlock("long public answer ".repeat(1500));
            List<Message> source = List.of(assistant(List.of(text, saved)));
            assertTrue(counter.estimateTokens(source) > 2000);
            assertTrue(counter.estimateTokens(source, MODEL) > 2000);
            var phase1 = guard.enforcePhase1(source, 2000, 3.5);
            assertTrue(phase1.tokensBefore() > 2000);
            assertTrue(phase1.tokensAfter() > 2000);
            var phase2 = guard.enforcePhase2(api(source), 2000, Map.of(), Set.of(), 3.5);
            assertFalse(phase2.fitsBudget());
            assertEquals(text, ((Message.AssistantMessage) source.getFirst()).content().getFirst());
            assertSame(saved, ((Message.AssistantMessage) source.getFirst()).content().getLast());
        }
    }

    @Test
    void foreignUnknownAndMixedStatesKeepOpaqueAccounting() {
        var reasoning = reasoningItem(true);
        var unknown = mapper.createObjectNode().put("type", "future_state").put("data", "x".repeat(200));
        for (var saved : List.of(
                state("openrouter", MODEL, List.of(reasoning)),
                state("zenmux", "openai/gpt-6-astra", List.of(reasoning)),
                state("zenmux", MODEL, List.of(unknown)),
                state("zenmux", MODEL, List.of(reasoning, unknown)),
                state("zenmux", MODEL, List.of(reasoning, nativeItem())))) {
            var source = List.<Message>of(assistant(List.of(new ContentBlock.TextBlock("public"), saved)));
            int opaque = saved.outputItems().stream().mapToInt(item -> item.toString().length()).sum();
            assertEquals(opaque + 4, counter.estimateTokens(source, MODEL));
            assertEquals(opaque, guard.estimateTokens(source, 3.5));
            var apiState = Map.<String, Object>of("type", "provider_response_state",
                    "provider", saved.provider(), "model", saved.model(), "output", saved.outputItems());
            assertEquals(saved.outputItems().toString().length(), guard.estimateApiTokens(
                    List.of(Map.of("role", "assistant", "content", List.of(apiState))), 3.5));
        }
    }

    @Test
    void retiredSnapshotDoesNotCountInEarlyBudgets() {
        var content = new ArrayList<>(publicContent());
        content.add(state("zenmux", MODEL, List.of(nativeItem())));
        var source = history(content);
        var control = history(publicContent());
        assertEquals(counter.estimateTokens(control), counter.estimateTokens(source));
        assertEquals(counter.estimateTokens(control, MODEL), counter.estimateTokens(source, MODEL));
        assertEquals(guard.estimateTokens(control, 3.5), guard.estimateTokens(source, 3.5));
        assertEquals(guard.estimateApiTokens(api(control), 3.5), guard.estimateApiTokens(api(source), 3.5));
    }

    private ObjectNode reasoningItem(boolean signed) {
        ObjectNode item = mapper.createObjectNode().put("type", "zenmux_claude_reasoning");
        var details = item.putArray("reasoning_details");
        if (signed) details.addObject().put("type", "reasoning.text")
                .put("text", "private reasoning").put("signature", "opaque-signature");
        item.putObject("replay").put("version", 1).put("id", "fixture")
                .put("parent", "").put("prefix", "p".repeat(64)).put("public", "s".repeat(64));
        return item;
    }

    private ObjectNode nativeItem() {
        ObjectNode item = mapper.createObjectNode().put("type", "zenmux_claude_message");
        item.putArray("content").addObject().put("type", "text").put("text", "old snapshot ".repeat(4000));
        return item; // Both pre-binding and replay-metadata versions use this retired format.
    }

    private ContentBlock.ProviderResponseStateBlock state(String provider, String model, List<JsonNode> output) {
        return new ContentBlock.ProviderResponseStateBlock(provider, model, "", output);
    }

    private List<ContentBlock> publicContent() {
        return List.of(new ContentBlock.TextBlock("current public answer ".repeat(8)),
                new ContentBlock.ToolUseBlock("call", "Read",
                        mapper.createObjectNode().put("path", "/fixture/" + "part".repeat(20))));
    }

    private Message.AssistantMessage assistant(List<ContentBlock> content) {
        return new Message.AssistantMessage("assistant", Instant.EPOCH, content, "tool_use", null);
    }

    private List<Message> history(List<ContentBlock> content) {
        return List.of(assistant(content), new Message.UserMessage("result", Instant.EPOCH,
                List.of(new ContentBlock.ToolResultBlock("call", "fixture result", false)), null, null));
    }

    private List<Map<String, Object>> api(List<Message> history) {
        return MessageParamConverter.toMaps(new MessageNormalizer().normalizeTyped(history));
    }
}
