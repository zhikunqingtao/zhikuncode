package com.aicodeassistant.engine;

import com.aicodeassistant.model.*;
import com.aicodeassistant.run.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunResultProjectionTest {
    private final RunTracker tracker = mock(RunTracker.class);
    private final QueryEngine.QueryResult partial = new QueryEngine.QueryResult(
            List.of(new Message.AssistantMessage("partial", Instant.EPOCH, List.of(new ContentBlock.TextBlock("partial work")), "end_turn", null)),
            new Usage(20, 30, 0, 0), "end_turn", null, 2);
    @ParameterizedTest
    @CsvSource({"CANCELLED,USER_CANCELLED,cancelled", "FAILED,DEADLINE_EXCEEDED,timeout",
            "FAILED,TOOL_TERMINATION_UNCONFIRMED,error", "FAILED,PROCESS_TERMINATION_UNCONFIRMED,error"})
    void committedTerminalWinsOverGracefulReturn(String status, String reason, String expected) {
        var run = terminal(status, reason);
        when(tracker.getRun("run")).thenReturn(Optional.of(run));
        var result = RunResultProjection.resolve(partial, tracker, "run", AbortReason.TIMEOUT);
        assertEquals(expected, result.stopReason()); assertFalse(result.isSuccess());
        assertEquals(partial.messages(), result.messages()); assertEquals(partial.totalUsage(), result.totalUsage());
        assertTrue(result.error().contains(reason));
    }
    @Test void committedCompletionWinsOverLateCancel() {
        var committed = terminal("COMPLETED", "MODEL_FINISHED");
        when(tracker.getRun("run")).thenReturn(Optional.of(committed));
        assertTrue(RunResultProjection.resolve(partial, tracker, "run", AbortReason.USER_INTERRUPT).isSuccess());
        verify(tracker, never()).abortRun(any(), any(), any());
    }
    @Test void missingAuthorityNeverClaimsStopped() {
        assertTrue(RunResultProjection.resolve(partial, null, null, AbortReason.TIMEOUT).error().contains("UNCONFIRMED"));
        assertTrue(RunResultProjection.resolve(partial, null, null, null).isSuccess());
    }
    @Test void missingOrUnreadableOrNonterminalRunFailsClosed() {
        when(tracker.getRun("run")).thenReturn(Optional.empty());
        assertUnconfirmed();
        var cancelling = terminal("CANCELLING", "USER_CANCELLED");
        when(tracker.getRun("run")).thenReturn(Optional.of(cancelling));
        assertUnconfirmed();
        when(tracker.getRun("run")).thenThrow(new IllegalStateException("read failed"));
        assertUnconfirmed();
    }
    @Test void flagsCannotAccidentallyBecomeSuccessWithoutError() {
        for (String stop : List.of("cancelled", "aborted", "timeout"))
            assertFalse(new QueryEngine.QueryResult(List.of(), Usage.zero(), stop, null, 1).isSuccess());
    }
    private void assertUnconfirmed() {
        var r = RunResultProjection.resolve(partial, tracker, "run", AbortReason.TIMEOUT);
        assertEquals("error", r.stopReason()); assertTrue(r.error().contains("RUN_TERMINATION_UNCONFIRMED"));
    }
    private RunEnvelope terminal(String status, String reason) {
        var run = mock(RunEnvelope.class);
        when(run.status()).thenReturn(RunEnvelope.RunStatus.valueOf(status));
        lenient().when(run.exitReason()).thenReturn(RunEnvelope.RunExitReason.valueOf(reason));
        lenient().when(run.requestedExitReason()).thenReturn(RunEnvelope.RunExitReason.DEADLINE_EXCEEDED);
        return run;
    }
}
