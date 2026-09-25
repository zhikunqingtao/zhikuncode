import com.aicodeassistant.tool.process.OwnedProcess;
import java.util.concurrent.TimeUnit;

public class Probe {
    public static void main(String[] args) throws Exception {
        System.out.println("os=" + System.getProperty("os.name"));

        Process p0 = new ProcessBuilder("sleep", "1").start();
        try (var d = p0.descendants()) {
            System.out.println("descendants(): available");
        } catch (RuntimeException e) {
            System.out.println("descendants(): UNAVAILABLE -> " + e);
        }
        p0.destroyForcibly();
        p0.waitFor(2, TimeUnit.SECONDS);

        // Case 1: simple foreground command, shell exits immediately (ManagedProcessRunner path)
        for (int i = 0; i < 3; i++) {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", "echo done");
            OwnedProcess owned = OwnedProcess.start(pb);
            boolean completed = owned.waitFor(5, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            long t0 = System.nanoTime();
            boolean confirmed = owned.terminate(deadline, 1000, false);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("simple#" + i + " completed=" + completed + " exit=" + owned.exitValue()
                    + " confirmed=" + confirmed + " cleanupMs=" + ms);
        }

        // Case 2: shell exits but leaves a lingering foreground-spawned child
        for (int i = 0; i < 2; i++) {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", "sleep 30 & echo spawned");
            OwnedProcess owned = OwnedProcess.start(pb);
            boolean completed = owned.waitFor(5, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            long t0 = System.nanoTime();
            boolean confirmed = owned.terminate(deadline, 1000, false);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("lingering#" + i + " completed=" + completed + " confirmed=" + confirmed
                    + " cleanupMs=" + ms);
        }

        // Case 3: still-running process terminated early (timeout path)
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", "sleep 60");
        OwnedProcess owned = OwnedProcess.start(pb);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        long t0 = System.nanoTime();
        boolean confirmed = owned.terminate(deadline, 1000, false);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("kill-running completed=false confirmed=" + confirmed + " cleanupMs=" + ms);

        // Case 4: gracefulRootFirst (PythonProcessManager path)
        ProcessBuilder pb4 = new ProcessBuilder("bash", "-c", "trap 'exit 0' TERM; while :; do sleep 0.05; done");
        OwnedProcess owned4 = OwnedProcess.start(pb4);
        Thread.sleep(300);
        long deadline4 = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        long t4 = System.nanoTime();
        boolean confirmed4 = owned4.terminate(deadline4, 10_000, true);
        long ms4 = (System.nanoTime() - t4) / 1_000_000;
        System.out.println("gracefulRootFirst confirmed=" + confirmed4 + " cleanupMs=" + ms4);
    }
}
