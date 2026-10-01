package com.aicodeassistant.tool.agent;

import com.aicodeassistant.authorization.AuthorizationSubject;
import com.aicodeassistant.authorization.AuthorizationSubjectResolver;
import com.aicodeassistant.service.GitService;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/** Real Git, manager and runner; only the persisted authorization lookup is mocked. */
@EnabledOnOs({OS.MAC, OS.LINUX})
@Timeout(60)
class WorktreeHookLifecycleTest {
    @TempDir Path fixture;

    @ParameterizedTest
    @CsvSource({"0,false,false", "0,true,false", "7,false,false", "7,true,false",
            "0,false,true", "0,true,true", "7,false,true", "7,true,true"})
    void naturalGitExitRetainsHookAndBlocksTargetUntilPassiveReclamation(
            int hookExit, boolean inheritPipes, boolean fastExit) throws Exception {
        Path root = repository();
        String originalHead = git(root, "rev-parse", "HEAD");
        var subjects = mock(AuthorizationSubjectResolver.class);
        when(subjects.resolve("root-run")).thenReturn(new AuthorizationSubject(
                "session", "root-run", "root-run", "workspace", root));
        var runner = spy(new ManagedProcessRunner());
        ReflectionTestUtils.setField(runner, "drainJoinMs", 100L);
        var manager = new WorktreeManager(subjects, new GitService(), runner);
        var context = ToolUseContext.of(root.toString(), "session").withCurrentRunId("root-run");
        var tree = manager.createWorktree("hook-worker", context, false);
        Files.writeString(tree.path().resolve("result.txt"), "agent result must survive\n");
        Path survived = fixture.resolve("hook-survived");
        Path hook = fixture.resolve("hooks/pre-commit");
        var owner = new CompletableFuture<String>();
        var requests = new CopyOnWriteArrayList<List<String>>();
        doAnswer(invocation -> {
            ManagedProcessRunner.Request request = invocation.getArgument(0);
            requests.add(request.command());
            if (request.command().contains("commit")) owner.complete(request.runId());
            return invocation.callRealMethod();
        }).when(runner).runRawGit(any());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try (var ready = new Pipe(fixture.resolve("ready.fifo"));
                 var allowGitExit = new Pipe(fixture.resolve("allow-git-exit.fifo"));
                 var releaseHook = new Pipe(fixture.resolve("release-hook.fifo"))) {
                String redirect = inheritPipes ? "" : " > " + quote(fixture.resolve("hook-output")) + " 2>&1";
                Files.writeString(hook, """
                        #!/bin/sh
                        (
                          IFS= read -r release < %s
                          printf 'survived\n'
                          printf 'survived\n' > %s
                        )%s &
                        printf '%%s\n' "$!" > %s
                        %s
                        exit %d
                        """.formatted(releaseHook.quotedPath(), quote(survived), redirect,
                        ready.quotedPath(), fastExit ? "" : "IFS= read -r release < " + allowGitExit.quotedPath(), hookExit));
                assertThat(hook.toFile().setExecutable(true)).isTrue();
                var readySignal = ready.receive(executor);
                var delivery = executor.submit(() -> manager.finishWorktree(tree.path(), true));
                ProcessHandle hookProcess = null;
                try {
                    hookProcess = ProcessHandle.of(Long.parseLong(readySignal.get(10, TimeUnit.SECONDS))).orElseThrow();
                    String exactOwner = owner.get(1, TimeUnit.SECONDS);
                    if (!fastExit) {
                        // Retain the original observed-child case alongside the fast-exit regression.
                        assertThat(runner.currentGitOperation(exactOwner).allTerminated()).isFalse();
                        allowGitExit.release();
                    }
                    // fastExit performs no scope observation until the real Git call has returned.
                    var result = delivery.get(10, TimeUnit.SECONDS);
                    assertThat(result.success()).isFalse();
                    assertThat(result.targetMayHaveChanged()).isFalse();
                    assertThat(result.summary()).contains("retained", "termination is unconfirmed", tree.path().toString());
                    assertThat(Files.readString(tree.path().resolve("result.txt"))).isEqualTo("agent result must survive\n");
                    assertThat(git(root, "rev-parse", "HEAD")).isEqualTo(originalHead);
                    assertThat(root.resolve("result.txt")).doesNotExist();
                    if (hookExit == 0) assertThat(git(tree.path(), "rev-parse", "HEAD")).isNotEqualTo(originalHead);
                    else assertThat(git(tree.path(), "rev-parse", "HEAD")).isEqualTo(originalHead);

                    int commandsBeforeBlockedAttempts = requests.size();
                    assertThatThrownBy(() -> manager.createWorktree("another-worker", context, false))
                            .hasMessageContaining("Earlier Git operation has not stopped");
                    assertThat(manager.inspectPendingDelivery(tree.path())).isEqualTo(WorktreeManager.PendingDelivery.UNKNOWN);
                    assertThat(manager.removeWorktree(tree.path(), context).success()).isFalse();
                    assertThat(requests).hasSize(commandsBeforeBlockedAttempts);
                    assertThat(runner.currentGitOperation("another-owner").allTerminated()).isTrue();

                    runner.retryRetainedCleanup();
                    runner.observeRetainedGit();
                    assertThat(runner.cancelRunDetailed(exactOwner).activeCount()).isZero();
                    assertThat(runner.cancelGitOperation(exactOwner).allTerminated()).isFalse();
                    ReflectionTestUtils.invokeMethod(runner, "shutdown");
                    assertThat(hookProcess.isAlive()).isTrue();
                    assertThat(tree.path()).exists();
                    CountDownLatch invalidatedAfterStop = new CountDownLatch(1);
                    manager.whenTargetSettled(tree.path(), invalidatedAfterStop::countDown);
                    assertThat(invalidatedAfterStop.getCount()).isOne();

                    releaseHook.release();
                    hookProcess.onExit().get(5, TimeUnit.SECONDS);
                    awaitRetainedDrains(runner);
                    assertThat(Files.readString(survived)).isEqualTo("survived\n");
                    runner.observeRetainedGit();
                    assertThat(runner.currentGitOperation(exactOwner).allTerminated()).isTrue();
                    // Passive observation clears occupancy only. Neither it nor a repeated finish delivers late.
                    assertThat(manager.inspectPendingDelivery(tree.path())).isEqualTo(WorktreeManager.PendingDelivery.PENDING);
                    assertThat(invalidatedAfterStop.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(manager.finishWorktree(tree.path(), true).success()).isFalse();
                    assertThat(git(root, "rev-parse", "HEAD")).isEqualTo(originalHead);
                    assertThat(root.resolve("result.txt")).doesNotExist();
                    assertThat(tree.path().resolve("result.txt")).exists();
                    assertThat(requests).noneMatch(command -> command.contains("merge"));
                    assertThat(manager.getActiveCount()).isOne();
                } finally {
                    allowGitExit.release();
                    releaseHook.release();
                    if (!delivery.isDone()) runner.cancelGitOperation(owner.getNow(null));
                    try { delivery.get(10, TimeUnit.SECONDS); }
                    finally { stopFixtureChildIfNecessary(hookProcess); }
                }
            }
        } finally {
            // Only this test's generated worktree is forcibly removed after its fixture has stopped.
            if (Files.exists(tree.path())) git(root, "worktree", "remove", "--force", tree.path().toString());
        }
    }

    private Path repository() throws Exception {
        Path root = Files.createDirectory(fixture.resolve("project"));
        Path hooks = Files.createDirectory(fixture.resolve("hooks"));
        git(root, "-c", "init.templateDir=" + hooks, "init", "-q", "-b", "main");
        git(root, "config", "user.name", "Hook Fixture");
        git(root, "config", "user.email", "hook-fixture@example.invalid");
        git(root, "config", "commit.gpgsign", "false");
        git(root, "config", "core.hooksPath", hooks.toString());
        Path excludes = fixture.resolve("empty-excludes");
        Files.writeString(excludes, "");
        git(root, "config", "core.excludesFile", excludes.toString());
        Files.writeString(root.resolve("base.txt"), "base\n");
        git(root, "add", ".");
        git(root, "commit", "-qm", "base");
        return root.toRealPath();
    }

    private static String git(Path cwd, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_SYSTEM", "/dev/null");
        Process process = builder.start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) throw new AssertionError("Fixture Git did not exit: " + command);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).describedAs(command + "\n" + output).isZero();
            return output;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
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

    private static void awaitRetainedDrains(ManagedProcessRunner runner) throws Exception {
        Map<?, ?> scopes = (Map<?, ?>) ReflectionTestUtils.getField(runner, "gitScopes");
        for (Object scope : List.copyOf(scopes.values())) {
            ((CompletableFuture<?>) ReflectionTestUtils.getField(scope, "stdout")).get(5, TimeUnit.SECONDS);
            ((CompletableFuture<?>) ReflectionTestUtils.getField(scope, "stderr")).get(5, TimeUnit.SECONDS);
        }
    }

    private static String quote(Path path) { return "'" + path.toString().replace("'", "'\"'\"'") + "'"; }

    /** Bidirectional open prevents FIFO open from depending on thread scheduling. */
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
        String quotedPath() { return quote(path); }
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
