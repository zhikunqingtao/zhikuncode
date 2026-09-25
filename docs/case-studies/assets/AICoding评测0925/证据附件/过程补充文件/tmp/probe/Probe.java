import com.aicodeassistant.tool.process.OwnedProcess;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Independent probe: does an unavailable descendants() view make terminate unconfirmable? */
public class Probe {

    static class ThrowingHandle implements ProcessHandle {
        private final long pid;
        private final boolean alive;
        ThrowingHandle(long pid, boolean alive) { this.pid = pid; this.alive = alive; }
        public long pid() { return pid; }
        public Optional<ProcessHandle> parent() { return Optional.empty(); }
        public Stream<ProcessHandle> children() { throw new UnsupportedOperationException("sysctl denied"); }
        public Stream<ProcessHandle> descendants() { throw new UnsupportedOperationException("sysctl denied"); }
        public Info info() { return new Info() {
            public Optional<String> command() { return Optional.empty(); }
            public Optional<String> commandLine() { return Optional.empty(); }
            public Optional<String[]> arguments() { return Optional.empty(); }
            public Optional<Instant> startInstant() { return Optional.empty(); }
            public Optional<Duration> totalCpuDuration() { return Optional.empty(); }
            public Optional<String> user() { return Optional.empty(); }
        }; }
        public CompletableFuture<ProcessHandle> onExit() { return CompletableFuture.completedFuture(this); }
        public boolean destroy() { return true; }
        public boolean destroyForcibly() { return true; }
        public boolean isAlive() { return alive; }
        public boolean supportsNormalTermination() { return true; }
        public int compareTo(ProcessHandle other) { return Long.compare(pid, other.pid()); }
    }

    static class EmptyHandle extends ThrowingHandle {
        EmptyHandle(long pid, boolean alive) { super(pid, alive); }
        @Override public Stream<ProcessHandle> children() { return Stream.empty(); }
        @Override public Stream<ProcessHandle> descendants() { return Stream.empty(); }
    }

    /** A dead child process whose ProcessHandle view is either throwing or empty. */
    static class FakeProcess extends Process {
        private final ProcessHandle handle;
        FakeProcess(ProcessHandle handle) { this.handle = handle; }
        public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        public int waitFor() { return 0; }
        public boolean waitFor(long timeout, TimeUnit unit) { return true; }
        public int exitValue() { return 0; }
        public void destroy() { }
        public Process destroyForcibly() { return this; }
        public boolean isAlive() { return false; }
        public ProcessHandle toHandle() { return handle; }
        public long pid() { return handle.pid(); }
    }

    public static void main(String[] args) {
        // 1) Default ProcessBuilder stdin redirect identity with Redirect.PIPE.
        ProcessBuilder pb = new ProcessBuilder("true");
        System.out.println("redirectInput == Redirect.PIPE : " + (pb.redirectInput() == ProcessBuilder.Redirect.PIPE));

        // 2) Dead root + descendants() unavailable (restricted host).
        FakeProcess restricted = new FakeProcess(new ThrowingHandle(999_998L, false));
        long start = System.nanoTime();
        boolean restrictedResult = OwnedProcess.terminateTree(restricted, Duration.ofMillis(100));
        long restrictedMs = (System.nanoTime() - start) / 1_000_000;

        // 3) Same dead root, but inspection works (healthy host).
        FakeProcess healthy = new FakeProcess(new EmptyHandle(999_999L, false));
        start = System.nanoTime();
        boolean healthyResult = OwnedProcess.terminateTree(healthy, Duration.ofMillis(100));
        long healthyMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("dead root + descendants() DENIED -> terminateTree=" + restrictedResult + " (" + restrictedMs + "ms)");
        System.out.println("dead root + descendants() OK     -> terminateTree=" + healthyResult + " (" + healthyMs + "ms)");

        // 4) Live root + descendants() denied (the kill path on a restricted host).
        FakeProcess liveRestricted = new FakeProcess(new ThrowingHandle(999_997L, true)) {
            @Override public boolean isAlive() { return true; }
        };
        start = System.nanoTime();
        boolean liveResult = OwnedProcess.terminateTree(liveRestricted, Duration.ofMillis(100));
        long liveMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println("live root + descendants() DENIED -> terminateTree=" + liveResult + " (" + liveMs + "ms)");
    }
}
