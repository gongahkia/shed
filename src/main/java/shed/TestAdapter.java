package shed;

import java.nio.file.Path;
import java.util.List;

interface TestAdapter {
    String id();
    boolean supports(Path root);
    List<String> defaultCommand(Path root);
    TestService.Command discovery(TestService.AdapterSpec spec);
    TestService.Command run(TestService.AdapterSpec spec, List<TestService.TestCase> selection, Path cacheDirectory);
    List<TestService.TestCase> parseDiscovery(Path root, String output);
    List<TestService.TestCase> parseRun(Path root, TestService.Command command, String output);
}
