package com.aicodeassistant.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class GitServiceTest {
    @TempDir Path directory;
    private final GitService service = new GitService();

    @Test
    void returnsCompleteMultiMegabyteUnstagedAndStagedDiff() throws Exception {
        initializeRepository();
        Path file = directory.resolve("large.txt");
        Files.writeString(file, "baseline\n");
        git("add", "large.txt");
        git("commit", "-m", "baseline");
        var content = new StringBuilder("START 中文\n");
        for (int i = 0; i < 40_000; i++) content.append(i).append(" 中文 payload ").append("x".repeat(70)).append('\n');
        content.append("END 中文\n");
        Files.writeString(file, content);

        String expected = git("diff");
        assertThat(expected.length()).isGreaterThan(3_000_000);
        assertThat(service.execGitPublic(directory, "diff"))
                .isNotNull().isEqualTo(expected).contains("+START 中文", "+20000 中文", "+END 中文");
        assertThat(service.execGitRaw(directory, "diff")).isEqualTo(gitRaw("diff"));

        git("add", "large.txt");
        assertThat(service.execGitPublic(directory, "diff", "--cached")).isEqualTo(git("diff", "--cached"));
        assertThat(service.execGitPublic(directory, "diff")).isEmpty();
    }

    @Test
    void returnsNullForNonRepositoryInvalidCommandAndMissingDirectory() {
        assertThat(service.execGitPublic(directory, "status")).isNull();
        assertThat(service.execGitPublic(directory, "not-a-valid-git-command")).isNull();
        assertThat(service.execGitPublic(directory.resolve("missing"), "status")).isNull();
        assertThat(service.execGitRaw(directory, "not-a-valid-git-command")).isNull();
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void preservesEmptyUnicodeAndLineNormalization() throws Exception {
        initializeRepository();
        assertThat(alias("printf '\\r\\n 中文\\r\\nsecond\\n\\n'"))
                .isEqualTo("中文\nsecond");
        assertThat(alias("printf ''")).isEmpty();
        assertThat(alias("printf partial; exit 7")).isNull();
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void rawOutputPreservesWhitespaceCrLfNulAndUnicodeWithoutChangingTextApi() throws Exception {
        initializeRepository();
        String script = "printf ' \\t中文\\r\\nsecond\\n\\000tail \\t\\000'; printf 'warning on stderr' >&2";
        assertThat(service.execGitRaw(directory, "-c", "alias.zk-service-test=!" + script, "zk-service-test"))
                .isEqualTo(" \t中文\r\nsecond\n\0tail \t\0");
        assertThat(service.execGitRaw(directory, "-c", "alias.zk-service-test=!printf ''", "zk-service-test"))
                .isEmpty();
        assertThat(service.execGitRaw(directory, "-c", "alias.zk-service-test=!printf partial; exit 7", "zk-service-test"))
                .isNull();
        assertThat(alias("printf '\\r\\n 中文\\r\\nsecond\\n\\n'"))
                .isEqualTo("中文\nsecond");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void discardsPartialOutputOnReadFailure(boolean raw) throws Exception {
        Process process = mock(Process.class);
        ProcessHandle root = mock(ProcessHandle.class);
        when(process.toHandle()).thenReturn(root);
        when(process.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(process.exitValue()).thenReturn(0);
        when(process.getInputStream()).thenReturn(new InputStream() {
            private boolean emitted;
            @Override public int read() throws IOException { throw new IOException("read failed"); }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (emitted) throw new IOException("read failed");
                emitted = true;
                byte[] partial = "partial output".getBytes(StandardCharsets.UTF_8);
                System.arraycopy(partial, 0, bytes, offset, partial.length);
                return partial.length;
            }
        });
        try (MockedConstruction<ProcessBuilder> ignored = mockConstruction(ProcessBuilder.class,
                (builder, context) -> when(builder.start()).thenReturn(process))) {
            assertThat(raw ? service.execGitRaw(directory, "status") : service.execGitPublic(directory, "status"))
                    .isNull();
        }
    }

    @Test
    void cleanupInspectionFailureStillPreservesNullFailureContract() throws Exception {
        Process process = mock(Process.class);
        ProcessHandle root = mock(ProcessHandle.class);
        when(root.isAlive()).thenThrow(new UnsupportedOperationException("inspection unavailable"));
        when(process.toHandle()).thenReturn(root);
        when(process.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
        when(process.getInputStream()).thenReturn(InputStream.nullInputStream());
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(process.exitValue()).thenReturn(1);
        try (MockedConstruction<ProcessBuilder> ignored = mockConstruction(ProcessBuilder.class,
                (builder, context) -> when(builder.start()).thenReturn(process))) {
            assertThat(service.execGitPublic(directory, "status")).isNull();
        }
    }

    @Test
    void repeatedSuccessAndReadFailureCloseEveryStreamAndFinishReaders() throws Exception {
        var closed = new AtomicInteger();
        var readers = java.util.Collections.synchronizedSet(new java.util.HashSet<Thread>());
        var invocation = new AtomicInteger();
        Process process = mock(Process.class);
        ProcessHandle root = mock(ProcessHandle.class);
        when(process.toHandle()).thenReturn(root);
        when(process.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(process.getInputStream()).thenAnswer(call -> {
            boolean fail = invocation.getAndIncrement() % 2 != 0;
            return new InputStream() {
                private final InputStream bytes = new ByteArrayInputStream("complete".getBytes(StandardCharsets.UTF_8));
                @Override public int read() throws IOException {
                    readers.add(Thread.currentThread());
                    if (fail) throw new IOException("read failed");
                    return bytes.read();
                }
                @Override public void close() { closed.incrementAndGet(); }
            };
        });
        try (MockedConstruction<ProcessBuilder> ignored = mockConstruction(ProcessBuilder.class,
                (builder, context) -> when(builder.start()).thenReturn(process))) {
            for (int i = 0; i < 10; i++) {
                String result = service.execGitPublic(directory, "status");
                if (i % 2 == 0) assertThat(result).isEqualTo("complete");
                else assertThat(result).isNull();
            }
        }
        assertThat(closed).hasValue(10);
        assertThat(readers).hasSize(10);
        for (Thread reader : readers) {
            reader.join(1_000);
            assertThat(reader.isAlive()).isFalse();
        }
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void timesOutAndCleansKnownHookDescendants() throws Exception {
        initializeRepository();
        long start = System.nanoTime();
        try {
            assertThat(alias("echo $$ > parent.pid; sleep 30 & echo $! > child.pid; wait")).isNull();
            assertThat(elapsedMillis(start)).isBetween(4_500L, 9_000L);
            assertPidStopped("parent.pid");
            assertPidStopped("child.pid");
        } finally {
            stopPid("child.pid");
            stopPid("parent.pid");
        }
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void eofSharesDeadlineWhenExitedParentLeavesPipeOpen() throws Exception {
        initializeRepository();
        long start = System.nanoTime();
        try {
            assertThat(alias("sleep 30 & echo $! > child.pid; sleep 0.2; printf partial; exit 0")).isNull();
            assertThat(elapsedMillis(start)).isBetween(4_500L, 9_000L);
            assertPidStopped("child.pid");
        } finally {
            stopPid("child.pid");
        }
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void interruptionCleansCommandAndRestoresCallingThreadsInterruptFlag() throws Exception {
        initializeRepository();
        var result = new AtomicReference<String>("not completed");
        var interrupted = new AtomicBoolean();
        Thread caller = Thread.ofPlatform().daemon().start(() -> {
            result.set(alias("echo $$ > parent.pid; sleep 30 & echo $! > child.pid; wait"));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        try {
            awaitPid("child.pid");
            Thread.sleep(100); // allow one descendant snapshot before requesting interruption
            caller.interrupt();
            caller.join(4_000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(result.get()).isNull();
            assertThat(interrupted).isTrue();
            assertPidStopped("parent.pid");
            assertPidStopped("child.pid");
        } finally {
            caller.interrupt();
            stopPid("child.pid");
            stopPid("parent.pid");
            caller.join(2_000);
        }
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void successfulCommandDoesNotKillDetachedHookWorkWithRedirectedOutput() throws Exception {
        initializeRepository();
        try {
            assertThat(alias("sleep 30 >/dev/null 2>&1 & echo $! > child.pid; sleep 0.2; printf done"))
                    .isEqualTo("done");
            assertThat(ProcessHandle.of(awaitPid("child.pid"))).get().extracting(ProcessHandle::isAlive).isEqualTo(true);
        } finally {
            stopPid("child.pid");
        }
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void normalAndLargeOutputHookCommitsEachCreateExactlyOneCommit() throws Exception {
        initializeRepository();
        Files.writeString(directory.resolve("change.txt"), "first\n");
        git("add", "change.txt");
        assertThat(service.execGitPublic(directory, "commit", "-m", "normal commit")).contains("normal commit");
        assertThat(git("rev-list", "--count", "HEAD")).isEqualTo("1");
        Path hook = directory.resolve("hooks/pre-commit");
        Files.writeString(hook, """
                #!/bin/sh
                printf 'HOOK-START 中文\\n'
                awk 'BEGIN { for (i = 0; i < 30000; i++) print "hook output abcdefghijklmnopqrstuvwxyz0123456789" }'
                printf 'HOOK-END 中文\\n' >&2
                """);
        assertThat(hook.toFile().setExecutable(true)).isTrue();
        Files.writeString(directory.resolve("change.txt"), "second\n");
        git("add", "change.txt");

        assertThat(service.execGitPublic(directory, "commit", "-m", "hook commit"))
                .isNotNull().contains("HOOK-START 中文", "HOOK-END 中文", "hook commit").hasSizeGreaterThan(1_000_000);
        assertThat(git("rev-list", "--count", "HEAD")).isEqualTo("2");
    }

    private String alias(String script) {
        return service.execGitPublic(directory, "-c", "alias.zk-service-test=!" + script, "zk-service-test");
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private long awaitPid(String file) throws Exception {
        Path path = directory.resolve(file);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Files.exists(path) && !Files.readString(path).isBlank()) return Long.parseLong(Files.readString(path).trim());
            Thread.sleep(10);
        }
        throw new AssertionError("Missing process PID: " + file);
    }

    private void assertPidStopped(String file) throws Exception {
        long pid = awaitPid(file);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).as(file).isFalse();
    }

    private void stopPid(String file) throws Exception {
        Path path = directory.resolve(file);
        if (Files.exists(path) && !Files.readString(path).isBlank()) {
            ProcessHandle.of(Long.parseLong(Files.readString(path).trim())).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private void initializeRepository() throws Exception {
        git("init", "-q");
        git("config", "user.name", "GitService Test");
        git("config", "user.email", "git-service-test@example.invalid");
        git("config", "commit.gpgsign", "false");
        git("config", "core.autocrlf", "false");
        git("config", "core.hooksPath", directory.resolve("hooks").toString());
        Files.createDirectories(directory.resolve("hooks"));
    }

    /** Independent Git baseline redirects output to a file, never to an unread pipe. */
    private String git(String... args) throws Exception {
        return gitRaw(args).trim();
    }

    private String gitRaw(String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        Path output = Files.createTempFile("git-service-reference-", ".txt");
        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            String text = Files.readString(output, StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(text).isZero();
            return text;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }
}
