import com.aicodeassistant.tool.process.OwnedProcess;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
public class OwnedProcessPortableProbe {
  static boolean live(long pid) throws Exception {
    if (System.getProperty("os.name").startsWith("Linux")) {
      try { String stat=Files.readString(Path.of("/proc",Long.toString(pid),"stat")); return !stat.substring(stat.lastIndexOf(')')+2).startsWith("Z"); }
      catch (java.nio.file.NoSuchFileException gone) { return false; }
    }
    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
  }
  public static void main(String[] args) throws Exception {
    Path dir=Path.of(args[0]); Files.createDirectories(dir);
    OwnedProcess process=OwnedProcess.start(new ProcessBuilder("bash","-c", "sleep 30 >/dev/null 2>&1 & echo $! > child.pid; exit 0").directory(dir.toFile()));
    ProcessHandle child=null;
    try {
      long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
      while(process.isAlive() && System.nanoTime()<deadline) Thread.sleep(5);
      long pid=Long.parseLong(Files.readString(dir.resolve("child.pid")).trim());
      child=ProcessHandle.of(pid).orElseThrow();
      boolean before=live(pid);
      boolean confirmed=OwnedProcess.terminateTree(process,Duration.ofMillis(50));
      System.out.println("PROBE portable: os="+System.getProperty("os.name")+" rootAlive="+process.isAlive()+" childBefore="+before+" terminationConfirmed="+confirmed+" childAfter="+live(pid));
    } finally { if(child!=null) { child.destroyForcibly(); child.onExit().get(2,TimeUnit.SECONDS); } OwnedProcess.terminateTree(process,Duration.ZERO); }
  }
}
