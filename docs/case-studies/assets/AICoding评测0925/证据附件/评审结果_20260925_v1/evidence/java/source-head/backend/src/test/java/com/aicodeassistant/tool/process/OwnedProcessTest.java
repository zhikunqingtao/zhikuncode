package com.aicodeassistant.tool.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OwnedProcessTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({
            "root, false, false", "root, true, false", "root, true, true",
            "member, false, false", "member, true, false", "member, true, true",
            "wait, false, false", "wait, true, false", "wait, true, true"
    })
    @SuppressWarnings("unchecked")
    void admitsDescendantsOnlyWhileOwnerIdentityRemainsAlive(String path, boolean before, boolean after)
            throws Exception {
        Process delegate = mock(Process.class);
        ProcessHandle root = mock(ProcessHandle.class);
        ProcessHandle parent = path.equals("member") ? mock(ProcessHandle.class) : root;
        ProcessHandle child = mock(ProcessHandle.class);
        when(delegate.toHandle()).thenReturn(root);
        // Model the JDK's PID-based enumeration: even a stale owner can return
        // a reused PID's children. All handles/signals remain mocks.
        when(root.descendants()).thenAnswer(call -> java.util.stream.Stream.empty());
        when(parent.descendants()).thenAnswer(call -> java.util.stream.Stream.of(child));
        when(delegate.descendants()).thenAnswer(call -> root.descendants());
        when(parent.isAlive()).thenReturn(before, after);
        when(parent.pid()).thenReturn(Long.MAX_VALUE - 1);
        when(child.pid()).thenReturn(Long.MAX_VALUE);
        when(delegate.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);

        var constructor = OwnedProcess.class.getDeclaredConstructor(Process.class,
                Class.forName(OwnedProcess.class.getName() + "$ProcIdentity"));
        constructor.setAccessible(true);
        OwnedProcess owned = constructor.newInstance(delegate, null);
        var field = OwnedProcess.class.getDeclaredField("known");
        field.setAccessible(true);
        var known = (java.util.Map<Long, ProcessHandle>) field.get(owned);
        ProcessHandle trusted = mock(ProcessHandle.class);
        known.put(Long.MAX_VALUE - 2, trusted);
        if (path.equals("member")) known.put(parent.pid(), parent);

        if (path.equals("wait")) {
            assertThat(owned.waitFor(0, TimeUnit.NANOSECONDS)).isTrue();
        } else {
            var inspect = OwnedProcess.class.getDeclaredMethod("liveMembers");
            inspect.setAccessible(true);
            inspect.invoke(owned);
        }
        assertThat(known.containsKey(child.pid())).isEqualTo(before && after);
        assertThat(known).containsEntry(Long.MAX_VALUE - 2, trusted);
        verify(parent, before ? times(1) : never()).descendants();
        when(parent.isAlive()).thenReturn(false);
        assertThat(OwnedProcess.terminateTree(owned, Duration.ZERO)).isTrue();
        verify(child, never()).destroy();
        verify(child, never()).destroyForcibly();
    }

    @EnabledOnOs(OS.LINUX)
    @Test void cleansStubbornChildAfterParentExitedWithoutKillingAnotherTask() throws Exception {
        Process unrelated = new ProcessBuilder("sleep", "30").start();
        OwnedProcess process = OwnedProcess.start(new ProcessBuilder("bash", "-c",
                "bash -c 'trap \"\" TERM; echo $$ > child.pid; while :; do sleep 1; done' >/dev/null 2>&1 & "
                        + "while [ ! -f release ]; do sleep .01; done; exit 0").directory(directory.toFile()));
        ProcessHandle child = null;
        try {
            long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            Path pidFile = directory.resolve("child.pid");
            while ((!Files.exists(pidFile) || Files.size(pidFile) == 0) && System.nanoTime() < readyDeadline) Thread.sleep(10);
            child = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).strip())).orElseThrow();
            assertThat(child.isAlive()).isTrue();
            Files.createFile(directory.resolve("release"));
            assertThat(process.waitFor(3, TimeUnit.SECONDS)).isTrue();
            assertThat(live(child.pid())).isTrue();
            assertThat(OwnedProcess.terminateTree(process, Duration.ofMillis(100))).isTrue();
            assertThat(live(child.pid())).isFalse();
            assertThat(unrelated.isAlive()).isTrue();
            assertThat(OwnedProcess.terminateTree(process, Duration.ZERO)).isTrue();
        } finally {
            OwnedProcess.terminateTree(process, Duration.ZERO);
            if (child != null) child.destroyForcibly();
            unrelated.destroyForcibly();
            unrelated.waitFor(2, TimeUnit.SECONDS);
        }
    }

    @EnabledOnOs(OS.LINUX)
    @Test void preservesOutputEnvironmentAndExitStatus() throws Exception {
        ProcessBuilder builder = new ProcessBuilder("bash", "-c", "printf '%s' \"$OWNED_TEST\"; printf err >&2; exit 7");
        builder.environment().put("OWNED_TEST", "value with spaces");
        var original = java.util.List.copyOf(builder.command());
        OwnedProcess process = OwnedProcess.start(builder);
        try {
            assertThat(process.waitFor(2, TimeUnit.SECONDS)).isTrue();
            assertThat(new String(process.getInputStream().readAllBytes())).isEqualTo("value with spaces");
            assertThat(new String(process.getErrorStream().readAllBytes())).isEqualTo("err");
            assertThat(process.exitValue()).isEqualTo(7);
            assertThat(builder.command()).isEqualTo(original);
        } finally { assertThat(OwnedProcess.terminateTree(process, Duration.ZERO)).isTrue(); }
    }

    @EnabledOnOs(OS.LINUX)
    @Test void retainsDetachedBrowserLikeChildBeforeStoppingItsDriver() throws Exception {
        OwnedProcess process = OwnedProcess.start(new ProcessBuilder("bash", "-c",
                "setsid bash -c 'trap \"\" TERM; echo $$ > child.pid; while :; do sleep 1; done' "
                        + ">/dev/null 2>&1 & wait").directory(directory.toFile()));
        ProcessHandle child = null;
        try {
            Path pidFile = directory.resolve("child.pid");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((!Files.exists(pidFile) || Files.size(pidFile) == 0) && System.nanoTime() < deadline) Thread.sleep(10);
            child = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).strip())).orElseThrow();
            assertThat(child.isAlive()).isTrue();
            assertThat(process.terminate(System.nanoTime() + TimeUnit.SECONDS.toNanos(2), 100, true)).isTrue();
            assertThat(live(child.pid())).isFalse();
        } finally {
            OwnedProcess.terminateTree(process, Duration.ZERO);
            if (child != null) child.destroyForcibly();
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    @EnabledIfEnvironmentVariable(named = "ZHIKUN_REAL_BROWSER_PROCESS_TEST", matches = "1")
    void stoppedRealChromiumIsReapedByForcedServiceCleanup() throws Exception {
        // Opt-in: isolated --init container with this Python/Playwright runtime.
        String python = System.getenv("ZHIKUN_TEST_PYTHON");
        OwnedProcess process = OwnedProcess.start(new ProcessBuilder(python, "-c", """
                from pathlib import Path
                import time
                from playwright.sync_api import sync_playwright
                p = sync_playwright().start()
                b = p.chromium.launch(headless=True, args=['--no-sandbox', '--disable-dev-shm-usage'])
                page = b.new_page()
                page.goto('data:text/html,resource-test')
                Path('ready').write_text('ready')
                time.sleep(60)
                """).directory(directory.toFile()));
        java.util.List<ProcessHandle> browsers = java.util.List.of();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(directory.resolve("ready")) && process.isAlive()
                    && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(directory.resolve("ready")).exists();
            browsers = process.descendants().filter(p -> p.info().command().orElse("").contains("chrome")).toList();
            assertThat(browsers).isNotEmpty();
            for (ProcessHandle browser : browsers) {
                assertThat(browser.isAlive()).isTrue();
                Process pause = new ProcessBuilder("kill", "-STOP", Long.toString(browser.pid())).start();
                assertThat(pause.waitFor(2, TimeUnit.SECONDS)).isTrue();
                assertThat(pause.exitValue()).isZero();
            }
            assertThat(process.terminate(System.nanoTime() + TimeUnit.SECONDS.toNanos(4), 200, true)).isTrue();
            for (ProcessHandle browser : browsers) assertThat(live(browser.pid())).isFalse();
        } finally {
            OwnedProcess.terminateTree(process, Duration.ZERO);
            for (ProcessHandle browser : browsers) browser.destroyForcibly();
        }
    }

    @EnabledOnOs(OS.LINUX)
    @Test void rootGetsGraceToRunItsOwnShutdownHook() throws Exception {
        OwnedProcess process = OwnedProcess.start(new ProcessBuilder("bash", "-c",
                "trap 'echo closed > closed; exit 0' TERM; echo ready > ready; while :; do sleep .05; done")
                .directory(directory.toFile()));
        try {
            long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!Files.exists(directory.resolve("ready")) && System.nanoTime() < readyDeadline) Thread.sleep(10);
            assertThat(Files.exists(directory.resolve("ready"))).isTrue();
            assertThat(process.terminate(System.nanoTime() + TimeUnit.SECONDS.toNanos(2), 1000, true)).isTrue();
            assertThat(directory.resolve("closed")).exists();
        } finally { OwnedProcess.terminateTree(process, Duration.ZERO); }
    }

    @EnabledOnOs(OS.LINUX)
    @Test void interruptedCallerStillCleansAndRetainsInterruptStatus() throws Exception {
        OwnedProcess process = OwnedProcess.start(new ProcessBuilder("sleep", "30"));
        try {
            Thread.currentThread().interrupt();
            assertThat(OwnedProcess.terminateTree(process, Duration.ofMillis(50))).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            OwnedProcess.terminateTree(process, Duration.ZERO);
        }
    }

    private static boolean live(long pid) throws Exception {
        Path stat = Path.of("/proc", Long.toString(pid), "stat");
        if (!Files.exists(stat)) return false;
        try {
            String text = Files.readString(stat);
            return !text.substring(text.lastIndexOf(')') + 2).startsWith("Z");
        } catch (java.nio.file.NoSuchFileException exited) { return false; }
    }
}
