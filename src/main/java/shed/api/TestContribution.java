package shed.api;

import java.nio.file.Path;
import java.util.List;

/** Language/framework-specific discovery and execution provider for the Tests view. */
public interface TestContribution {
    String id();

    String displayName();

    boolean supports(Path workspaceRoot);

    TestCommand discovery(Path workspaceRoot) throws Exception;

    List<ExtensionTestCase> parseDiscovery(Path workspaceRoot, String output) throws Exception;

    TestCommand run(Path workspaceRoot, List<ExtensionTestCase> selection, Path reportCache) throws Exception;

    List<ExtensionTestCase> parseRun(Path workspaceRoot, TestCommand command, String output) throws Exception;
}
