package com.aicodeassistant.tool.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** POSIX background jobs retain their group identity after the command's shell exits. */
final class BackgroundProcessGroup {
    private static final Logger log = LoggerFactory.getLogger(BackgroundProcessGroup.class);
    // Job control establishes the group before exec. The child cannot execute user code until admitted.
    private static final String LAUNCH = """
            set -m
            ( IFS= read -r admission; [ "$admission" = start ] || exit 125; exec "$@" ) <&0 >/dev/null 2>&1 &
            child=$!
            printf '%s\\n' "$child"
            wait "$child"
            """;
    private static final String LIVE = """
            set -o pipefail
            ps -eo pgid=,stat= | awk -v group="$1" '$1 == group && $2 !~ /^Z/ { live=1 } END { print live+0 }'
            """;
    final Process launcher;
    final long pid;
    final CompletableFuture<Void> exited = new CompletableFuture<>();

    static BackgroundProcessGroup launch(List<String> command, Path directory) throws IOException {
        var argv = new ArrayList<>(List.of("bash", "-c", LAUNCH, "zhikun-background"));
        argv.addAll(command);
        Process process = new ProcessBuilder(argv).directory(directory.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var identity = new CompletableFuture<String>();
        Thread.ofVirtual().start(() -> {
            try { identity.complete(process.inputReader(StandardCharsets.UTF_8).readLine()); }
            catch (IOException e) { identity.completeExceptionally(e); }
        });
        try {
            long pid = Long.parseLong(identity.get(5, TimeUnit.SECONDS));
            if (pid <= 1) throw new IOException("BACKGROUND_PROCESS_GROUP_UNAVAILABLE");
            return new BackgroundProcessGroup(process, pid);
        } catch (Exception failure) {
            // No admission was written, so the waiting child exits on EOF without executing the command.
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
            process.destroyForcibly();
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException("BACKGROUND_PROCESS_GROUP_UNAVAILABLE", failure);
        }
    }
    private BackgroundProcessGroup(Process launcher, long pid) {
        this.launcher = launcher; this.pid = pid;
        Thread.ofVirtual().name("background-group-" + pid).start(() -> {
            launcher.onExit().join();
            boolean warned = false;
            for (;;) {
                try {
                    if (!isAlive(System.nanoTime() + TimeUnit.SECONDS.toNanos(2))) {
                        exited.complete(null); return;
                    }
                } catch (IOException failure) {
                    // Failure to inspect a group is not proof of exit. Keep its lease and cancellation entry.
                    if (!warned) { log.warn("Cannot confirm background group exit: {}", pid, failure); warned = true; }
                }
                try { Thread.sleep(200); }
                catch (InterruptedException ignored) { /* Ownership ends only after confirmed group exit. */ }
            }
        });
    }
    void admit() throws IOException {
        launcher.getOutputStream().write("start\n".getBytes(StandardCharsets.UTF_8));
        launcher.getOutputStream().close();
    }
    boolean terminate(long deadline, long graceMillis) {
        if (exited.isDone()) return true;
        try {
            signal("TERM", deadline);
            long grace = Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMillis));
            boolean empty;
            try { empty = awaitEmpty(grace); }
            catch (IOException unconfirmed) { empty = false; }
            // A timed-out inspection during the grace period must still allow forced termination.
            if (!empty) {
                signal("KILL", deadline);
                if (!awaitEmpty(deadline)) return false;
            }
            launcher.destroy();
            return true;
        } catch (IOException failure) { return false; }
    }
    private boolean awaitEmpty(long deadline) throws IOException {
        while (System.nanoTime() < deadline) {
            if (exited.isDone() || !isAlive(deadline)) return true;
            try { Thread.sleep(20); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return exited.isDone();
    }
    private void signal(String signal, long deadline) throws IOException {
        command("kill -s \"$1\" -- \"-$2\"", deadline, signal, Long.toString(pid));
    }
    private boolean isAlive(long deadline) throws IOException {
        // Zombies cannot write files; don't retain a lease just because an external init has not reaped them.
        String result = command(LIVE, deadline, Long.toString(pid));
        if (!result.equals("0") && !result.equals("1")) throw new IOException("PROCESS_GROUP_INSPECTION_FAILED");
        return result.equals("1");
    }
    private static String command(String script, long deadline, String... arguments) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("PROCESS_GROUP_DEADLINE");
        var argv = new ArrayList<>(List.of("bash", "-c", script, "zhikun-process-group"));
        argv.addAll(List.of(arguments));
        Process process = new ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(remaining, TimeUnit.NANOSECONDS)) throw new IOException("PROCESS_GROUP_DEADLINE");
            // kill may return nonzero for an already empty group; the subsequent inspection decides completion.
            if (script.equals(LIVE) && process.exitValue() != 0) throw new IOException("PROCESS_GROUP_INSPECTION_FAILED");
            return new String(process.getInputStream().readNBytes(16), StandardCharsets.UTF_8).strip();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("PROCESS_GROUP_INTERRUPTED", e);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.getInputStream().close();
        }
    }
}
