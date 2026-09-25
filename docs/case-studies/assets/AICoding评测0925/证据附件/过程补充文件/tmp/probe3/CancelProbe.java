import com.aicodeassistant.tool.process.ManagedProcessRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Reproduces ManagedProcessRunnerTest#cancellationIsDistinguishedFromTimeout without Spring/JUnit. */
public class CancelProbe {
    public static void main(String[] args) throws Exception {
        var runner = new ManagedProcessRunner();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> {
                try {
                    return runner.run(new ManagedProcessRunner.Request(
                            List.of("bash", "-c", "sleep 30"),
                            Path.of(System.getProperty("java.io.tmpdir")),
                            Duration.ofSeconds(30), "run-c", "tool-c"));
                } catch (Throwable failure) {
                    System.out.println("RUN THREW: " + failure);
                    return null;
                }
            });
            Thread.sleep(300);
            System.out.println("cancelRunDetailed(activeCount)=" + runner.cancelRunDetailed("run-c").activeCount());
            System.out.println("cancel=" + runner.cancel("run-c", "tool-c"));
            var result = future.get(20, TimeUnit.SECONDS);
            System.out.println("result=" + (result == null ? "null" : ("cancelled=" + result.cancelled()
                    + " exit=" + result.exitCode() + " timedOut=" + result.timedOut()
                    + " confirmed=" + result.terminationConfirmed())));
        }
        System.out.println("permits after=" + ((java.util.concurrent.Semaphore) field(runner, "capacity")).availablePermits());
    }

    private static Object field(Object target, String name) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
