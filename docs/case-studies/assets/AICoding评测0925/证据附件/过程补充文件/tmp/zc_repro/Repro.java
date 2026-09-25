import com.aicodeassistant.run.RunExecutionRegistry;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import com.aicodeassistant.engine.AbortContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public class Repro {
    public static void main(String[] args) throws Exception {
        var registry = new RunExecutionRegistry();
        registry.register("run-1", "session-1", new AbortContext());
        var runner = new ManagedProcessRunner(registry);
        var result = runner.run(new ManagedProcessRunner.Request(
                List.of("bash", "-c", "printf hello"), Path.of(System.getProperty("java.io.tmpdir")),
                Duration.ofSeconds(5), "run-1", "tool-1", deadline -> false));
        System.out.println("[1] exitCode=" + result.exitCode()
                + " terminationConfirmed=" + result.terminationConfirmed());

        registry.unregister("run-1");
        System.out.println("[2] isRegistered(run-1)=" + registry.isRegistered("run-1")
                + " quiescent=" + registry.awaitQuiescence("run-1", Duration.ZERO)
                + " runBySession(session-1)=" + registry.activeRunForSession("session-1").orElse("<none>"));

        try {
            registry.register("run-2", "session-1", new AbortContext());
            System.out.println("[3] SECOND RUN REGISTERED OK");
        } catch (Exception e) {
            System.out.println("[3] SECOND RUN IN SAME SESSION FAILED: " + e);
        }

        System.out.println("[4] cancelRunDetailed(run-1)=" + runner.cancelRunDetailed("run-1"));
        try {
            registry.register("run-3", "session-1", new AbortContext());
            System.out.println("[5] THIRD RUN REGISTERED OK (recovered)");
        } catch (Exception e) {
            System.out.println("[5] THIRD RUN FAILED: " + e);
        }
    }
}
