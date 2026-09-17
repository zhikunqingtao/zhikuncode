package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompactionHistoryRecoveryTest {
    private static Message.AssistantMessage call(String uuid, String... ids) {
        List<ContentBlock> blocks = java.util.Arrays.stream(ids)
                .<ContentBlock>map(id -> new ContentBlock.ToolUseBlock(id, "Bash", new ObjectMapper().createObjectNode()))
                .toList();
        return new Message.AssistantMessage(uuid, Instant.EPOCH, blocks, "tool_use", null);
    }

    private static Message.UserMessage result(String uuid, String id, String text) {
        return new Message.UserMessage(uuid, Instant.EPOCH,
                List.of(new ContentBlock.ToolResultBlock(id, text, false)), null, null);
    }

    @Test void missingResultIsRecoveredOnlyInRequestCopyAndProjectionIsIdempotent() {
        var assistant = call("assistant", "call");
        var latest = new Message.UserMessage("latest", Instant.EPOCH,
                List.of(new ContentBlock.TextBlock("Continue")), null, null);
        var source = List.<Message>of(assistant, latest);
        String fingerprint = CompactionHistory.fingerprint(source);
        var projected = CompactionHistory.forRequest(source);

        assertEquals(3, projected.size());
        assertEquals(assistant, projected.getFirst());
        assertEquals(latest, projected.getLast());
        var placeholder = (ContentBlock.ToolResultBlock) ((Message.UserMessage) projected.get(1)).content().getFirst();
        assertEquals("call", placeholder.toolUseId());
        assertTrue(placeholder.isError());
        assertTrue(placeholder.content().contains("execution outcome unknown"));
        assertTrue(placeholder.content().contains("Side effects may have occurred; verify before retrying"));
        assertEquals(projected, CompactionHistory.forRequest(source));
        assertEquals(projected, CompactionHistory.forRequest(projected));
        assertEquals(fingerprint, CompactionHistory.fingerprint(source));
        assertDoesNotThrow(() -> CompactionHistory.analyze(projected));
        assertThrows(IllegalArgumentException.class, () -> CompactionHistory.analyze(source));
    }

    @Test void partialMultiCallPreservesRealResultAndOnlyFillsMissingIdsInCallOrder() {
        var assistant = call("assistant", "missing-a", "done", "missing-b");
        var real = result("real", "done", "actual evidence");
        var projected = CompactionHistory.forRequest(List.of(assistant, real));
        var placeholders = ((Message.UserMessage) projected.get(1)).content().stream()
                .map(ContentBlock.ToolResultBlock.class::cast).toList();
        assertEquals(List.of("missing-a", "missing-b"), placeholders.stream().map(ContentBlock.ToolResultBlock::toolUseId).toList());
        assertEquals(real, projected.getLast());
        assertDoesNotThrow(() -> CompactionHistory.analyze(projected));
    }

    @Test void completeAndLegacyHistoriesDoNotReceivePlaceholders() {
        var assistant = call("assistant", "done");
        var complete = List.<Message>of(assistant, result("result", "done", "actual evidence"));
        assertEquals(complete, CompactionHistory.forRequest(complete));
        var legacy = new Message.UserMessage("legacy", Instant.EPOCH, List.of(), "actual evidence", "assistant");
        var projected = CompactionHistory.forRequest(List.of(assistant, legacy));
        assertEquals(2, projected.size());
        assertEquals(List.of(new ContentBlock.ToolResultBlock("done", "actual evidence", false)),
                ((Message.UserMessage) projected.getLast()).content());
    }

    @Test void recoveryIdentityDoesNotCollideWithExistingMessage() {
        var projected = CompactionHistory.forRequest(List.of(call("assistant", "missing"),
                new Message.UserMessage("missing-tool-result:assistant", Instant.EPOCH, List.of(), null, null)));
        assertEquals(3, projected.stream().map(Message::uuid).distinct().count());
        assertEquals(projected, CompactionHistory.forRequest(projected));
    }

    @Test void missingResultDoesNotHideOtherInvalidHistory() {
        var missing = call("missing", "missing-id");
        var assistant = call("assistant", "done");
        var real = result("result", "done", "actual evidence");
        var conflict = new Message.UserMessage("result", Instant.EPOCH, real.content(), "different", "done");
        List<List<Message>> invalid = List.of(
                List.of(missing, call("other", "missing-id")),
                List.of(missing, call("missing", "other")),
                List.of(missing, result("orphan", "unknown", "result")),
                List.of(missing, assistant, real, result("duplicate", "done", "actual evidence")),
                List.of(missing, assistant, conflict),
                List.of(missing, call("multi", "one", "two"),
                        new Message.UserMessage("legacy", Instant.EPOCH, List.of(), "result", "multi")),
                List.of(assistant, missing, real));
        for (var history : invalid) {
            assertThrows(IllegalArgumentException.class, () -> CompactionHistory.forRequest(history));
        }
    }
}
