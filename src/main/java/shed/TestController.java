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
    record Snapshot(Path root, List<TestService.AdapterSpec> adapters, List<TestService.TestCase> tests, List<String> diagnostics, String output, int runningJobs,
                    CoverageService.Summary coverage) {
        Snapshot {
            adapters = adapters == null ? List.of() : List.copyOf(adapters);
            tests = tests == null ? List.of() : List.copyOf(tests);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            output = output == null ? "" : output;
            coverage = coverage == null ? CoverageService.Report.empty().summary() : coverage;
        }
    }

    private static final class State {
        final Path root;
        List<TestService.AdapterSpec> specs = List.of();
        List<TestService.TestCase> tests = List.of();
        List<String> diagnostics = List.of();
        String output = "Refresh to discover tests.";
        final Map<Integer, String> jobs = new LinkedHashMap<>();
        CoverageService.Report coverage = CoverageService.Report.empty();
        State(Path root) { this.root = root; }
    }

    private record Execution(Path root, TestService.AdapterSpec spec, TestAdapter adapter, TestService.Command command, CommandResult result,
                             List<String> diagnostics, Path devContainerReportCache) {
        Execution {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
        Execution(Path root, TestService.AdapterSpec spec, TestAdapter adapter, TestService.Command command, CommandResult result,
                  List<String> diagnostics) {
            this(root, spec, adapter, command, result, diagnostics, null);
        }
    }
    private record StaticDiscovery(Path root, TestService.AdapterSpec spec, List<TestService.TestCase> tests) { }
    private record CoverageImport(Path root, CoverageService.ImportResult imported) { }

    private final Texteditor editor;
    private final TestService tests;
    private final CoverageService coverage = new CoverageService();
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
        if (value.regionMatches(true, 0, "debug ", 0, 6)) return debugId(selectedRoot(), value.substring(6).trim());
        return "Usage: :test [ui|refresh|run [test-id]|debug <test-id>|failed|cancel|text]";
    }

    String handleCoverage(String argument) {
        String value = argument == null ? "" : argument.trim();
        Path root = selectedRoot();
        if (value.isEmpty() || "ui".equalsIgnoreCase(value)) {
            editor.showToolWindow(ToolWindowHost.Tab.TESTS);
            return "Tests panel opened";
        }
        if ("clear".equalsIgnoreCase(value)) return clearCoverage(root);
        if ("text".equalsIgnoreCase(value)) {
            editor.showScratchBuffer("[coverage]", coverageText(root));
            return "Showing coverage";
        }
        if (value.regionMatches(true, 0, "import ", 0, 7)) value = value.substring(7).trim();
        if (value.isBlank()) return "Usage: :coverage import <report>|clear|text";
        return importCoverage(root, Path.of(value));
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
        return new Snapshot(state.root, state.specs, state.tests, state.diagnostics, state.output, state.jobs.size(), state.coverage.summary());
    }

    Result refresh(Path root) {
        State state = state(root);
        for (TestService.AdapterSpec spec : state.specs) editor.problemsController.clearQuickfixSource("test:" + spec.id());
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
            if ("maven".equals(spec.id()) || "gradle".equals(spec.id())) started += startStaticDiscovery(state, spec);
            TestService.Command command = adapter.discovery(state.root, spec);
            if (!command.executable()) continue;
            try {
                started += start(state, "discover", spec, adapter, command, remoteDiscoveryCache(state, spec, command));
            } catch (IOException error) {
                state.output = "Remote test report cache failed: " + error.getMessage();
                refreshPanel();
            }
        }
        if (started == 0) {
            if ("Discovery requested.".equals(state.output)) state.output = "No runnable discovery command.";
            refreshPanel();
        }
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

    private String runId(Path root, String id) {
        if (id == null || id.isBlank()) return "Test id required";
        for (TestService.TestCase test : state(root).tests) if (id.equals(test.id())) return runSelection(root, test);
        return "Test not found: " + id;
    }

    String debugSelection(Path root, TestService.TestCase test) {
        if (test == null) return "Select a test";
        State state = state(root);
        for (TestService.AdapterSpec spec : state.specs) {
            if (!spec.id().equals(test.adapterId())) continue;
            if (spec.debugConfiguration().isBlank()) return "Adapter " + spec.id() + " has no debug_configuration in .shedtests";
            return editor.debugSessionController.startTest(state.root, test, spec.debugConfiguration());
        }
        return "Selected test adapter is unavailable";
    }

    private String debugId(Path root, String id) {
        if (id == null || id.isBlank()) return "Usage: :test debug <test-id>";
        for (TestService.TestCase test : state(root).tests) if (id.equals(test.id())) return debugSelection(root, test);
        return "Test not found: " + id;
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

    String importCoverage(Path root, Path report) {
        Path workspace = root == null ? selectedRoot() : root.toAbsolutePath().normalize();
        if (report == null) return "Coverage report path required";
        Path resolved = report.isAbsolute() ? report.normalize() : workspace.resolve(report).normalize();
        State state = state(workspace);
        int jobId = editor.asyncJobService.submit("coverage import: " + resolved.getFileName(), token ->
            new CoverageImport(workspace, coverage.importReport(workspace, resolved)), (job, imported, error) -> completeCoverageImport(job, workspace, imported, error));
        state.jobs.put(jobId, "coverage");
        state.output = "coverage import running (job " + jobId + ")";
        refreshPanel();
        return "Coverage import requested";
    }

    String clearCoverage(Path root) {
        State state = state(root);
        state.coverage = CoverageService.Report.empty();
        state.output = "Coverage cleared";
        updateCoverageGutter(editor.getCurrentBuffer());
        refreshPanel();
        return "Coverage cleared";
    }

    void updateCoverageGutter(FileBuffer buffer) {
        if (editor.lineNumberPanel == null) return;
        if (buffer == null || !buffer.hasFilePath()) {
            editor.lineNumberPanel.updateCoverageMarkers(Map.of());
            return;
        }
        Path file = buffer.getFile().toPath().toAbsolutePath().normalize();
        Path root = coverageRootFor(file, rootsForPanel(), selectedRoot());
        editor.lineNumberPanel.updateCoverageMarkers(root == null ? Map.of() : state(root).coverage.hits(file));
    }

    /** Routes coverage to the workspace folder owning the file, not the Tests-panel selection. */
    static Path coverageRootFor(Path file, List<Path> roots, Path selectedRoot) {
        if (file == null) return null;
        Path normalized = file.toAbsolutePath().normalize();
        Path configured = WorkspaceRootResolver.configuredRoot(normalized, roots);
        if (configured != null) return configured;
        if (selectedRoot == null) return null;
        Path selected = selectedRoot.toAbsolutePath().normalize();
        return normalized.startsWith(selected) ? selected : null;
    }

    private void completeCoverageImport(AsyncJobService.JobSnapshot job, Path root, CoverageImport imported, Exception error) {
        State state = state(root);
        state.jobs.remove(job.getId());
        if (job.getStatus() == AsyncJobService.Status.CANCELLED) state.output = "Coverage import cancelled";
        else if (error != null) state.output = "Coverage import failed: " + error.getMessage();
        else if (imported == null) state.output = "Coverage import failed";
        else {
            state.coverage = state.coverage.merge(imported.imported().report());
            state.output = "Imported " + imported.imported().format().name().toLowerCase(Locale.ROOT) + " coverage: " + state.coverage.summary().display();
            updateCoverageGutter(editor.getCurrentBuffer());
        }
        refreshPanel();
    }

    private String coverageText(Path root) {
        State state = state(root);
        StringBuilder text = new StringBuilder("Coverage\n\n").append(state.coverage.summary().display()).append("\n\n");
        for (Map.Entry<Path, Map<Integer, CoverageService.Line>> entry : state.coverage.files().entrySet()) {
            long covered = entry.getValue().values().stream().filter(CoverageService.Line::covered).count();
            text.append(entry.getKey()).append(": ").append(covered).append('/').append(entry.getValue().size()).append(" lines\n");
        }
        return text.toString();
    }

    private int run(State state, TestService.AdapterSpec raw, List<TestService.TestCase> selection) {
        TestAdapter adapter = tests.adapter(raw.id());
        TestService.AdapterSpec spec = tests.resolvedSpec(state.root, raw);
        if (adapter == null || spec == null || spec.command().isEmpty()) return 0;
        if (selection != null && selection.size() > 1 && !adapter.supportsMultipleSelection()) {
            int started = 0;
            for (TestService.TestCase test : selection) started += run(state, raw, List.of(test));
            return started;
        }
        boolean devContainer = usesDevContainer(state.root);
        Path cache = null;
        try {
            if (remoteExecutionTarget(state.root) != null) {
                cache = Files.createTempDirectory(tests.reportCache(state.root, spec.id(), Path.of(editor.configManager.getShedDirectoryPath())), "remote-");
            } else if (devContainer) {
                cache = DevContainerTestExecution.createReportCache(state.root);
            } else {
                cache = tests.reportCache(state.root, spec.id(), Path.of(editor.configManager.getShedDirectoryPath()));
            }
            TestService.Command command = adapter.run(state.root, spec, selection == null ? List.of() : selection, cache);
            if (!command.executable()) {
                reportDevContainerCleanupFailure(state, discardDevContainerReportCache(state, devContainer, cache));
                return 0;
            }
            int started = start(state, "run", spec, adapter, command, cache);
            if (started == 0) reportDevContainerCleanupFailure(state, discardDevContainerReportCache(state, devContainer, cache));
            return started;
        } catch (IOException | RuntimeException error) {
            String message = error.getMessage();
            state.output = "Test report cache failed: " + (message == null || message.isBlank() ? error.getClass().getSimpleName() : message);
            reportDevContainerCleanupFailure(state, discardDevContainerReportCache(state, devContainer, cache));
            refreshPanel();
            return 0;
        }
    }

    private int start(State state, String operation, TestService.AdapterSpec spec, TestAdapter adapter, TestService.Command command) {
        return start(state, operation, spec, adapter, command, null);
    }

    private int start(State state, String operation, TestService.AdapterSpec spec, TestAdapter adapter, TestService.Command command, Path reportCache) {
        RemoteWorkspaceTaskTargets.Target remote = remoteExecutionTarget(state.root);
        if (remote != null) return startRemote(state, operation, spec, adapter, command, reportCache, remote);
        if (usesDevContainer(state.root)) return startDevContainer(state, operation, spec, adapter, command, reportCache);
        int jobId = editor.asyncJobService.submit("test " + spec.id() + " " + operation, token -> {
            CommandResult result = editor.jobQuickfixController.runExternalCommand(command.argv(), state.root.toFile(), null, token,
                editor.configManager.getProcessTimeoutMs(), editor.configManager.getProcessOutputMaxBytes(), true);
            return new Execution(state.root, spec, adapter, command, result, List.of());
        }, (job, execution, error) -> complete(state, job, execution, error));
        state.jobs.put(jobId, spec.id());
        state.output = operation + " " + spec.id() + " running (job " + jobId + ")";
        refreshPanel();
        return 1;
    }

    private int startDevContainer(State state, String operation, TestService.AdapterSpec spec, TestAdapter adapter, TestService.Command command,
                                  Path reportCache) {
        int jobId = editor.asyncJobService.submit("dev container test " + spec.id() + " " + operation, token -> {
            try {
                String remoteRoot = DevContainerRuntime.remoteWorkingDirectory(state.root);
                DevContainerTestExecution.Plan plan = DevContainerTestExecution.prepare(state.root, remoteRoot, command);
                CommandResult result = editor.jobQuickfixController.runExternalCommand(plan.invocation(), state.root.toFile(), null, token,
                    editor.configManager.getProcessTimeoutMs(), editor.configManager.getProcessOutputMaxBytes(), true);
                List<String> diagnostics = new ArrayList<>();
                TestService.Command safeCommand = DevContainerTestExecution.validatedCommand(command, reportCache, diagnostics);
                if (result.stdout != null && result.stdout.contains("[shed: output truncated]")) {
                    diagnostics.add("Dev Container test output was truncated; stdout-derived results may be incomplete.");
                }
                return new Execution(state.root, spec, adapter, safeCommand, result, diagnostics, reportCache);
            } catch (Exception error) {
                String message = error.getMessage();
                CommandResult result = new CommandResult(-1, "", "Dev Container test unavailable: "
                    + (message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ')));
                return new Execution(state.root, spec, adapter, command, result, List.of(), reportCache);
            }
        }, (job, execution, error) -> complete(state, job, execution, error));
        state.jobs.put(jobId, spec.id());
        state.output = operation + " " + spec.id() + " running in the Dev Container (job " + jobId + ")";
        refreshPanel();
        return 1;
    }

    private int startRemote(State state, String operation, TestService.AdapterSpec spec, TestAdapter adapter, TestService.Command command,
                            Path reportCache, RemoteWorkspaceTaskTargets.Target remote) {
        RemoteTestExecution.Plan plan;
        try {
            plan = RemoteTestExecution.prepare(remote, state.root, command, reportCache);
        } catch (IOException error) {
            state.output = "Remote " + spec.id() + " " + operation + " unavailable: " + error.getMessage();
            refreshPanel();
            return 0;
        }
        int jobId = editor.asyncJobService.submit("remote test " + spec.id() + " " + operation, token -> {
            RemoteTestExecution.Result result = RemoteTestExecution.execute(plan);
            return new Execution(state.root, spec, adapter, result.command(), result.result(), result.diagnostics());
        }, (job, execution, error) -> complete(state, job, execution, error));
        state.jobs.put(jobId, spec.id());
        state.output = operation + " " + spec.id() + " running remotely in " + remote.id() + " (job " + jobId + ")";
        refreshPanel();
        return 1;
    }

    private int startStaticDiscovery(State state, TestService.AdapterSpec spec) {
        int jobId = editor.asyncJobService.submit("test " + spec.id() + " discover", token ->
            new StaticDiscovery(state.root, spec, token.isCancelled() ? List.of() : tests.staticDiscovery(state.root, spec)),
            (job, discovery, error) -> completeStaticDiscovery(job, discovery, error));
        state.jobs.put(jobId, spec.id());
        state.output = "discover " + spec.id() + " running (job " + jobId + ")";
        refreshPanel();
        return 1;
    }

    private void completeStaticDiscovery(AsyncJobService.JobSnapshot job, StaticDiscovery discovery, Exception error) {
        if (discovery == null) return;
        State state = state(discovery.root());
        state.jobs.remove(job.getId());
        if (job.getStatus() == AsyncJobService.Status.CANCELLED) state.output = discovery.spec().id() + " discovery cancelled";
        else if (error != null) state.output = discovery.spec().id() + " discovery failed: " + error.getMessage();
        else {
            merge(state, discovery.tests());
            state.output = discovery.tests().isEmpty() ? "No Java tests found." : discovery.tests().size() + " Java tests discovered.";
        }
        refreshPanel();
    }

    private void complete(State expectedState, AsyncJobService.JobSnapshot job, Execution execution, Exception error) {
        State state = execution == null ? expectedState : state(execution.root());
        state.jobs.remove(job.getId());
        if (job.getStatus() == AsyncJobService.Status.CANCELLED) {
            state.output = execution == null ? "Test job cancelled" : execution.spec().id() + " cancelled";
        } else if (execution == null || error != null || execution.result() == null) {
            String adapter = execution == null ? "Test job" : execution.spec().id();
            state.output = adapter + " failed: " + (error == null ? job.getErrorMessage() : error.getMessage());
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
            if (!execution.diagnostics().isEmpty()) state.output += "\n" + String.join("\n", execution.diagnostics());
            if (!"discover".equals(operation(job))) publishProblems(state, execution.spec().id());
        }
        if (execution != null && execution.devContainerReportCache() != null) {
            String cleanup = DevContainerTestExecution.cleanupReportCache(execution.root(), execution.devContainerReportCache());
            if (!cleanup.isBlank()) state.output = state.output.isBlank() ? cleanup : state.output + "\n" + cleanup;
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

    private RemoteWorkspaceTaskTargets.Target remoteExecutionTarget(Path root) {
        RemoteWorkspaceTaskTargets.Target target = editor.remoteWorkspaceTaskTargets == null ? null : editor.remoteWorkspaceTaskTargets.targetForPath(root);
        if (target == null || target.workspace().executionRoot() == null) return null;
        String executionRoot = target.workspace().executionRoot().trim();
        return executionRoot.equals(target.localRoot().toString()) ? null : target;
    }

    private boolean usesDevContainer(Path root) {
        return DevContainerRuntime.hasConfiguration(root);
    }

    private static String discardDevContainerReportCache(State state, boolean devContainer, Path cache) {
        return !devContainer || cache == null ? "" : DevContainerTestExecution.cleanupReportCache(state.root, cache);
    }

    private void reportDevContainerCleanupFailure(State state, String cleanup) {
        if (cleanup == null || cleanup.isBlank()) return;
        state.output = state.output.isBlank() ? cleanup : state.output + "\n" + cleanup;
        refreshPanel();
    }

    private Path remoteDiscoveryCache(State state, TestService.AdapterSpec spec, TestService.Command command) throws IOException {
        if (remoteExecutionTarget(state.root) == null || command == null || command.reports().isEmpty()) return null;
        Path cache = tests.reportCache(state.root, spec.id(), Path.of(editor.configManager.getShedDirectoryPath()));
        return Files.createTempDirectory(cache, "remote-");
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
