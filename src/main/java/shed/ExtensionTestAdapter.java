package shed;

import shed.api.ExtensionTestCase;
import shed.api.TestCommand;
import shed.api.TestContribution;
import shed.api.TestStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** Adapts a versioned extension testing provider to Shed's native Tests view. */
final class ExtensionTestAdapter implements TestAdapter {
    private final String id;
    private final TestContribution contribution;

    ExtensionTestAdapter(ExtensionRegistry.Owned<TestContribution> owned) {
        if (owned == null || owned.value() == null) throw new IllegalArgumentException("test contribution is required");
        this.id = adapterId(owned);
        this.contribution = owned.value();
    }

    static String adapterId(ExtensionRegistry.Owned<TestContribution> owned) {
        if (owned == null || owned.value() == null) throw new IllegalArgumentException("test contribution is required");
        return "extension-" + encode(owned.extensionId()) + "-" + encode(owned.value().id());
    }

    @Override public String id() { return id; }

    @Override public boolean supports(Path root) {
        try {
            return root != null && contribution.supports(root);
        } catch (Exception error) {
            return false;
        }
    }

    @Override public List<String> defaultCommand(Path root) {
        // The provider supplies the actual argv in discovery/run. This marker only
        // satisfies the native adapter contract without ever being executed.
        return List.of("shed-extension-provider");
    }

    @Override public TestService.Command discovery(TestService.AdapterSpec spec) {
        return new TestService.Command(List.of(), List.of());
    }

    @Override public TestService.Command discovery(Path root, TestService.AdapterSpec spec) {
        try {
            return command(contribution.discovery(root));
        } catch (Exception error) {
            throw new IllegalStateException("extension test discovery failed: " + concise(error), error);
        }
    }

    @Override public TestService.Command run(TestService.AdapterSpec spec, List<TestService.TestCase> selection, Path cacheDirectory) {
        return new TestService.Command(List.of(), List.of());
    }

    @Override public TestService.Command run(Path root, TestService.AdapterSpec spec, List<TestService.TestCase> selection, Path cacheDirectory) {
        try {
            List<ExtensionTestCase> selected = (selection == null ? List.<TestService.TestCase>of() : selection).stream()
                .map(ExtensionTestAdapter::testCase).toList();
            return command(contribution.run(root, selected, cacheDirectory));
        } catch (Exception error) {
            throw new IllegalStateException("extension test run setup failed: " + concise(error), error);
        }
    }

    @Override public List<TestService.TestCase> parseDiscovery(Path root, String output) {
        try {
            return nativeTests(contribution.parseDiscovery(root, output));
        } catch (Exception error) {
            throw new IllegalStateException("extension test discovery parsing failed: " + concise(error), error);
        }
    }

    @Override public List<TestService.TestCase> parseRun(Path root, TestService.Command command, String output) {
        try {
            return nativeTests(contribution.parseRun(root, extensionCommand(command), output));
        } catch (Exception error) {
            throw new IllegalStateException("extension test result parsing failed: " + concise(error), error);
        }
    }

    private TestService.Command command(TestCommand command) {
        if (command == null) return new TestService.Command(List.of(), List.of());
        return new TestService.Command(command.arguments(), command.reports());
    }

    private TestCommand extensionCommand(TestService.Command command) {
        if (command == null) return new TestCommand(List.of(), List.of());
        return new TestCommand(command.argv(), command.reports());
    }

    private List<TestService.TestCase> nativeTests(List<ExtensionTestCase> tests) {
        return (tests == null ? List.<ExtensionTestCase>of() : tests).stream().filter(java.util.Objects::nonNull)
            .map(value -> new TestService.TestCase(id, value.id(), value.name(), value.suite(), value.file(), value.line(), status(value.status()),
                value.durationMillis(), value.output())).toList();
    }

    private static ExtensionTestCase testCase(TestService.TestCase value) {
        return new ExtensionTestCase(value.id(), value.name(), value.suite(), value.file(), value.line(), status(value.status()), value.durationMillis(), value.output());
    }

    private static TestService.Status status(TestStatus value) { return TestService.Status.valueOf((value == null ? TestStatus.UNKNOWN : value).name()); }
    private static TestStatus status(TestService.Status value) { return TestStatus.valueOf((value == null ? TestService.Status.UNKNOWN : value).name()); }

    private static String encode(String value) {
        StringBuilder result = new StringBuilder();
        for (byte part : value.getBytes(StandardCharsets.UTF_8)) result.append(String.format(java.util.Locale.ROOT, "%02x", part));
        return result.toString();
    }

    private static String concise(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }
}
