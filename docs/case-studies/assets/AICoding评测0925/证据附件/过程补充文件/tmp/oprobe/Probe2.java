import com.aicodeassistant.tool.process.OwnedProcess;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

public class Probe2 {
    static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    static long readPid(Path f) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while ((!Files.exists(f) || Files.size(f) == 0) && System.nanoTime() < end) Thread.sleep(10);
        return Long.parseLong(Files.readString(f).strip());
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("probe2");
        System.out.println("os=" + System.getProperty("os.name"));

        // A: shell exits leaving a backgrounded daemon. waitFor() polling path (production-like).
        Path pidA = dir.resolve("a.pid");
        ProcessBuilder pbA = new ProcessBuilder("bash", "-c",
                "bash -c 'echo $$ > " + pidA + "; exec sleep 300' & echo spawned");
        pbA.directory(dir.toFile());
        OwnedProcess a = OwnedProcess.start(pbA);
        boolean completed = a.waitFor(5, TimeUnit.SECONDS);
        long childA = readPid(pidA);
        System.out.println("A child pid=" + childA + " aliveBeforeCleanup=" + alive(childA) + " completed=" + completed);
        long t = System.nanoTime();
        boolean confirmedA = a.terminate(System.nanoTime() + TimeUnit.SECONDS.toNanos(2), 1000, false);
        System.out.println("A confirmed=" + confirmedA + " ms=" + (System.nanoTime() - t) / 1_000_000
                + " childAliveAfter=" + alive(childA));

        // B: same but WITHOUT calling waitFor first (no snapshot opportunity)
        Path pidB = dir.resolve("b.pid");
        ProcessBuilder pbB = new ProcessBuilder("bash", "-c",
                "bash -c 'echo $$ > " + pidB + "; exec sleep 300' & echo spawned");
        pbB.directory(dir.toFile());
        OwnedProcess b = OwnedProcess.start(pbB);
        Thread.sleep(600); // let shell exit and child detach, no waitFor polling
        long childB = readPid(pidB);
        System.out.println("B child pid=" + childB + " aliveBeforeCleanup=" + alive(childB)
                + " rootAlive=" + b.isAlive());
        boolean confirmedB = b.terminate(System.nanoTime() + TimeUnit.SECONDS.toNanos(2), 1000, false);
        System.out.println("B confirmed=" + confirmedB + " childAliveAfter=" + alive(childB));

        // cleanup leftovers
        ProcessHandle.of(childA).ifPresent(ProcessHandle::destroyForcibly);
        ProcessHandle.of(childB).ifPresent(ProcessHandle::destroyForcibly);
    }
}
