package com.aicodeassistant.tool.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledOnOs({OS.MAC, OS.LINUX})
@Timeout(45)
class RawGitManagedProcessRunnerTest {
    @TempDir Path directory;
    private final ManagedProcessRunner runner = new ManagedProcessRunner();

    @Test
    void completeMachineOutputExceedsPreviewAndPreservesNulAndWhitespace() throws Exception {
        String script = "printf ' M name with spaces\\000\\n'; head -c 40000 /dev/zero | tr '\\000' x; printf ' warning\\n' >&2";
        var result = runner.runRawGit(request("raw", "output", script, Duration.ofSeconds(5)));
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo(" M name with spaces\0\n" + "x".repeat(40000));
        assertThat(result.stderr()).isEqualTo(" warning\n");
        assertThat(result.stdoutTruncated()).isFalse();
        assertThat(result.stderrTruncated()).isFalse();
        assertThat(result.terminationConfirmed()).isTrue();
        assertThat(runner.currentGitOperation("raw").allTerminated()).isTrue();

        var ordinary = runner.run(new ManagedProcessRunner.Request(
                List.of("bash", "-c", "head -c 40000 /dev/zero | tr '\\000' x"), directory,
                Duration.ofSeconds(5), "ordinary", "output"));
        assertThat(ordinary.stdout()).hasSize(30000);
    }

    @ParameterizedTest
    @CsvSource({"observe", "timeout"})
    @EnabledOnOs(OS.MAC)
    void macScopeLifecycleWorksFromVirtualThreadWithOneCarrier(String scenario) throws Exception {
        Path log = directory.resolve("single-carrier-" + scenario + ".log");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process probe = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:ActiveProcessorCount=1", "-Djdk.virtualThreadScheduler.parallelism=1",
                "-cp", classpath, SingleCarrierProbeMain.class.getName(),
                scenario, directory.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(probe.waitFor(30, TimeUnit.SECONDS))
                    .as("single-carrier probe deadline; output: %s", Files.readString(log)).isTrue();
            assertThat(probe.exitValue()).as(Files.readString(log)).isZero();
        } finally {
            // This JVM and its descendants are exclusively owned by the test.
            if (probe.isAlive()) OwnedProcess.terminateTree(probe, Duration.ZERO);
        }
    }

    /** A fresh JVM is necessary: the virtual scheduler size is fixed when first used. */
    public static final class SingleCarrierProbeMain {
        public static void main(String[] args) throws Exception {
            assertThat(Runtime.getRuntime().availableProcessors()).isOne();
            assertThat(System.getProperty("jdk.virtualThreadScheduler.parallelism")).isEqualTo("1");
            String scenario = args[0];
            Path directory = Path.of(args[1]);
            Path pidFile = directory.resolve("timeout-command.pid");
            ManagedProcessRunner runner = new ManagedProcessRunner();
            List<String> command = scenario.equals("observe") ? List.of("git", "--version")
                    : List.of("/bin/sh", "-c", "printf '%s\\n' \"$$\" > timeout-command.pid; exec sleep 30");
            var request = new ManagedProcessRunner.Request(command, directory,
                    Duration.ofSeconds(scenario.equals("observe") ? 5 : 3), "single-carrier", scenario,
                    null, ManagedProcessRunner.Ownership.SERVICE);
            var outcome = new CompletableFuture<ManagedProcessRunner.Result>();
            Thread worker = Thread.ofVirtual().name("raw-git-single-carrier-probe").start(() -> {
                try { outcome.complete(runner.runRawGit(request)); }
                catch (Throwable failure) { outcome.completeExceptionally(failure); }
            });
            try {
                var result = outcome.get(20, TimeUnit.SECONDS);
                assertThat(result.terminationConfirmed()).as(result.toString()).isTrue();
                assertThat(result.descendantTrackingUnavailable()).isFalse();
                assertThat(result.stdoutTruncated()).isFalse();
                assertThat(result.stderrTruncated()).isFalse();
                assertThat(result.cancelled()).isFalse();
                if (scenario.equals("observe")) {
                    assertThat(result.exitCode()).isZero();
                    assertThat(result.timedOut()).isFalse();
                    assertThat(result.stdout()).startsWith("git version ").endsWith("\n");
                } else {
                    assertThat(result.exitCode()).isEqualTo(137);
                    assertThat(result.timedOut()).isTrue();
                    long pid = Long.parseLong(Files.readString(pidFile).strip());
                    assertThat(ProcessHandle.of(pid).filter(ProcessHandle::isAlive)).isEmpty();
                }
                assertThat(runner.currentGitOperation("single-carrier").allTerminated()).isTrue();
                System.out.println(scenario + " passed: " + result);
            } finally {
                runner.shutdown();
                worker.join(Duration.ofSeconds(3));
                assertThat(worker.isAlive()).isFalse();
            }
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void nonMacFactoryRetainsTheExistingOwnedProcessContract() throws Exception {
        var existing = org.mockito.Mockito.mock(OwnedProcess.class);
        var snapshot = new OwnedProcess.ScopeSnapshot(1, true);
        org.mockito.Mockito.when(existing.observeScope()).thenReturn(snapshot);
        try (var starts = org.mockito.Mockito.mockStatic(OwnedProcess.class)) {
            ProcessBuilder builder = new ProcessBuilder("git", "status");
            starts.when(() -> OwnedProcess.start(builder)).thenReturn(existing);
            RawGitProcess raw = RawGitProcess.start(builder);
            assertThat(raw.observeScope()).isSameAs(snapshot);
            raw.terminate(123L, 4L, false);
            org.mockito.Mockito.verify(existing).terminate(123L, 4L, false);
            starts.verify(() -> OwnedProcess.start(builder));
        }
    }

    @Test
    void invalidUtf8AndCaptureOverflowAreNotCompleteMachineRecords() throws Exception {
        var invalid = runner.runRawGit(request("invalid", "output", "printf '\\377'", Duration.ofSeconds(5)));
        assertThat(invalid.exitCode()).isZero();
        assertThat(invalid.stdoutTruncated()).isTrue();
        assertThat(invalid.terminationConfirmed()).isTrue();
        var overflow = runner.runRawGit(request("overflow", "output",
                "head -c 1048577 /dev/zero; head -c 1048577 /dev/zero >&2", Duration.ofSeconds(5)));
        assertThat(overflow.stdoutTruncated()).isTrue();
        assertThat(overflow.stderrTruncated()).isTrue();
        assertThat(overflow.terminationConfirmed()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"0,false,false", "0,true,false", "7,false,false", "7,true,false",
            "0,false,true", "0,true,true", "7,false,true", "7,true,true"})
    void naturallyExitedGitPreservesHookAndPipesWithoutHoldingForegroundCapacity(
            int hookExit, boolean inheritPipes, boolean fastExit) throws Exception {
        git("init", "-q");
        git("config", "user.email", "fixture@example.invalid");
        git("config", "user.name", "Fixture");
        git("config", "commit.gpgsign", "false");
        git("config", "core.hooksPath", directory.resolve(".git/hooks").toString());
        Files.writeString(directory.resolve("tracked.txt"), "fixture");
        git("add", "tracked.txt");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try (var ready = new Pipe(directory.resolve("ready.fifo"));
                 var allowExit = new Pipe(directory.resolve("allow-exit.fifo"));
                 var releaseHook = new Pipe(directory.resolve("release-hook.fifo"))) {
                Path hook = directory.resolve(".git/hooks/pre-commit");
                String redirect = inheritPipes ? "" : " > hook-output 2>&1";
                Files.writeString(hook, """
                        #!/bin/sh
                        (
                          IFS= read -r release < %s
                          echo survived
                          echo survived > hook-survived
                        )%s &
                        printf '%%s\n' "$!" > %s
                        %s
                        exit %d
                        """.formatted(releaseHook.quotedPath(), redirect, ready.quotedPath(),
                        fastExit ? ":" : "IFS= read -r release < " + allowExit.quotedPath(), hookExit));
                assertThat(hook.toFile().setExecutable(true)).isTrue();
                var capacity = new Semaphore(1);
                ReflectionTestUtils.setField(runner, "capacity", capacity);
                ReflectionTestUtils.setField(runner, "drainJoinMs", 100L);
                Future<String> readySignal = ready.receive(executor);
                var call = executor.submit(() -> runner.runRawGit(new ManagedProcessRunner.Request(
                        List.of("git", "commit", "-m", "fixture"), directory, Duration.ofSeconds(15),
                        "hook-owner", "commit", null, ManagedProcessRunner.Ownership.SERVICE)));
                ProcessHandle child = null;
                try {
                    child = ProcessHandle.of(Long.parseLong(readySignal.get(5, TimeUnit.SECONDS))).orElseThrow();
                    if (!fastExit) {
                        assertThat(runner.currentGitOperation("hook-owner").allTerminated()).isFalse();
                        allowExit.release();
                    }
                    // The fast variant has no pre-exit observation or barrier:
                    // the hook returns immediately after spawning its child.
                    var result = call.get(5, TimeUnit.SECONDS);
                    if (hookExit == 0) assertThat(result.exitCode()).isZero();
                    else assertThat(result.exitCode()).isNotZero();
                    assertThat(result.timedOut()).isFalse();
                    assertThat(result.cancelled()).isFalse();
                    assertThat(result.terminationConfirmed()).isFalse();
                    assertThat(result.stdoutTruncated() || result.stderrTruncated()).isEqualTo(inheritPipes);
                    assertThat(capacity.availablePermits()).isEqualTo(1);
                    assertThat(runner.currentGitOperation("unrelated").allTerminated()).isTrue();

                    runner.retryRetainedCleanup();
                    runner.observeRetainedGit();
                    assertThat(runner.cancelRunDetailed("hook-owner").activeCount()).isZero();
                    assertThat(runner.cancelGitOperation("hook-owner").allTerminated()).isFalse();
                    runner.shutdown();
                    assertThat(child.isAlive()).isTrue();
                    assertThatThrownBy(() -> runner.runRawGit(request("hook-owner", "commit",
                            "touch duplicate-must-not-run", Duration.ofSeconds(3))))
                            .isInstanceOf(IOException.class).hasMessage("PROCESS_OWNERSHIP_CONFLICT");
                    assertThat(directory.resolve("duplicate-must-not-run")).doesNotExist();
                    assertThat(capacity.availablePermits()).isEqualTo(1);
                    for (int index = 0; index < 17; index++) {
                        assertThat(runner.runRawGit(request("other-" + index, "check", "true", Duration.ofSeconds(3)))
                                .exitCode()).isZero();
                        assertThat(capacity.availablePermits()).isEqualTo(1);
                    }
                    releaseHook.release();
                    child.onExit().get(5, TimeUnit.SECONDS);
                    awaitRetainedDrains();
                    assertThat(directory.resolve("hook-survived")).exists();
                    assertThat(runner.currentGitOperation("hook-owner").allTerminated()).isTrue();
                    runner.observeRetainedGit();
                    assertThat(capacity.availablePermits()).isEqualTo(1);
                } finally {
                    allowExit.release();
                    releaseHook.release();
                    if (!call.isDone()) runner.cancelGitOperation("hook-owner");
                    try { call.get(10, TimeUnit.SECONDS); }
                    finally { stopFixtureChildIfNecessary(child); }
                }
            }
        }
    }

    @Test
    void rawTimeoutStopsOnlyItsOwnedScopeAndReportsDeadline() throws Exception {
        try (var wait = new Pipe(directory.resolve("timeout.fifo"))) {
            var result = runner.runRawGit(request("deadline", "wait",
                    "IFS= read -r release < " + wait.quotedPath(), Duration.ofMillis(150)));
            assertThat(result.timedOut()).isTrue();
            assertThat(result.cancelled()).isFalse();
            assertThat(result.exitCode()).isEqualTo(137);
            assertThat(runner.currentGitOperation("deadline").allTerminated()).isTrue();
        }
    }

    @ParameterizedTest
    @CsvSource({"anchor", "protocol"})
    @EnabledOnOs(OS.MAC)
    void macSupervisorFailureCannotTurnAStoredGroupNumberIntoExitProof(String fault) throws Exception {
        Path childPid = directory.resolve("retained-child.pid");
        Path survived = directory.resolve("retained-child-survived");
        var capacity = new Semaphore(1);
        ReflectionTestUtils.setField(runner, "capacity", capacity);
        try (var release = new Pipe(directory.resolve("retained-child.fifo"))) {
            String script = "( IFS= read -r release < " + release.quotedPath()
                    + "; printf survived > '" + survived + "' ) >/dev/null 2>&1 &\n"
                    + "printf '%s\\n' \"$!\" > '" + childPid + "'\n";
            var result = runner.runRawGit(request("damaged-supervisor", "start", script, Duration.ofSeconds(5)));
            assertThat(result.exitCode()).isZero();
            assertThat(result.terminationConfirmed()).isFalse();
            ProcessHandle child = ProcessHandle.of(Long.parseLong(Files.readString(childPid).strip())).orElseThrow();
            Map<?, ?> scopes = (Map<?, ?>) ReflectionTestUtils.getField(runner, "gitScopes");
            Object scope = scopes.values().iterator().next();
            RawGitProcess raw = (RawGitProcess) ReflectionTestUtils.getField(scope, "process");
            ProcessHandle anchor = (ProcessHandle) ReflectionTestUtils.getField(raw, "anchor");
            Process launcher = (Process) ReflectionTestUtils.getField(raw, "launcher");
            Path control = (Path) ReflectionTestUtils.getField(raw, "control");
            try {
                if (fault.equals("anchor")) {
                    anchor.destroyForcibly();
                    anchor.onExit().get(5, TimeUnit.SECONDS);
                } else {
                    // Lose the exit record before it can be used as an exit proof.
                    ReflectionTestUtils.setField(raw, "exit", null);
                    Files.writeString(control.resolve("exit"), "invalid-exit-record\n");
                }
                runner.cancelGitOperation("damaged-supervisor");
                runner.observeRetainedGit();
                assertThat(child.isAlive()).isTrue();
                assertThat(capacity.availablePermits()).isOne();
                release.release();
                child.onExit().get(5, TimeUnit.SECONDS);
                assertThat(Files.readString(survived)).isEqualTo("survived");
                assertThat(runner.currentGitOperation("damaged-supervisor").allTerminated()).isFalse();
                assertThat(raw.observeScope().inspectionComplete()).isFalse();
                assertThat(runner.currentGitOperation("unrelated").allTerminated()).isTrue();
            } finally {
                release.release();
                stopFixtureChildIfNecessary(child);
                // Test-created helpers only. An unknown production scope remains
                // retained; the fixture must not leave its parked shell behind.
                raw.getOutputStream().close();
                if (!launcher.waitFor(5, TimeUnit.SECONDS)) {
                    anchor.destroyForcibly();
                    assertThat(launcher.waitFor(5, TimeUnit.SECONDS)).isTrue();
                }
                for (String name : List.of("anchor", "foreground", "exit")) Files.deleteIfExists(control.resolve(name));
                Files.deleteIfExists(control);
            }
        }
    }

    @Test
    void rawCancellationIsExactAndDoesNotUseDefaultServiceOwner() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try (var firstReady = new Pipe(directory.resolve("first-ready.fifo"));
                 var secondReady = new Pipe(directory.resolve("second-ready.fifo"));
                 var firstRelease = new Pipe(directory.resolve("first-release.fifo"));
                 var secondRelease = new Pipe(directory.resolve("second-release.fifo"))) {
                var firstSignal = firstReady.receive(executor);
                var secondSignal = secondReady.receive(executor);
                var first = executor.submit(() -> runner.runRawGit(request("first", "wait",
                        "echo ready > " + firstReady.quotedPath() + "; IFS= read -r release < " + firstRelease.quotedPath(),
                        Duration.ofSeconds(20))));
                var second = executor.submit(() -> runner.runRawGit(request("second", "wait",
                        "echo ready > " + secondReady.quotedPath() + "; IFS= read -r release < " + secondRelease.quotedPath(),
                        Duration.ofSeconds(20))));
                try {
                    assertThat(firstSignal.get(5, TimeUnit.SECONDS)).isEqualTo("ready");
                    assertThat(secondSignal.get(5, TimeUnit.SECONDS)).isEqualTo("ready");
                    runner.cancelGitOperation("first");
                    var result = first.get(5, TimeUnit.SECONDS);
                    assertThat(result.cancelled()).isTrue();
                    assertThat(result.timedOut()).isFalse();
                    assertThat(result.exitCode()).isEqualTo(130);
                    assertThat(second.isDone()).isFalse();
                    assertThat(runner.currentGitOperation("second").allTerminated()).isFalse();
                    assertThat(runner.currentRunTermination("first").allTerminated()).isTrue();
                } finally {
                    firstRelease.release(); secondRelease.release();
                    runner.cancelGitOperation("first");
                    first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void rawLaunchFailureAndInvalidOwnerDoNotLeakCapacity() {
        var capacity = new Semaphore(1);
        ReflectionTestUtils.setField(runner, "capacity", capacity);
        assertThatThrownBy(() -> runner.runRawGit(ManagedProcessRunner.Request.serviceOwned(
                List.of("true"), directory, Duration.ofSeconds(2), "shared")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.runRawGit(new ManagedProcessRunner.Request(List.of("true"),
                directory.resolve("missing"), Duration.ofSeconds(2), "failed", "start", null,
                ManagedProcessRunner.Ownership.SERVICE))).isInstanceOf(IOException.class);
        assertThat(capacity.availablePermits()).isEqualTo(1);
        assertThat(runner.currentGitOperation("failed").allTerminated()).isTrue();
    }

    @Test
    void captureSetupFailureDoesNotLeaveUnstartedFutureOrCapacityReserved() {
        var process = org.mockito.Mockito.mock(RawGitProcess.class);
        var alive = new java.util.concurrent.atomic.AtomicBoolean(true);
        var capacity = new Semaphore(1);
        ReflectionTestUtils.setField(runner, "capacity", capacity);
        org.mockito.Mockito.when(process.getInputStream()).thenThrow(new IllegalStateException("capture setup fixture"));
        org.mockito.Mockito.when(process.isAlive()).thenAnswer(call -> alive.get());
        org.mockito.Mockito.when(process.observeScope()).thenAnswer(call ->
                new OwnedProcess.ScopeSnapshot(alive.get() ? 1 : 0, true));
        org.mockito.Mockito.when(process.terminate(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(false))).thenAnswer(call -> { alive.set(false); return true; });
        try (var starts = org.mockito.Mockito.mockStatic(RawGitProcess.class)) {
            starts.when(() -> RawGitProcess.start(org.mockito.ArgumentMatchers.any(ProcessBuilder.class))).thenReturn(process);
            assertThatThrownBy(() -> runner.runRawGit(request("capture-failed", "one", "true", Duration.ofSeconds(2))))
                    .isInstanceOf(IllegalStateException.class).hasMessage("capture setup fixture");
            assertThat(runner.currentGitOperation("capture-failed").allTerminated()).isTrue();
            assertThat(capacity.availablePermits()).isEqualTo(1);
        }
    }

    @Test
    void unconfirmedPartialLaunchKeepsItsOwnerUntilReadOnlyExitConfirmation() {
        var process = org.mockito.Mockito.mock(RawGitProcess.class);
        var exited = new java.util.concurrent.atomic.AtomicBoolean(false);
        var capacity = new Semaphore(1);
        ReflectionTestUtils.setField(runner, "capacity", capacity);
        org.mockito.Mockito.when(process.isAlive()).thenAnswer(call -> !exited.get());
        org.mockito.Mockito.when(process.observeScope()).thenAnswer(call ->
                new OwnedProcess.ScopeSnapshot(exited.get() ? 0 : 1, true));
        try (var starts = org.mockito.Mockito.mockStatic(RawGitProcess.class)) {
            starts.when(() -> RawGitProcess.start(org.mockito.ArgumentMatchers.any(ProcessBuilder.class)))
                    .thenThrow(new RawGitProcess.LaunchFailure(process, new IOException("fixture startup failure")));
            assertThatThrownBy(() -> runner.runRawGit(request("partial-launch", "one", "true", Duration.ofSeconds(2))))
                    .isInstanceOf(IOException.class).hasMessage("PROCESS_LAUNCH_CLEANUP_UNCONFIRMED");
            assertThat(runner.currentGitOperation("partial-launch").allTerminated()).isFalse();
            assertThat(runner.currentGitOperation("another-owner").allTerminated()).isTrue();
            assertThat(capacity.availablePermits()).isZero();
            org.mockito.Mockito.clearInvocations(process);
            runner.observeRetainedGit();
            runner.retryRetainedCleanup();
            org.mockito.Mockito.verify(process, org.mockito.Mockito.never()).terminate(
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
            exited.set(true);
            assertThat(runner.currentGitOperation("partial-launch").allTerminated()).isTrue();
            assertThat(capacity.availablePermits()).isEqualTo(1);
            runner.observeRetainedGit();
            assertThat(capacity.availablePermits()).isEqualTo(1);
        }
    }

    @Test
    void currentSessionBackgroundIsReadOnlyAndMatchesOnlyExactSession() throws Exception {
        CountDownLatch leaseClosed = new CountDownLatch(1);
        var lease = org.mockito.Mockito.mock(com.aicodeassistant.session.SessionExecutionGate.BackgroundLease.class);
        org.mockito.Mockito.doAnswer(call -> { leaseClosed.countDown(); return null; }).when(lease).close();
        var sessions = org.mockito.Mockito.mock(com.aicodeassistant.session.SessionManager.class);
        org.mockito.Mockito.when(sessions.acquireBackgroundLease("child")).thenReturn(lease);
        ReflectionTestUtils.setField(runner, "sessions", sessions);
        try (var wait = new Pipe(directory.resolve("background.fifo"))) {
            var background = runner.startBackground(new ManagedProcessRunner.BackgroundRequest(
                    List.of("bash", "-c", "IFS= read -r release < " + wait.quotedPath()), directory, "run", "service", "child"));
            try {
                assertThat(runner.currentSessionBackground("child").unconfirmedCount()).isEqualTo(1);
                assertThat(runner.currentSessionBackground("child-other").allTerminated()).isTrue();
                assertThat(runner.currentSessionBackground(null).allTerminated()).isTrue();
                assertThat(ProcessHandle.of(background.pid()).orElseThrow().isAlive()).isTrue();
            } finally { runner.cancelSessionBackground("child"); }
            assertThat(leaseClosed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(runner.currentSessionBackground("child").allTerminated()).isTrue();
        }
    }

    @Test
    void cancellationDuringRawLaunchIsAppliedAfterProcessIdentityArrives() throws Exception {
        var process = org.mockito.Mockito.mock(RawGitProcess.class);
        org.mockito.Mockito.when(process.getInputStream()).thenReturn(java.io.InputStream.nullInputStream());
        org.mockito.Mockito.when(process.getErrorStream()).thenReturn(java.io.InputStream.nullInputStream());
        org.mockito.Mockito.when(process.getOutputStream()).thenReturn(java.io.OutputStream.nullOutputStream());
        org.mockito.Mockito.when(process.isAlive()).thenReturn(true);
        org.mockito.Mockito.when(process.waitFor(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        org.mockito.Mockito.when(process.terminate(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(false))).thenAnswer(call -> {
                    org.mockito.Mockito.when(process.isAlive()).thenReturn(false);
                    return true;
                });
        org.mockito.Mockito.when(process.observeScope()).thenAnswer(call ->
                new OwnedProcess.ScopeSnapshot(process.isAlive() ? 1 : 0, true));
        try (var starts = org.mockito.Mockito.mockStatic(RawGitProcess.class)) {
            starts.when(() -> RawGitProcess.start(org.mockito.ArgumentMatchers.any(ProcessBuilder.class))).thenAnswer(call -> {
                assertThat(runner.cancelGitOperation("starting").allTerminated()).isFalse();
                return process;
            });
            var result = runner.runRawGit(request("starting", "one", "true", Duration.ofSeconds(2)));
            assertThat(result.cancelled()).isTrue();
            assertThat(result.timedOut()).isFalse();
            assertThat(result.exitCode()).isEqualTo(130);
            assertThat(runner.currentGitOperation("starting").allTerminated()).isTrue();
        }
    }

    @Test
    void naturalExitWinsCancellationRaceAndUnknownScopeIsOnlyObserved() throws Exception {
        var process = org.mockito.Mockito.mock(RawGitProcess.class);
        var inspectionAvailable = new java.util.concurrent.atomic.AtomicBoolean(false);
        var capacity = new Semaphore(1);
        ReflectionTestUtils.setField(runner, "capacity", capacity);
        org.mockito.Mockito.when(process.getInputStream()).thenReturn(java.io.InputStream.nullInputStream());
        org.mockito.Mockito.when(process.getErrorStream()).thenReturn(java.io.InputStream.nullInputStream());
        org.mockito.Mockito.when(process.getOutputStream()).thenReturn(java.io.OutputStream.nullOutputStream());
        org.mockito.Mockito.when(process.isAlive()).thenReturn(false);
        org.mockito.Mockito.when(process.exitValue()).thenReturn(7);
        org.mockito.Mockito.when(process.observeScope()).thenAnswer(call ->
                new OwnedProcess.ScopeSnapshot(0, inspectionAvailable.get()));
        org.mockito.Mockito.when(process.waitFor(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    runner.cancelGitOperation("exited");
                    return true;
                });
        try (var starts = org.mockito.Mockito.mockStatic(RawGitProcess.class)) {
            starts.when(() -> RawGitProcess.start(org.mockito.ArgumentMatchers.any(ProcessBuilder.class))).thenReturn(process);
            var result = runner.runRawGit(request("exited", "one", "true", Duration.ofSeconds(2)));
            assertThat(result.exitCode()).isEqualTo(7);
            assertThat(result.cancelled()).isFalse();
            assertThat(result.terminationConfirmed()).isFalse();
            assertThat(result.descendantTrackingUnavailable()).isTrue();
            assertThat(capacity.availablePermits()).isEqualTo(1);
            runner.observeRetainedGit();
            runner.retryRetainedCleanup();
            runner.shutdown();
            assertThat(runner.currentGitOperation("exited").allTerminated()).isFalse();
            org.mockito.Mockito.verify(process, org.mockito.Mockito.never()).terminate(
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
            inspectionAvailable.set(true);
            assertThat(runner.currentGitOperation("exited").allTerminated()).isTrue();
            assertThat(capacity.availablePermits()).isEqualTo(1);
        }
    }

    @Test
    void cancelledRootExitReturnsCapacityWhileDescendantOwnershipRemains() throws Exception {
        var process = org.mockito.Mockito.mock(RawGitProcess.class);
        var rootAlive = new java.util.concurrent.atomic.AtomicBoolean(true);
        var descendantAlive = new java.util.concurrent.atomic.AtomicBoolean(true);
        var capacity = new Semaphore(1);
        ReflectionTestUtils.setField(runner, "capacity", capacity);
        org.mockito.Mockito.when(process.getInputStream()).thenReturn(java.io.InputStream.nullInputStream());
        org.mockito.Mockito.when(process.getErrorStream()).thenReturn(java.io.InputStream.nullInputStream());
        org.mockito.Mockito.when(process.getOutputStream()).thenReturn(java.io.OutputStream.nullOutputStream());
        org.mockito.Mockito.when(process.isAlive()).thenAnswer(call -> rootAlive.get());
        org.mockito.Mockito.when(process.observeScope()).thenAnswer(call ->
                new OwnedProcess.ScopeSnapshot((rootAlive.get() ? 1 : 0) + (descendantAlive.get() ? 1 : 0), true));
        org.mockito.Mockito.when(process.terminate(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(false))).thenAnswer(call -> {
                    rootAlive.set(false);
                    return !descendantAlive.get();
                });
        org.mockito.Mockito.when(process.waitFor(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    assertThat(runner.cancelGitOperation("cancelled-root").allTerminated()).isFalse();
                    return true;
                });
        try (var starts = org.mockito.Mockito.mockStatic(RawGitProcess.class)) {
            starts.when(() -> RawGitProcess.start(org.mockito.ArgumentMatchers.any(ProcessBuilder.class))).thenReturn(process);
            var result = runner.runRawGit(request("cancelled-root", "one", "true", Duration.ofSeconds(2)));
            assertThat(result.cancelled()).isTrue();
            assertThat(result.exitCode()).isEqualTo(130);
            assertThat(result.terminationConfirmed()).isFalse();
            assertThat(capacity.availablePermits()).isOne();
            assertThat(runner.currentGitOperation("cancelled-root").allTerminated()).isFalse();
            // A cancelled scope remains cancellable after foreground capacity is returned.
            org.mockito.Mockito.clearInvocations(process);
            assertThat(runner.cancelGitOperation("cancelled-root").allTerminated()).isFalse();
            org.mockito.Mockito.verify(process).terminate(org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(false));
            assertThat(capacity.availablePermits()).isOne();
            descendantAlive.set(false);
            assertThat(runner.currentGitOperation("cancelled-root").allTerminated()).isTrue();
            runner.observeRetainedGit();
            assertThat(capacity.availablePermits()).isOne();
        }
    }

    private ManagedProcessRunner.Request request(String owner, String step, String script, Duration timeout) {
        return new ManagedProcessRunner.Request(List.of("bash", "-c", script), directory, timeout,
                owner, step, null, ManagedProcessRunner.Ownership.SERVICE);
    }

    private void git(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).describedAs(new String(process.getInputStream().readAllBytes())).isZero();
    }

    private static void stopFixtureChildIfNecessary(ProcessHandle child) throws Exception {
        if (child == null || !child.isAlive()) return;
        try { child.onExit().get(5, TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException failure) {
            // Destructive fallback is restricted to this test-created process handle.
            child.destroyForcibly();
            child.onExit().get(5, TimeUnit.SECONDS);
            throw failure;
        }
    }

    private void awaitRetainedDrains() throws Exception {
        Map<?, ?> scopes = (Map<?, ?>) ReflectionTestUtils.getField(runner, "gitScopes");
        for (Object scope : List.copyOf(scopes.values())) {
            ((CompletableFuture<?>) ReflectionTestUtils.getField(scope, "stdout")).get(5, TimeUnit.SECONDS);
            ((CompletableFuture<?>) ReflectionTestUtils.getField(scope, "stderr")).get(5, TimeUnit.SECONDS);
        }
    }

    /** A real kernel FIFO is the fixture barrier; no timing-based shell polling. */
    private static final class Pipe implements AutoCloseable {
        private final Path path;
        private final FileChannel channel;
        private boolean released;
        Pipe(Path path) throws Exception {
            this.path = path;
            Process create = new ProcessBuilder("mkfifo", path.toString()).start();
            if (!create.waitFor(5, TimeUnit.SECONDS)) {
                create.destroyForcibly(); throw new IOException("FIFO fixture creation timed out");
            }
            if (create.exitValue() != 0) throw new IOException("FIFO fixture creation failed");
            channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        }
        String quotedPath() { return "'" + path.toString().replace("'", "'\"'\"'") + "'"; }
        Future<String> receive(ExecutorService executor) {
            return executor.submit(() -> {
                StringBuilder value = new StringBuilder();
                ByteBuffer one = ByteBuffer.allocate(1);
                while (channel.read(one) >= 0) {
                    one.flip(); char next = (char) one.get(); one.clear();
                    if (next == '\n') return value.toString();
                    value.append(next);
                }
                throw new IOException("FIFO fixture closed before readiness");
            });
        }
        void release() throws IOException {
            if (!released) {
                released = true;
                channel.write(ByteBuffer.wrap(new byte[]{'g', 'o', '\n'}));
            }
        }
        @Override public void close() throws IOException { channel.close(); }
    }
}
