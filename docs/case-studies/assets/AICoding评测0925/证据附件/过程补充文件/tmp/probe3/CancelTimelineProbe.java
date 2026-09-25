import com.aicodeassistant.tool.process.ManagedProcessRunner;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Timeline probe: when does the ActiveProcess reservation get a real process? */
public class CancelTimelineProbe {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var runner = new ManagedProcessRunner();
        var activeField = ManagedProcessRunner.class.getDeclaredField("active");
        activeField.setAccessible(true);
        var active = (Map<Object, Object>) activeField.get(runner);
        var processRef = Class.forName("com.aicodeassistant.tool.process.ManagedProcessRunner$ActiveProcess")
                .getDeclaredMethod("processRef");
        processRef.setAccessible(true);
        var started = new AtomicReference<ManagedProcessRunner.Result>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> {
                try {
                    started.set(runner.run(new ManagedProcessRunner.Request(
                            List.of("bash", "-c", "sleep 30"),
                            Path.of(System.getProperty("java.io.tmpdir")),
                            Duration.ofSeconds(30), "run-c", "tool-c")));
                } catch (Throwable failure) {
                    System.out.println("RUN THREW: " + failure);
                }
            });
            long t0 = System.nanoTime();
            for (int i = 0; i < 12; i++) {
                Thread.sleep(50);
                Object entry = active.values().stream().findFirst().orElse(null);
                Object ref = entry == null ? null : ((java.util.concurrent.atomic.AtomicReference<?>) processRef.invoke(entry)).get();
                System.out.printf("t=%4dms active=%d process=%s%n", (System.nanoTime() - t0) / 1_000_000,
                        active.size(), ref == null ? "null" : ref.getClass().getSimpleName());
            }
            long t1 = System.nanoTime();
            boolean cancelled = runner.cancel("run-c", "tool-c");
            System.out.println("cancel=" + cancelled + " in " + (System.nanoTime() - t1) / 1_000_000 + "ms");
            future.get(20, TimeUnit.SECONDS);
            System.out.println("result=" + started.get());
        }
    }
}
