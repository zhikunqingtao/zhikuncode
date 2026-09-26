package com.aicodeassistant.engine;

import com.aicodeassistant.llm.*;
import com.aicodeassistant.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextCompactorTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String SUMMARY = "The /repo/main.java task remains pending; tests failed, deployment is not authorized. Keep the latest user corrections and verify before claiming completion. ".repeat(3);
    final MockEnvironment env = new MockEnvironment();
    final LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
    final LlmProvider provider = mock(LlmProvider.class);
    final ModelRegistry models = mock(ModelRegistry.class);
    final TokenCounter counter = new TokenCounter(null, null, null);
    ContextCompactor compactor;
    @BeforeEach void setup() {
        when(registry.findProviderByName("deepseek")).thenReturn(Optional.of(provider));
        when(provider.supportsSummary(any(), any())).thenReturn(true);
        when(models.findExplicitCapabilities(any(), eq(provider))).thenReturn(Optional.of(
                new ModelCapabilities("deepseek-v4.1-flash", "DS", 32768, 1000000, true, true, false, 0, false, 0, 0)));
        when(provider.summarize(any(), any())).thenReturn(new SummaryResult("<summary>" + SUMMARY + "</summary>", "stop", null, "DS", "id", null));
        compactor = new ContextCompactor(counter, registry, models, new CompactConfiguration(env));
    }
    @Test void faithfulInputAndOneSummaryDeliveredWithoutStateMutation() {
        var source = history(); String before = CompactionHistory.fingerprint(source);
        var r = compactor.compact(source, context(12000), false);
        assertEquals("llm_summary", r.mode()); assertNull(r.skipReason());
        var request = org.mockito.ArgumentCaptor.forClass(SummaryRequest.class);
        verify(provider).summarize(request.capture(), any());
        assertTrue(request.getValue().userContent().contains("TAIL_UNIQUE_FAILURE"));
        assertTrue(request.getValue().userContent().contains("MID_UNIQUE_VALUE"));
        assertTrue(request.getValue().userContent().contains("/repo/main.java"));
        assertTrue(request.getValue().userContent().contains("isError=true"));
        assertFalse(request.getValue().systemPrompt().contains("<analysis>"));
        assertEquals(before, CompactionHistory.fingerprint(source));
        var projected = CompactionHistory.forRequest(r.compactedMessages());
        assertEquals(projected, CompactionHistory.forRequest(r.compactedMessages()));
        assertEquals(1, projected.stream().filter(m -> m.toString().contains(SUMMARY.trim())).count());
        assertTrue(r.compactedMessages().contains(source.getFirst()));
    }
    @Test void incompleteOrMalformedOrOversizeOutputsUseWholeTransactionFallback() {
        for (var result : List.of(new SummaryResult("<summary>" + SUMMARY + "</summary>", "length", null, null, null, null),
                new SummaryResult("<summary>" + SUMMARY, "stop", null, null, null, null),
                new SummaryResult("<summary>" + SUMMARY + "</summary>", null, null, null, null, null),
                new SummaryResult("<summary>" + SUMMARY.repeat(100) + "</summary>", "stop", null, null, null, null))) {
            when(provider.summarize(any(), any())).thenReturn(result);
            var r = compactor.compact(history(), context(12000), false);
            assertEquals("key_selection", r.mode());
            assertDoesNotThrow(() -> CompactionHistory.analyze(r.compactedMessages()));
        }
    }
    @Test void noMandatoryCapacityNoNetwork() {
        var source = history();
        var r = compactor.compact(source, context(100), false);
        assertEquals("mandatory_context_over_budget", r.skipReason()); verify(provider, never()).summarize(any(), any());
    }
    @Test void disabledStillProjectsOldSummaryAndUsesLocalSelection() {
        env.setProperty("app.compact.llm-enabled", "false");
        var source = new ArrayList<Message>();
        source.add(new Message.SystemMessage("old", Instant.EPOCH, "old reference", SystemMessageType.COMPACT_SUMMARY));
        source.addAll(history());
        var r = compactor.compact(source, context(12000), false);
        assertEquals("key_selection", r.mode()); verify(provider, never()).summarize(any(), any());
        assertTrue(CompactionHistory.forRequest(r.compactedMessages()).toString().contains("old reference"));
    }
    @Test void cancelledGenerationDoesNotApplyFallback() {
        var signal = new AbortContext();
        var context = new CompactionContext("executor", 12000, 11000, 3.5, new LlmCallContext("cancel", signal), Long.MAX_VALUE, () -> true);
        when(provider.summarize(any(), any())).thenAnswer(inv -> { signal.abort(AbortReason.USER_INTERRUPT); return SummaryResult.failed("late"); });
        assertThrows(CancellationException.class, () -> compactor.compact(history(), context, false));
    }
    @Test void sourceMutationDiscardsCandidate() {
        var source = new ArrayList<>(history());
        when(provider.summarize(any(), any())).thenAnswer(inv -> {
            source.add(user("new", "latest correction"));
            return new SummaryResult("<summary>" + SUMMARY + "</summary>", "stop", null, null, null, null);
        });
        assertEquals("source_changed", compactor.compact(source, context(12000), false).skipReason());
    }
    @Test void summaryWindowCheckedIndependentlyFromExecutionWindow() {
        when(models.findExplicitCapabilities(any(), eq(provider))).thenReturn(Optional.of(
                new ModelCapabilities("deepseek-v4.1-flash", "DS", 9000, 10000, true, true, false, 0, false, 0, 0)));
        var r = compactor.compact(history(), context(12000), false);
        assertEquals("summary_input_over_budget", r.failureReason()); verify(provider, never()).summarize(any(), any());
    }
    @Test void strictTags() {
        for (String s : List.of("raw text", "<summary>x", "<summary><summary id='x'>nested</summary>", "<analysis>a</analysis><summary>b</summary>",
                "<summary>a<summary>b</summary>c</summary>", "<summary>x</summary>suffix")) assertNull(ContextCompactor.extract(s));
        assertEquals("literal &lt;summary&gt;", ContextCompactor.extract("<summary>literal &lt;summary&gt;</summary>"));
    }
    @Test void legacyAssistantReferenceProjectsRealCallIdAndPreservesMixedText() {
        var call = call("assistant", "tool", 10);
        var result = new Message.UserMessage("result", Instant.EPOCH, List.of(new ContentBlock.TextBlock("user correction")), "failed", "assistant");
        var a = CompactionHistory.analyze(List.of(call, result));
        var u = (Message.UserMessage)a.canonical().getLast();
        assertNull(u.toolUseResult());
        assertTrue(u.content().contains(new ContentBlock.ToolResultBlock("tool", "failed", false)));
        assertTrue(a.units().getFirst().userContent());
        assertTrue(CompactionHistory.format(List.of(result), a).contains("isError=unknown"));
    }
    @Test void mirroredTypedErrorWinsButConflictsAndOrphansReject() {
        var c = call("a", "t", 10);
        var result = new Message.UserMessage("r", Instant.EPOCH, List.of(new ContentBlock.ToolResultBlock("t", "failure", true)), "failure", "t");
        assertEquals(1, ((Message.UserMessage)CompactionHistory.analyze(List.of(c,result)).canonical().getLast()).content().size());
        var conflict = new Message.UserMessage("r", Instant.EPOCH, result.content(), "different", "t");
        assertThrows(IllegalArgumentException.class, () -> CompactionHistory.analyze(List.of(c,conflict)));
        assertThrows(IllegalArgumentException.class, () -> CompactionHistory.analyze(List.of(result)));
        assertThrows(IllegalArgumentException.class, () -> CompactionHistory.analyze(List.of(c)));
    }
    @Test void multiCallIsIndivisibleAndLegacyMultiCallIsAmbiguous() {
        var c = new Message.AssistantMessage("a", Instant.EPOCH, List.of(new ContentBlock.ToolUseBlock("x","Bash",JSON.createObjectNode()),
                new ContentBlock.ToolUseBlock("y","Bash",JSON.createObjectNode())),null,null);
        var x = result("rx","x","one"); var y = result("ry","y","two");
        assertEquals(List.of(new CompactionHistory.Unit(0,3,true,false)), CompactionHistory.analyze(List.of(c,x,y)).units());
        assertThrows(IllegalArgumentException.class, () -> CompactionHistory.analyze(List.of(c,new Message.UserMessage("r",Instant.EPOCH,List.of(),"one","a"))));
    }
    @Test void frozenBoundaryExtendsAcrossWholeTransactionAndKeepsMixedUserText() {
        var source=history();
        source.add(2,new Message.SystemMessage("frozen",Instant.EPOCH,"previous summary",SystemMessageType.COMPACT_SUMMARY));
        source.add(5,user("authorization","Never deploy without renewed permission."));
        var r=compactor.compact(source,context(20000),false);
        assertNull(r.skipReason());
        assertEquals(source.subList(0,4),r.compactedMessages().subList(0,4));
        assertTrue(r.compactedMessages().contains(source.get(5)));
        assertDoesNotThrow(()->CompactionHistory.analyze(r.compactedMessages()));
    }
    @Test void onePhaseNeverStartsAnotherLogicalSummaryAfterFailure() {
        when(provider.summarize(any(),any())).thenReturn(SummaryResult.failed("summary_timeout"));
        var context=context(12000);
        assertEquals("key_selection",compactor.compact(history(),context,false).mode());
        assertEquals("summary_already_attempted",compactor.compact(history(),context,true).failureReason());
        verify(provider,times(1)).summarize(any(),any());
    }
    @Test void invalidOptionalConfigurationAndUnknownCapacityNeverInvokeProvider() {
        env.setProperty("app.compact.timeout-ms","not-a-number");
        assertEquals("invalid_summary_configuration",compactor.compact(history(),context(12000),false).failureReason());
        env.setProperty("app.compact.timeout-ms","90000");
        when(models.findExplicitCapabilities(any(),any())).thenReturn(Optional.empty());
        assertEquals("summary_capacity_unknown",compactor.compact(history(),context(12000),false).failureReason());
        verify(provider,never()).summarize(any(),any());
    }
    @Test void outputModelRatioControlsSummaryCapAndInputModelWindowStaysSeparate() {
        var tight=new CompactionContext("executor",12000,11000,0.6,LlmCallContext.unscoped(),Long.MAX_VALUE,()->true);
        env.setProperty("app.compact.max-summary-tokens","500");
        var r=compactor.compact(history(),tight,false);
        assertEquals("summary_over_budget",r.failureReason());
        assertEquals("key_selection",r.mode());
    }

    @Test void lateCompleteSummaryIsRejectedWithoutCancellingParent() {
        var signal=new AbortContext();
        var expired=new CompactionContext("executor",12000,11000,3.5,new LlmCallContext("expired",signal),0,()->true);
        var r=compactor.compact(history(),expired,false);
        assertEquals("key_selection",r.mode());assertEquals("summary_timeout",r.failureReason());
        assertFalse(signal.isCancelled());
    }
    @Test void endedRunCannotPublishFallback() {
        var active=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(provider.summarize(any(),any())).thenAnswer(inv->{active.set(false);return SummaryResult.failed("failure");});
        var context=new CompactionContext("executor",12000,11000,3.5,LlmCallContext.unscoped(),Long.MAX_VALUE,active::get);
        assertThrows(IllegalStateException.class,()->compactor.compact(history(),context,false));
    }
    @Test void legacyNamespaceCollisionAndDuplicateCallIdsAreRejected() {
        var a=call("collision","call-a",1);var b=call("b","collision",1);
        assertThrows(IllegalArgumentException.class,()->CompactionHistory.analyze(List.of(a,result("ra","call-a","a"),b,
            new Message.UserMessage("rb",Instant.EPOCH,List.of(),"b","collision"))));
        assertThrows(IllegalArgumentException.class,()->CompactionHistory.analyze(List.of(a,call("b","call-a",1))));
    }

    @Test void repeatedLocalSelectionDoesNotFreezeOptionalTransactions() {
        env.setProperty("app.compact.llm-enabled", "false");
        var source = new ArrayList<Message>();
        var goal = user("goal", "Inspect /repo/main.java. Do not deploy.");
        source.add(goal);
        for (int i = 0; i < 20; i++) {
            source.add(call("a"+i, "t"+i, 0));
            source.add(result("r"+i, "t"+i, "x".repeat(1400)));
        }
        var first = compactor.compact(source, context(6000), false);
        assertEquals("key_selection", first.mode());
        assertTrue(first.compactedMessages().stream().anyMatch(m -> m instanceof Message.SystemMessage sys
                && sys.type() == SystemMessageType.COMPACT_OMISSION));
        source = new ArrayList<>(first.compactedMessages());
        for (int round = 0; round < 5; round++) {
            for (int j = 0; j < 6; j++) {
                String id = round + "-" + j;
                source.add(call("a"+id, "t"+id, 0));
                source.add(result("r"+id, "t"+id, "x".repeat(5000)));
            }
            var recent = List.copyOf(source.subList(source.size()-6, source.size()));
            var next = compactor.compact(source, context(6000), false);
            assertEquals("key_selection", next.mode(), next.skipReason());
            assertTrue(next.compactedMessages().contains(goal));
            assertTrue(next.compactedMessages().containsAll(recent));
            assertTrue(next.afterTokens() <= 5700);
            assertDoesNotThrow(() -> CompactionHistory.analyze(next.compactedMessages()));
            source = new ArrayList<>(next.compactedMessages());
        }
        verify(provider, never()).summarize(any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void oldToolTransactionDoesNotFreezeLaterTextHistory(boolean reactive) {
        env.setProperty("app.compact.llm-enabled", "false");
        for (boolean earlyTool : List.of(false, true)) {
            var source = new ArrayList<Message>();
            source.add(user("goal", "Investigate only; do not deploy."));
            if (earlyTool) {
                source.add(call("early-call", "early-tool", 10));
                source.add(result("early-result", "early-tool", "early evidence"));
            }
            for (int i = 0; i < 20; i++) {
                source.add(user("u" + i, "Continue investigation " + i));
                source.add(new Message.AssistantMessage("a" + i, Instant.EPOCH,
                        List.of(new ContentBlock.TextBlock("analysis ".repeat(1050))), null, null));
            }
            String fingerprint = CompactionHistory.fingerprint(source);
            var context = context(12000);
            assertTrue(counter.estimateTokens(CompactionHistory.forRequest(source), "executor") > context.historyBudget());
            var recent = List.copyOf(source.subList(source.size() - (reactive ? 1 : 3), source.size()));
            var result = compactor.compact(source, context, reactive);
            assertNull(result.skipReason(), "earlyTool=" + earlyTool + ": " + result.skipReason());
            assertEquals("key_selection", result.mode());
            assertTrue(result.afterTokens() <= context.historyBudget());
            assertTrue(result.afterTokens() < result.beforeTokens());
            assertTrue(result.compactedMessages().containsAll(recent));
            source.stream().filter(Message.UserMessage.class::isInstance)
                    .map(Message.UserMessage.class::cast)
                    .filter(m -> m.content().stream().anyMatch(ContentBlock.TextBlock.class::isInstance))
                    .forEach(m -> assertTrue(result.compactedMessages().contains(m)));
            assertDoesNotThrow(() -> CompactionHistory.analyze(result.compactedMessages()));
            if (earlyTool) {
                assertEquals(result.compactedMessages().contains(source.get(1)),
                        result.compactedMessages().contains(source.get(2)), "Tool transaction must remain indivisible");
            }
            assertEquals(fingerprint, CompactionHistory.fingerprint(source));
        }
        verify(provider, never()).summarize(any(), any());
    }

    @Test void omissionNoticeProjectsOnceWithoutMutatingHistory() {
        var notice = new Message.SystemMessage("omission", Instant.EPOCH, "older records omitted", SystemMessageType.COMPACT_OMISSION);
        var history = List.<Message>of(notice, user("latest", "Do not deploy"));
        var projected = CompactionHistory.forRequest(history);
        assertEquals(projected, CompactionHistory.forRequest(history));
        assertEquals(1, projected.stream().filter(m -> m.toString().contains("older records omitted")).count());
        assertTrue(projected.toString().contains("not a factual summary"));
        assertSame(notice, history.getFirst());
    }

    static CompactionContext context(int window) { return new CompactionContext("executor",window,(int)(window*.95),3.5,LlmCallContext.unscoped(),Long.MAX_VALUE,()->true); }
    static List<Message> history() {
        var messages = new ArrayList<Message>(); messages.add(user("goal","Fix /repo/main.java, do not deploy. Latest user instructions remain authoritative."));
        for (int i=0;i<8;i++) {
            messages.add(call("a"+i,"t"+i,1400));
            messages.add(result("r"+i,"t"+i,"log ".repeat(700)+"MID_UNIQUE_VALUE"+" log".repeat(700)+"TAIL_UNIQUE_FAILURE"));
        }
        return messages;
    }
    static Message user(String id,String text) { return new Message.UserMessage(id,Instant.EPOCH,List.of(new ContentBlock.TextBlock(text)),null,null); }
    static Message call(String id,String tool,int size) { return new Message.AssistantMessage(id,Instant.EPOCH,List.of(new ContentBlock.ToolUseBlock(tool,"Bash",JSON.createObjectNode().put("path","/repo/main.java").put("command","x".repeat(size)))),null,null); }
    static Message result(String id,String tool,String text) { return new Message.UserMessage(id,Instant.EPOCH,List.of(new ContentBlock.ToolResultBlock(tool,text,true)),text,tool); }
}
