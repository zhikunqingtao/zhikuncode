import com.aicodeassistant.tool.process.ManagedProcessRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Hits the launch window: cancelRunDetailed while the reservation exists but the
 * process has not been created yet, then reports the CancelSummary the Run
 * termination coordinator uses to decide the terminal Run state.
 */
public class LaunchWindowCancelProbe {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var runner = new ManagedProcessRunner();
        var activeField = ManagedProcessRunner.class.getDeclaredField("active");
        activeField.setAccessible(true);
        var active = (Map<Object, Object>) activeField.get(runner);
        var processRef = Class.forName("com.aicodeassistant.tool.process.ManagedProcessRunner$ActiveProcess")
                .getDeclaredMethod("processRef");
        processRef.setAccessible(true);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> {
                try {
                    return runner.run(new ManagedProcessRunner.Request(
                            List.of("bash", "-c", "sleep 5"),
                            Path.of(System.getProperty("java.io.tmpdir")),
                            Duration.ofSeconds(30), "run-w", "tool-w"));
                } catch (Throwable failure) {
                    return null;
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean hitWindow = false;
            while (System.nanoTime() < deadline) {
                Object entry = active.values().stream().findFirst().orElse(null);
                if (entry != null) {
                    Object ref = ((java.util.concurrent.atomic.AtomicReference<?>) processRef.invoke(entry)).get();
                    if (ref == null) { hitWindow = true; break; }
                }
            }
            System.out.println("hit launch window (reservation without process) = " + hitWindow);
            ManagedProcessRunner.CancelSummary summary = runner.cancelRunDetailed("run-w");
            System.out.println("CancelSummary = active=" + summary.activeCount()
                    + " confirmed=" + summary.confirmedCount()
                    + " unconfirmed=" + summary.unconfirmedCount()
                    + " allTerminated=" + summary.allTerminated());
            var result = future.get(20, TimeUnit.SECONDS);
            System.out.println("run result = " + result);
        }
    }
}
