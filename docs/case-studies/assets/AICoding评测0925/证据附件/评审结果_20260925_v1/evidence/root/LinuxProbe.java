import com.aicodeassistant.tool.process.OwnedProcess;
import java.nio.file.*;
import java.util.concurrent.*;

public class LinuxProbe {
  public static void main(String[] args) throws Exception {
    System.out.println("os=" + System.getProperty("os.name") + " java=" + System.getProperty("java.version"));
    System.out.println("setsid=" + Files.isExecutable(Path.of("/usr/bin/setsid")) + " bash=" + Files.isExecutable(Path.of("/bin/bash")));
    for (boolean owned : new boolean[]{false, true}) {
      Path pidFile = Files.createTempFile("eval-child-", ".pid");
      Process p = null; ProcessHandle child = null;
      try {
        ProcessBuilder b = new ProcessBuilder("/bin/bash", "-c", "sleep 30 >/dev/null 2>&1 & echo $! > \"$1\"; echo ok; printf err >&2; exit 3", "probe", pidFile.toString());
        p = owned ? OwnedProcess.start(b) : b.start();
        if (!p.waitFor(3,TimeUnit.SECONDS)) throw new IllegalStateException("shell timeout");
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        child = ProcessHandle.of(pid).orElse(null);
        boolean before = child != null && child.isAlive();
        boolean confirmed = owned && ((OwnedProcess)p).terminate(System.nanoTime()+TimeUnit.SECONDS.toNanos(2),100,false);
        boolean after = child != null && child.isAlive();
        System.out.println("mode="+(owned?"target-owned":"parent-normal-completion")+" exit="+p.exitValue()+" stdout="+new String(p.getInputStream().readAllBytes()).trim()+" stderr="+new String(p.getErrorStream().readAllBytes()).trim()+" childBefore="+before+" cleanupConfirmed="+confirmed+" childAfter="+after);
        if (owned && (!confirmed || after || p.exitValue()!=3)) throw new AssertionError("owned lifecycle mismatch");
        if (!owned && !after) throw new AssertionError("baseline child unexpectedly missing");
      } finally { if(child!=null&&child.isAlive()){ child.destroyForcibly(); child.onExit().get(3,TimeUnit.SECONDS); } if(p!=null&&p.isAlive())p.destroyForcibly(); Files.deleteIfExists(pidFile); }
    }
  }
}
