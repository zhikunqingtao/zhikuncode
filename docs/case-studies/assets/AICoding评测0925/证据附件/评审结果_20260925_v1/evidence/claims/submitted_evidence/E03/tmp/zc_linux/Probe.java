import com.aicodeassistant.tool.process.OwnedProcess;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class Probe {
    static boolean alive(long pid) { return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); }

    public static void main(String[] a) throws Exception {
        System.out.println("A setsid=" + Files.isExecutable(Path.of("/usr/bin/setsid"))
            + " bash=" + Files.isExecutable(Path.of("/bin/bash")) + " uid=" + System.getProperty("user.name"));

        // 1) normal command: does the fast path confirm cleanup?
        for (int i = 0; i < 3; i++) {
            var b = new ProcessBuilder("bash", "-c", "printf ok; printf perr >&2; exit 3");
            b.environment().put("PROBE_ENV", "v a l");
            long t0 = System.nanoTime();
            var p = OwnedProcess.start(b);
            p.waitFor(5, TimeUnit.SECONDS);
            String out = new String(p.getInputStream().readAllBytes());
            String err = new String(p.getErrorStream().readAllBytes());
            boolean conf = p.terminate(System.nanoTime() + 2_000_000_000L, 1000, false);
            System.out.println("B run" + i + " out='" + out + "' err='" + err + "' exit=" + p.exitValue()
                + " confirmed=" + conf + " ms=" + (System.nanoTime() - t0) / 1_000_000);
        }

        // 2) parent exits, stubborn same-group child ignoring TERM
        Files.deleteIfExists(Path.of("/tmp/release"));
        var b2 = new ProcessBuilder("bash", "-c",
            "bash -c 'trap \"\" TERM; echo $$ > /tmp/child.pid; while :; do sleep 1; done' >/dev/null 2>&1 & "
            + "while [ ! -f /tmp/release ]; do sleep .01; done; exit 0");
        var p2 = OwnedProcess.start(b2);
        long dl = System.nanoTime() + 3_000_000_000L;
        while (!Files.exists(Path.of("/tmp/child.pid")) && System.nanoTime() < dl) Thread.sleep(10);
        long child = Long.parseLong(Files.readString(Path.of("/tmp/child.pid")).strip());
        Files.createFile(Path.of("/tmp/release"));
        p2.waitFor(5, TimeUnit.SECONDS);
        System.out.println("C childAliveBeforeCleanup=" + alive(child));
        long t1 = System.nanoTime();
        boolean conf2 = p2.terminate(System.nanoTime() + 2_000_000_000L, 1000, false);
        System.out.println("D confirmed=" + conf2 + " ms=" + (System.nanoTime() - t1) / 1_000_000
            + " childAliveAfter=" + alive(child));

        // 3) detached (setsid) grandchild: retained via descendant snapshot, root-first grace
        var b3 = new ProcessBuilder("bash", "-c",
            "setsid bash -c 'trap \"\" TERM; echo $$ > /tmp/detached.pid; while :; do sleep 1; done' >/dev/null 2>&1 & wait");
        var p3 = OwnedProcess.start(b3);
        dl = System.nanoTime() + 3_000_000_000L;
        while (!Files.exists(Path.of("/tmp/detached.pid")) && System.nanoTime() < dl) Thread.sleep(10);
        long det = Long.parseLong(Files.readString(Path.of("/tmp/detached.pid")).strip());
        System.out.println("E detachedAliveBefore=" + alive(det));
        boolean conf3 = p3.terminate(System.nanoTime() + 4_000_000_000L, 200, true);
        System.out.println("F confirmed=" + conf3 + " detachedAliveAfter=" + alive(det));

        System.out.println("G done pid=" + ProcessHandle.current().pid());
    }
}
