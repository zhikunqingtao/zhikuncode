package com.aicodeassistant.run;

import com.aicodeassistant.engine.AbortReason;
import com.aicodeassistant.interaction.DurableInteractionService;
import com.aicodeassistant.tool.StreamingToolExecutor;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunTerminationCoordinatorTest {
    @Test
    void userCancellationUsesOneOrderedTerminationPath() {
        RunControlService runs = mock(RunControlService.class);
        DurableInteractionService interactions = mock(DurableInteractionService.class);
        ManagedProcessRunner processes = mock(ManagedProcessRunner.class);
        RunExecutionRegistry executions = mock(RunExecutionRegistry.class);
        StreamingToolExecutor tools = mock(StreamingToolExecutor.class);
        when(interactions.beginRunTermination("run", RunEnvelope.RunExitReason.USER_CANCELLED, "user"))
                .thenReturn(new DurableInteractionService.CancellationResult(
                        RunControlService.TransitionResult.APPLIED, 1));
        when(processes.cancelRunDetailed("run"))
                .thenReturn(new ManagedProcessRunner.CancelSummary(1, 1, 0));
        when(tools.cancelRunDetailed("run"))
                .thenReturn(new StreamingToolExecutor.ToolCancelSummary(1, 1, 0));
        when(executions.awaitQuiescence(eq("run"), any())).thenReturn(true);
        when(runs.cancel("run")).thenReturn(RunControlService.TransitionResult.APPLIED);

        RunTerminationCoordinator.Result result = new RunTerminationCoordinator(
                runs, interactions, processes, executions, tools).cancelByUser("run", "user");

        assertEquals(RunControlService.TransitionResult.APPLIED, result.transition());
        assertTrue(result.terminationConfirmed());
        InOrder order = inOrder(interactions, executions, tools, processes, runs);
        order.verify(interactions).beginRunTermination("run", RunEnvelope.RunExitReason.USER_CANCELLED, "user");
        order.verify(executions).beginTermination("run");
        order.verify(executions).abortRun("run", AbortReason.USER_INTERRUPT);
        order.verify(tools).cancelRunDetailed("run");
        order.verify(processes).cancelRunDetailed("run");
        order.verify(runs).cancel("run");
    }

    @Test
    void unconfirmedProcessTerminationOverridesRequestedTerminalReason() {
        RunControlService runs = mock(RunControlService.class);
        DurableInteractionService interactions = mock(DurableInteractionService.class);
        ManagedProcessRunner processes = mock(ManagedProcessRunner.class);
        RunExecutionRegistry executions = mock(RunExecutionRegistry.class);
        StreamingToolExecutor tools = mock(StreamingToolExecutor.class);
        when(interactions.beginRunTermination(anyString(), any(), anyString()))
                .thenReturn(new DurableInteractionService.CancellationResult(
                        RunControlService.TransitionResult.APPLIED, 0));
        when(processes.cancelRunDetailed("run"))
                .thenReturn(new ManagedProcessRunner.CancelSummary(2, 1, 1));
        when(tools.cancelRunDetailed("run"))
                .thenReturn(new StreamingToolExecutor.ToolCancelSummary(0, 0, 0));
        when(executions.awaitQuiescence(eq("run"), any())).thenReturn(true);
        when(runs.fail(eq("run"), eq(RunEnvelope.RunExitReason.PROCESS_TERMINATION_UNCONFIRMED), anyString()))
                .thenReturn(RunControlService.TransitionResult.APPLIED);

        RunTerminationCoordinator.Result result = new RunTerminationCoordinator(
                runs, interactions, processes, executions, tools)
                .terminate("run", RunEnvelope.RunExitReason.DEADLINE_EXCEEDED, "deadline");

        assertFalse(result.terminationConfirmed());
        verify(runs).fail(eq("run"), eq(RunEnvelope.RunExitReason.PROCESS_TERMINATION_UNCONFIRMED),
                contains("1 supervised process"));
        verify(runs, never()).cancel(anyString());
    }

    @Test
    void failedDbClaimPerformsNoExternalCancellation() {
        RunControlService runs = mock(RunControlService.class);
        DurableInteractionService interactions = mock(DurableInteractionService.class);
        ManagedProcessRunner processes = mock(ManagedProcessRunner.class);
        RunExecutionRegistry executions = mock(RunExecutionRegistry.class);
        StreamingToolExecutor tools = mock(StreamingToolExecutor.class);
        when(interactions.beginRunTermination(anyString(), any(), anyString()))
                .thenReturn(new DurableInteractionService.CancellationResult(
                        RunControlService.TransitionResult.ALREADY_TERMINAL, 0));

        RunTerminationCoordinator.Result result = new RunTerminationCoordinator(
                runs, interactions, processes, executions, tools).cancelByUser("run", "late");

        assertEquals(RunControlService.TransitionResult.ALREADY_TERMINAL, result.transition());
        verifyNoInteractions(processes, executions, tools, runs);
    }
}
