import com.aicodeassistant.tool.process.OwnedProcess;
import java.util.concurrent.TimeUnit;

public class Probe3 {
    public static void main(String[] a) throws Exception {
        var b = new ProcessBuilder("/usr/bin/env");
        b.environment().clear();
        b.environment().put("ONLY_THIS", "1");
        var p = OwnedProcess.start(b);
        p.waitFor(5, TimeUnit.SECONDS);
        System.out.println("K child env='" + new String(p.getInputStream().readAllBytes()).trim().replace('\n', '|') + "'");
    }
}
