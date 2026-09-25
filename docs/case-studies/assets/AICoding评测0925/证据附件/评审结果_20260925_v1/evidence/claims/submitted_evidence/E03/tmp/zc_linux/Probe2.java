import com.aicodeassistant.tool.process.OwnedProcess;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class Probe2 {
    public static void main(String[] a) throws Exception {
        // missing command: compare exit code + stderr shape
        var b = new ProcessBuilder("bash", "-c", "definitely-not-a-command-xyz");
        var p = OwnedProcess.start(b);
        p.waitFor(5, TimeUnit.SECONDS);
        System.out.println("H exit=" + p.exitValue() + " stderr='" + new String(p.getErrorStream().readAllBytes()).trim() + "'");
        OwnedProcess.terminateTree(p, Duration.ZERO);

        // piped stdin still EOF for the child (parity with the pre-change close())
        var b2 = new ProcessBuilder("bash", "-c", "cat; echo DONE-$?");
        var p2 = OwnedProcess.start(b2);
        p2.waitFor(5, TimeUnit.SECONDS);
        System.out.println("I out='" + new String(p2.getInputStream().readAllBytes()).trim() + "'");

        // environment with no PATH at all (isolated callers)
        var b3 = new ProcessBuilder("bash", "-c", "printf '%s' \"$PATH\"; command -v cat >/dev/null && echo ' cat=ok'");
        b3.environment().clear();
        var p3 = OwnedProcess.start(b3);
        p3.waitFor(5, TimeUnit.SECONDS);
        System.out.println("J out='" + new String(p3.getInputStream().readAllBytes()).trim() + "'");
    }
}
