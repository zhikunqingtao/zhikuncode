package com.aicodeassistant.tool.process;
import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.interaction.DurableInteractionService;
import com.aicodeassistant.run.*;
import com.aicodeassistant.tool.StreamingToolExecutor;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class GroundTruthParentWindowTest {
 @Test void sameWindowBeforeChangeHasNoStaleActiveReservation() throws Exception {
  var registry=spy(new RunExecutionRegistry()); registry.register("window","session",new AbortContext());
  var runner=new ManagedProcessRunner(registry);
  var paused=new CountDownLatch(1); var resume=new CountDownLatch(1);
  Process process=mock(Process.class);
  when(process.getInputStream()).thenReturn(InputStream.nullInputStream());
  when(process.getErrorStream()).thenReturn(InputStream.nullInputStream());
  when(process.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
  when(process.waitFor(anyLong(),any(TimeUnit.class))).thenReturn(true);
  var runs=mock(RunControlService.class); var interactions=mock(DurableInteractionService.class); var toolExecutor=mock(StreamingToolExecutor.class);
  when(interactions.beginRunTermination(anyString(),any(),anyString())).thenReturn(new DurableInteractionService.CancellationResult(RunControlService.TransitionResult.APPLIED,0));
  when(toolExecutor.cancelRunDetailed("window")).thenReturn(new StreamingToolExecutor.ToolCancelSummary(0,0,0));
  when(runs.cancel("window")).thenReturn(RunControlService.TransitionResult.APPLIED);
  doAnswer(i->{resume.countDown();return i.callRealMethod();}).when(registry).awaitQuiescence(eq("window"),any());
  try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
    var launched=executor.submit(()->{
      try(var builders=mockConstruction(ProcessBuilder.class,(mock,context)->when(mock.start()).thenAnswer(i->{paused.countDown();assertThat(resume.await(3,TimeUnit.SECONDS)).isTrue();return process;}))) {
        return runner.run(new ManagedProcessRunner.Request(List.of("true"),Path.of(System.getProperty("java.io.tmpdir")),Duration.ofSeconds(3),"window","tool"));
      }
    });
    assertThat(paused.await(3,TimeUnit.SECONDS)).isTrue();
    var result=new RunTerminationCoordinator(runs,interactions,runner,registry,toolExecutor).cancelByUser("window","probe");
    assertThat(launched.get(3,TimeUnit.SECONDS).cancelled()).isTrue();
    assertThat(registry.awaitQuiescence("window",Duration.ZERO)).isTrue();
    verify(runs).cancel("window"); verify(runs,never()).fail(anyString(),any(),anyString());
    System.out.println("PROBE parent launch-summary: quiescent=true cached="+result.processes()+" terminal=CANCELLED confirmation="+result.terminationConfirmed());
  } finally { resume.countDown(); runner.shutdown(); }
 }
}
