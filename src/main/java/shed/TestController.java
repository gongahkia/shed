package shed;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TestController {
    record Snapshot(Path root, List<TestService.AdapterSpec> adapters, List<TestService.TestCase> tests, List<String> diagnostics, String output, int runningJobs) {
        Snapshot {
            adapters = adapters == null ? List.of() : List.copyOf(adapters);
            tests = tests == null ? List.of() : List.copyOf(tests);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            output = output == null ? "" : output;
        }
    }

    private static final class State {
        final Path root;
        List<TestService.AdapterSpec> specs = List.of();
        List<TestService.TestCase> tests = List.of();
        List<String> diagnostics = List.of();
        String output = "Refresh to discover tests.";
        final Map<Integer, String> jobs = new LinkedHashMap<>();
        State(Path root) { this.root = root; }
    }

    private record Execution(Path root, TestService.AdapterSpec spec, TestAdapter adapter, TestService.Command command, CommandResult result) { }

    private final Texteditor editor;
    private final TestService tests;
    private final Map<Path, State> states = new LinkedHashMap<>();
    private Path selectedRoot;

    TestController(Texteditor editor, TestService tests) {
        this.editor = editor;
        this.tests = tests == null ? new TestService() : tests;
    }

    String handle(String argument) {
        String value = argument == null ? "" : argument.trim();
        if (value.isEmpty() || "ui".equalsIgnoreCase(value)) {
            editor.showToolWindow(ToolWindowHost.Tab.TESTS);
            return "Tests panel opened";
        }
        if ("refresh".equalsIgnoreCase(value)) return refresh(selectedRoot()).message();
        if ("run".equalsIgnoreCase(value) || "all".equalsIgnoreCase(value)) return runAll(selectedRoot());
        if ("failed".equalsIgnoreCase(value) || "rerun-failed".equalsIgnoreCase(value)) return rerunFailed(selectedRoot());
        if ("cancel".equalsIgnoreCase(value)) return cancel(selectedRoot());
        if ("text".equalsIgnoreCase(value)) return showText(selectedRoot());
        if (value.regionMatches(true, 0, "run ", 0, 4)) return runId(selectedRoot(), value.substring(4).trim());
        return "Usage: :test [ui|refresh|run [test-id]|failed|cancel|text]";
    }

    List<Path> rootsForPanel() {
        List<Path> roots = new ArrayList<>(editor.workspaceController.roots());
        if (roots.isEmpty()) roots.add(selectedRoot());
        return roots.stream().filter(path -> path != null).distinct().toList();
    }

    void selectRoot(Path root) { if (root != null && Files.isDirectory(root)) selectedRoot = root.toAbsolutePath().normalize(); }
    Path selectedRoot() {
        if (selectedRoot != null && Files.isDirectory(selectedRoot)) return selectedRoot;
        Path active = editor.workspaceController.activeRoot();
        if (active != null) { selectedRoot = active; return active; }
        File fallback = editor.resolveTaskProjectRoot();
        selectedRoot = fallback == null ? Path.of(".").toAbsolutePath().normalize() : fallback.toPath();
        return selectedRoot;
    }

    Snapshot snapshot(Path root) {
        State state = state(root == null ? selectedRoot() : root);
        return new Snapshot(state.root, state.specs, state.tests, state.diagnostics, state.output, state.jobs.size());
    }

    Result refresh(Path root) {
        State state = state(root);
        TestService.LoadResult loaded = tests.load(state.root);
        state.specs = loaded.specs();
        state.diagnostics = loaded.diagnostics();
        state.tests = List.of();
        state.output = loaded.valid() ? loaded.specs().isEmpty() ? "No supported test runner detected. Add .shedtests to declare one." : "Discovery requested." : String.join("\n", loaded.diagnostics());
        refreshPanel();
        if (!loaded.valid()) return new Result("Test configuration invalid");
        int started = 0;
        for (TestService.AdapterSpec raw : loaded.specs()) {
            TestAdapter adapter = tests.adapter(raw.id());
            TestService.AdapterSpec spec = tests.resolvedSpec(state.root, raw);
            if (adapter == null || spec == null) continue;
            List<TestService.TestCase> staticTests = tests.staticDiscovery(state.root, spec);
            if (!staticTests.isEmpty()) merge(state, staticTests);
            TestService.Command command = adapter.discovery(spec);
            if (!command.executable()) continue;
            started += start(state, "discover", spec, adapter, command);
        }
        if (started == 0) { state.output = state.tests.isEmpty() ? "No runnable discovery command; Java tests listed from src/test/java." : "Discovery complete."; refreshPanel(); }
        return new Result(started == 0 ? "Test discovery complete" : "Test discovery requested (" + started + " job" + (started == 1 ? "" : "s") + ")");
    }

    String runAll(Path root) {
        State state = state(root);
        if (state.specs.isEmpty()) return "Refresh tests first";
        int started = 0;
        for (TestService.AdapterSpec raw : state.specs) started += run(state, raw, List.of());
        return started == 0 ? "No test runner available" : "Test run requested (" + started + " job" + (started == 1 ? "" : "s") + ")";
    }

    String runSelection(Path root, TestService.TestCase test) {
        if (test == null) return "Select a test";
        State state = state(root);
        for (TestService.AdapterSpec raw : state.specs) if (raw.id().equals(test.adapterId())) return run(state, raw, List.of(test)) == 0 ? "Test runner unavailable" : "Test run requested";
        return "Selected test adapter is unavailable";
    }

    String rerunFailed(Path root) {
        State state = state(root);
        Map<String, List<TestService.TestCase>> failed = new LinkedHashMap<>();
        for (TestService.TestCase test : state.tests) if (test.status().failed()) failed.computeIfAbsent(test.adapterId(), ignored -> new ArrayList<>()).add(test);
        if (failed.isEmpty()) return "No failed tests";
        int started = 0;
        for (TestService.AdapterSpec raw : state.specs) {
            List<TestService.TestCase> selection = failed.get(raw.id());
            if (selection != null) started += run(state, raw, selection);
        }
        return started == 0 ? "No failed test runner available" : "Failed tests requested";
    }

    String cancel(Path root) {
        State state = state(root);
        int cancelled = 0;
        for (Integer job : List.copyOf(state.jobs.keySet())) if (editor.asyncJobService.cancel(job)) cancelled++;
        return cancelled == 0 ? "No test job is running" : "Cancelled " + cancelled + " test job" + (cancelled == 1 ? "" : "s");
    }

    String open(TestService.TestCase test) {
        if (test == null) return "Select a test";
        if (test.file() == null || !Files.isRegularFile(test.file())) return "Test source location unavailable";
        try { editor.openFile(test.file().toFile()); return editor.gotoLine(test.line()); }
        catch (Exception error) { return "Test source open failed: " + error.getMessage(); }
    }

    private int run(State state, TestService.AdapterSpec raw, List<TestService.TestCase> selection) {
        TestAdapter adapter = tests.adapter(raw.id());
        TestService.AdapterSpec spec = tests.resolvedSpec(state.root, raw);
        if (adapter == null || spec == null || spec.command().isEmpty()) return 0;
        try {
            Path cache = tests.reportCache(state.root, spec.id());
            TestService.Command command = adapter.run(spec, selection == null ? List.of() : selection, cache);
            if (!command.executable()) return 0;
            return start(state, "run", spec, adapter, command);
        } catch (IOException error) {
            state.output = "Test report cache failed: " + error.getMessage();
            refreshPanel();
            return 0;
        }
    }

    private int start(State state, String operation, TestService.AdapterSpec spec, TestAdapter adapter, TestService.Command command) {
        int jobId = editor.asyncJobService.submit("test " + spec.id() + " " + operation, token -> {
            CommandResult result = editor.jobQuickfixController.runExternalCommand(command.argv(), state.root.toFile(), null, token,
                editor.configManager.getProcessTimeoutMs(), editor.configManager.getProcessOutputMaxBytes(), true);
            return new Execution(state.root, spec, adapter, command, result);
        }, (job, execution, error) -> complete(job, execution, error));
        state.jobs.put(jobId, spec.id());
        state.output = operation + " " + spec.id() + " running (job " + jobId + ")";
        refreshPanel();
        return 1;
    }

    private void complete(AsyncJobService.JobSnapshot job, Execution execution, Exception error) {
        if (execution == null) return;
        State state = state(execution.root());
        state.jobs.remove(job.getId());
        if (job.getStatus() == AsyncJobService.Status.CANCELLED) {
            state.output = execution.spec().id() + " cancelled";
        } else if (error != null || execution.result() == null) {
            state.output = execution.spec().id() + " failed: " + (error == null ? job.getErrorMessage() : error.getMessage());
        } else {
            String output = execution.result().stdout == null ? "" : execution.result().stdout;
            List<TestService.TestCase> parsed = "discover".equals(operation(job))
                ? execution.adapter().parseDiscovery(execution.root(), output)
                : execution.adapter().parseRun(execution.root(), execution.command(), output);
            if (execution.result().exitCode != 0 && parsed.isEmpty() && !"discover".equals(operation(job))) {
                parsed = List.of(new TestService.TestCase(execution.spec().id(), "[runner]", "Runner failed", execution.spec().id(), null, 1,
                    TestService.Status.ERRORED, 0, output.isBlank() ? execution.result().stderr : output));
            }
            if (!parsed.isEmpty()) merge(state, parsed);
            state.output = output.isBlank() ? execution.result().stderr : output;
            if (state.output.isBlank()) state.output = execution.spec().id() + " exited " + execution.result().exitCode;
            if (!"discover".equals(operation(job))) publishProblems(state, execution.spec().id());
        }
        refreshPanel();
    }

    private static String operation(AsyncJobService.JobSnapshot job) {
        String description = job == null ? "" : job.getDescription();
        return description.endsWith(" discover") ? "discover" : "run";
    }

    private void publishProblems(State state, String adapterId) {
        String source = "test:" + adapterId;
        editor.problemsController.clearQuickfixSource(source);
        List<QuickfixService.Entry> entries = new ArrayList<>();
        for (TestService.TestCase test : state.tests) {
            if (!adapterId.equals(test.adapterId()) || !test.status().failed() || test.file() == null) continue;
            entries.add(new QuickfixService.Entry(test.file().toString(), test.line(), 1, test.label() + (test.output().isBlank() ? "" : ": " + firstLine(test.output())), source));
        }
        if (!entries.isEmpty()) editor.problemsController.recordQuickfixEntries(entries);
    }

    private String showText(Path root) {
        Snapshot snapshot = snapshot(root);
        StringBuilder output = new StringBuilder("Tests\n\nroot: ").append(snapshot.root()).append('\n');
        for (String diagnostic : snapshot.diagnostics()) output.append("config: ").append(diagnostic).append('\n');
        for (TestService.TestCase test : snapshot.tests()) output.append(test.status().name().toLowerCase(Locale.ROOT)).append("  ").append(test.adapterId()).append("  ").append(test.label()).append('\n');
        editor.showScratchBuffer("[tests]", output.toString());
        return "Showing tests";
    }

    private State state(Path root) {
        Path normalized = (root == null ? selectedRoot() : root).toAbsolutePath().normalize();
        return states.computeIfAbsent(normalized, State::new);
    }

    private static void merge(State state, List<TestService.TestCase> incoming) {
        Map<String, TestService.TestCase> values = new LinkedHashMap<>();
        for (TestService.TestCase test : state.tests) values.put(test.adapterId() + "\u0000" + test.id(), test);
        for (TestService.TestCase test : incoming) values.put(test.adapterId() + "\u0000" + test.id(), test);
        state.tests = values.values().stream().sorted(Comparator.comparing(TestService.TestCase::adapterId).thenComparing(TestService.TestCase::suite).thenComparing(TestService.TestCase::name)).toList();
    }

    private void refreshPanel() { if (editor.toolWindowHost != null) editor.toolWindowHost.refresh(ToolWindowHost.Tab.TESTS); }
    private static String firstLine(String value) { return value == null ? "" : value.lines().findFirst().orElse("").strip(); }
    record Result(String message) { }
}
