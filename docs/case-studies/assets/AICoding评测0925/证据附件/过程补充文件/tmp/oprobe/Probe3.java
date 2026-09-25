import com.aicodeassistant.tool.process.OwnedProcess;
import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;

public class Probe3 {
    static long cpuMs() {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        return os.getProcessCpuTime() / 1_000_000;
    }
    public static void main(String[] args) throws Exception {
        System.out.println("os=" + System.getProperty("os.name"));
        for (int i = 0; i < 3; i++) {
            Process raw = new ProcessBuilder("sleep", "4").start();
            long c0 = cpuMs();
            raw.waitFor(20, TimeUnit.SECONDS);
            long rawMs = cpuMs() - c0;

            OwnedProcess owned = OwnedProcess.start(new ProcessBuilder("sleep", "4"));
            long c1 = cpuMs();
            owned.waitFor(20, TimeUnit.SECONDS);
            long ownedMs = cpuMs() - c1;
            OwnedProcess.terminateTree(owned, java.time.Duration.ZERO);
            System.out.println("run#" + i + " rawProcessCpuMs=" + rawMs + " ownedProcessCpuMs=" + ownedMs);
        }
    }
}
