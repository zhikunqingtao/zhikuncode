package com.aicodeassistant.verify;

import com.aicodeassistant.tool.bash.ProcessTreeManager;
import com.aicodeassistant.tool.process.OwnedProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DevServerLauncherTest {
    @TempDir Path root;

    @Test void occupiedPortFailsBeforeProjectCodeRuns() throws Exception {
        var launcher = new DevServerLauncher(new ProcessTreeManager());
        try (var occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            assertThatThrownBy(() -> launcher.start(root, "touch should-not-exist", occupied.getLocalPort(), Duration.ofSeconds(3)))
                    .hasMessageContaining("port is already in use");
        }
        assertThat(root.resolve("should-not-exist")).doesNotExist();
    }

    @Test void exitedProcessIsNotReportedReady() throws Exception {
        var launcher = new DevServerLauncher(new ProcessTreeManager());
        int port;
        try (var free = new ServerSocket(0)) { port = free.getLocalPort(); }
        assertThatThrownBy(() -> launcher.start(root, "exit 7", port, Duration.ofSeconds(3)))
                .isInstanceOf(RuntimeException.class);
        assertThat(root.resolve(".ai-code-assistant/devserver.pid")).doesNotExist();
    }

    @Test void failedCleanupRetainsHandleAndPidFileForRetry() throws Exception {
        var trees = org.mockito.Mockito.mock(ProcessTreeManager.class);
        var launcher = new DevServerLauncher(trees);
        var process = org.mockito.Mockito.mock(Process.class);
        Path pidFile = root.resolve("server.pid");
        Files.writeString(pidFile, "123");
        var handle = new DevServerHandle(process, 123, 9999, root.resolve("server.log"), pidFile);
        org.mockito.Mockito.when(trees.destroyProcessTree(org.mockito.ArgumentMatchers.eq(process),
                org.mockito.ArgumentMatchers.any())).thenReturn(false, true);
        launcher.stop(handle);
        assertThat(pidFile).exists();
        launcher.shutdownAll();
        assertThat(pidFile).doesNotExist();
        org.mockito.Mockito.verify(trees, org.mockito.Mockito.times(2)).destroyProcessTree(
                org.mockito.ArgumentMatchers.eq(process), org.mockito.ArgumentMatchers.any());
    }

    @Test void interruptedInstallPreservesInterruptAndPrimaryFailureWhenCleanupFails() throws Exception {
        var trees = mock(ProcessTreeManager.class);
        var launcher = new DevServerLauncher(trees);
        var process = mock(OwnedProcess.class);
        var interrupted = new InterruptedException("install cancelled");
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenThrow(interrupted);
        when(trees.destroyProcessTree(eq(process), any())).thenReturn(false);
        try (var starts = mockStatic(OwnedProcess.class)) {
            starts.when(() -> OwnedProcess.start(any(ProcessBuilder.class))).thenReturn(process);
            Throwable failure = runSyncFailure(launcher);
            assertThat(failure.getCause()).isSameAs(interrupted);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertCleanupSuppressed(interrupted);
        } finally {
            Thread.interrupted();
        }
    }

    @Test void failedInstallPreservesExitErrorWhenCleanupFails() throws Exception {
        var trees = mock(ProcessTreeManager.class);
        var launcher = new DevServerLauncher(trees);
        var process = mock(OwnedProcess.class);
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(process.exitValue()).thenReturn(7);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(trees.destroyProcessTree(eq(process), any())).thenReturn(false);
        try (var starts = mockStatic(OwnedProcess.class)) {
            starts.when(() -> OwnedProcess.start(any(ProcessBuilder.class))).thenReturn(process);
            Throwable failure = runSyncFailure(launcher);
            assertThat(failure).hasMessageContaining("npm install failed (exit 7)");
            assertCleanupSuppressed(failure);
        }
    }

    @Test void successfulInstallStillFailsAndRetainsProcessWhenCleanupIsUnconfirmed() throws Exception {
        var trees = mock(ProcessTreeManager.class);
        var launcher = new DevServerLauncher(trees);
        var process = mock(OwnedProcess.class);
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(process.exitValue()).thenReturn(0);
        when(trees.destroyProcessTree(eq(process), any())).thenReturn(false, true);
        try (var starts = mockStatic(OwnedProcess.class)) {
            starts.when(() -> OwnedProcess.start(any(ProcessBuilder.class))).thenReturn(process);
            assertThat(runSyncFailure(launcher)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("npm install process cleanup unconfirmed");
            launcher.shutdownAll();
            verify(trees, times(2)).destroyProcessTree(eq(process), any());
        }
    }

    private Throwable runSyncFailure(DevServerLauncher launcher) throws Exception {
        var method = DevServerLauncher.class.getDeclaredMethod("runSync", Path.class, String.class, Duration.class);
        method.setAccessible(true);
        try {
            method.invoke(launcher, root, "npm install", Duration.ofSeconds(3));
            throw new AssertionError("Expected runSync to fail");
        } catch (InvocationTargetException failure) {
            return failure.getCause();
        }
    }

    private static void assertCleanupSuppressed(Throwable failure) {
        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(failure.getSuppressed()[0]).isInstanceOf(IllegalStateException.class)
                .hasMessage("npm install process cleanup unconfirmed");
    }
}
