package com.aicodeassistant.tool.process;

import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.interaction.DurableInteractionService;
import com.aicodeassistant.run.*;
import com.aicodeassistant.tool.StreamingToolExecutor;
import com.aicodeassistant.tool.bash.ShellStateManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class GroundTruthLifecycleProbeTest {
  @Test void staleLaunchSummarySurvivesRealQuiescence() throws Exception {
    var registry = spy(new RunExecutionRegistry());
    registry.register("window", "window-session", new AbortContext());
    var runner = new ManagedProcessRunner(registry);
    var paused = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    OwnedProcess process = mock(OwnedProcess.class);
    when(process.getInputStream()).thenReturn(InputStream.nullInputStream());
    when(process.getErrorStream()).thenReturn(InputStream.nullInputStream());
    when(process.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
    when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
    when(process.terminate(anyLong(), anyLong(), eq(false))).thenReturn(true);
    var runs = mock(RunControlService.class);
    var interactions = mock(DurableInteractionService.class);
    var toolExecutor = mock(StreamingToolExecutor.class);
    when(interactions.beginRunTermination(anyString(), any(), anyString())).thenReturn(
      new DurableInteractionService.CancellationResult(RunControlService.TransitionResult.APPLIED, 0));
    when(toolExecutor.cancelRunDetailed("window")).thenReturn(new StreamingToolExecutor.ToolCancelSummary(0,0,0));
    AtomicReference<RunEnvelope.RunExitReason> reason = new AtomicReference<>();
    when(runs.fail(anyString(), any(), anyString())).thenAnswer(i -> { reason.set(i.getArgument(1)); return RunControlService.TransitionResult.APPLIED; });
    doAnswer(i -> { resume.countDown(); return i.callRealMethod(); }).when(registry).awaitQuiescence(eq("window"), any());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var launched = executor.submit(() -> {
        try (var starts = mockStatic(OwnedProcess.class)) {
          starts.when(() -> OwnedProcess.start(any(ProcessBuilder.class))).thenAnswer(i -> {
            paused.countDown(); assertThat(resume.await(3, TimeUnit.SECONDS)).isTrue(); return process;
          });
          return runner.run(new ManagedProcessRunner.Request(List.of("true"), Path.of(System.getProperty("java.io.tmpdir")), Duration.ofSeconds(3), "window", "tool"));
        }
      });
      assertThat(paused.await(3, TimeUnit.SECONDS)).isTrue();
      var coordinator = new RunTerminationCoordinator(runs, interactions, runner, registry, toolExecutor);
      var result = coordinator.cancelByUser("window", "probe");
      assertThat(launched.get(3, TimeUnit.SECONDS).cancelled()).isTrue();
      assertThat(registry.awaitQuiescence("window", Duration.ZERO)).isTrue();
      assertThat(runner.cancelRunDetailed("window").activeCount()).isZero();
      assertThat(reason.get()).isEqualTo(RunEnvelope.RunExitReason.PROCESS_TERMINATION_UNCONFIRMED);
      System.out.println("PROBE launch-summary: quiescent=true activeNow=0 cached=" + result.processes() + " terminalReason=" + reason.get());
    } finally { resume.countDown(); runner.shutdown(); }
  }

  @Test void retainedLeaseHasNoRetryAfterItsOneShotCallbackAndUnregister() throws Exception {
    var registry = new RunExecutionRegistry();
    registry.register("retained", "retained-session", new AbortContext());
    var runner = new ManagedProcessRunner(registry);
    Semaphore permits = new Semaphore(1);
    ReflectionTestUtils.setField(runner, "capacity", permits);
    AtomicBoolean recovered = new AtomicBoolean(false);
    AtomicInteger calls = new AtomicInteger();
    try {
      var result = runner.run(new ManagedProcessRunner.Request(List.of("true"), Path.of(System.getProperty("java.io.tmpdir")), Duration.ofSeconds(2), "retained", "tool", d -> { calls.incrementAndGet(); return recovered.get(); }));
      assertThat(result.terminationConfirmed()).isFalse();
      registry.beginCompletion("retained");
      assertThat(registry.awaitQuiescence("retained", Duration.ZERO)).isFalse();
      registry.beginTermination("retained"); // completion -> coordinator automatic retry (one-shot callback)
      assertThat(runner.cancelRunDetailed("retained").unconfirmedCount()).isEqualTo(1); // coordinator scan retries too
      registry.unregister("retained");
      int beforeRecovery = calls.get();
      recovered.set(true);
      registry.beginTermination("retained");
      registry.abortSession("retained-session", com.aicodeassistant.engine.AbortReason.USER_INTERRUPT);
      assertThat(calls.get()).isEqualTo(beforeRecovery);
      assertThat(registry.isRegistered("retained")).isTrue();
      assertThat(permits.availablePermits()).isZero();
      assertThatThrownBy(() -> registry.register("next", "retained-session", new AbortContext())).hasMessage("SESSION_EXECUTION_ALREADY_REGISTERED");
      System.out.println("PROBE retained: attemptsBeforeRecovery="+beforeRecovery+" attemptsAfterRepeatedRegistryCancellation="+calls.get()+" registered="+registry.isRegistered("retained")+" permits="+permits.availablePermits()+" nextRun=SESSION_EXECUTION_ALREADY_REGISTERED");
      assertThat(runner.cancelRunDetailed("retained").confirmedCount()).isEqualTo(1);
      assertThat(registry.isRegistered("retained")).isFalse();
      assertThat(permits.availablePermits()).isEqualTo(1);
      System.out.println("PROBE retained manual-runner-retry: registered=false permits=1");
    } finally { recovered.set(true); runner.shutdown(); }
  }

  @Test void descendantFailureCanRecoverAfterRootExit() throws Exception {
    Process delegate = mock(Process.class); ProcessHandle handle = mock(ProcessHandle.class);
    AtomicBoolean alive = new AtomicBoolean(true);
    when(delegate.toHandle()).thenReturn(handle);
    when(delegate.isAlive()).thenAnswer(i -> alive.get());
    when(handle.isAlive()).thenAnswer(i -> alive.get());
    when(handle.descendants()).thenThrow(new UnsupportedOperationException("probe capability unavailable"));
    when(handle.destroy()).thenAnswer(i -> { alive.set(false); return true; });
    var ctor = OwnedProcess.class.getDeclaredConstructor(Process.class, Class.forName(OwnedProcess.class.getName()+"$ProcIdentity"));
    ctor.setAccessible(true);
    OwnedProcess process = ctor.newInstance(delegate, null);
    long started = System.nanoTime();
    boolean confirmed = process.terminate(started + TimeUnit.SECONDS.toNanos(1), 100, false);
    assertThat(confirmed).isTrue();
    System.out.println("PROBE descendants-unavailable-recovery: confirmed="+confirmed+" elapsedMs="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
  }

  @Test void cwdUpdateMethodIsOnlyNotification() throws Exception {
    var manager = new ShellStateManager();
    String session = "ground-truth-" + UUID.randomUUID();
    Path base = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "cwd-probe-"); Path child = Files.createDirectory(base.resolve("child"));
    try {
      ProcessBuilder builder = new ProcessBuilder("bash", "-c", manager.wrapCommand("cd '"+child+"'", session)).directory(base.toFile());
      builder.environment().put("TMPDIR", base.toString());
      Process process = builder.start();
      assertThat(process.waitFor(2, TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).isZero();
      // Deliberately omit updateStateFromSnapshot, exactly like new unconfirmed branch.
      assertThat(manager.resolveWorkingDirectory(session, base.toString())).isEqualTo(child.toString());
      System.out.println("PROBE cwd: skippedUpdate=true nextCommandCwd="+manager.resolveWorkingDirectory(session, base.toString()));
    } finally { Files.deleteIfExists(manager.getCwdTrackingPath(session)); }
  }
}
