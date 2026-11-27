import java.nio.file.*;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.*;
public final class Verify {
 public static void main(String[] args)throws Exception {
  var request=LauncherDiscoveryRequestBuilder.request().configurationParameter("junit.jupiter.extensions.autodetection.enabled","true");
  for(String name:Files.readAllLines(Path.of(args[0])))request.selectors(selectClass("shed."+name));
  var listener=new SummaryGeneratingListener();LauncherFactory.create().execute(request.build(),listener);var writer=new java.io.PrintWriter(System.out,true);listener.getSummary().printTo(writer);listener.getSummary().printFailuresTo(writer);
  var s=listener.getSummary();System.out.println("DIRECT_JUNIT_COUNTS "+s.getTestsFoundCount()+" "+s.getTestsSucceededCount()+" "+s.getTestsFailedCount()+" "+s.getTestsSkippedCount()+" "+s.getTestsAbortedCount());System.exit(s.getTotalFailureCount()==0?0:1);
 }
}
