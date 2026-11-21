package shed;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** reviewed direct Java CLI fixture only; not a shell launcher or an OS sandbox. */
final class WorkspaceCliTestProcess {
    private WorkspaceCliTestProcess() { }

    static ProcessBuilder prepare(ProcessBuilder requested) throws IOException {
        try { NativeFixtureGate.requireContext(); } catch (Exception invalid) { throw new IOException("Missing disposable Java CLI context"); }
        Path fixture = Path.of(System.getenv("SHED_PORTABLE_FIXTURE_ROOT")).toRealPath();
        Path run = fixture.resolve("run");
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
        List<String> given = requested.command();
        int main = given.indexOf("shed.Texteditor");
        if (main < 2 || given.size() > 24 || !Path.of(given.getFirst()).toRealPath().equals(javaExecutable) || main + 1 >= given.size()
            || !"--workspace-api".equals(given.get(main + 1))) throw new IOException("Only the fixed headless workspace CLI is admitted");
        Path home = confined(Path.of(System.getProperty("user.home")), run);
        for (int i = 1; i < main; i++) {
            String option = given.get(i);
            if ("-cp".equals(option) && i + 1 < main) { i++; continue; }
            if ("-Djava.awt.headless=true".equals(option)) continue;
            if (option.startsWith("-Duser.home=")) { home = confined(Path.of(option.substring("-Duser.home=".length())), run); continue; }
            throw new IOException("Unsupported Java CLI option");
        }
        for (int i = main + 2; i < given.size(); i++) {
            String option = given.get(i);
            if (Set.of("--stdio", "--status", "--metadata", "--navigate").contains(option)) continue;
            if (!Set.of("--root", "--connect", "--credential", "--request").contains(option) || ++i == given.size())
                throw new IOException("Unsupported workspace CLI option");
            if (!"--request".equals(option)) confined(Path.of(given.get(i)), run);
            else if (given.get(i).length() > 4096) throw new IOException("Fixture request exceeds its bound");
        }
        List<String> classpath = new ArrayList<>();
        if (!(WorkspaceCliTestProcess.class.getClassLoader() instanceof URLClassLoader loader)) throw new IOException("Unsupported test classloader");
        Path target = Path.of("target").toRealPath();
        for (var url : loader.getURLs()) {
            if (!"file".equals(url.getProtocol()) || classpath.size() >= 256) throw new IOException("Unsupported fixture classpath");
            Path entry;
            try { entry = Path.of(url.toURI()).toRealPath(); } catch (java.net.URISyntaxException invalid) { throw new IOException("Invalid fixture classpath"); }
            if (!entry.startsWith(target) && !entry.startsWith(run.resolve("dependencies"))) throw new IOException("Classpath outside reviewed build/dependency roots");
            classpath.add(entry.toString());
        }
        if (classpath.isEmpty()) throw new IOException("Empty fixture classpath");
        List<String> command = new ArrayList<>(List.of(javaExecutable.toString(), "-Djava.awt.headless=true", "-Duser.home=" + home,
            "-Djava.io.tmpdir=" + confined(Path.of(System.getProperty("java.io.tmpdir")), run),
            "-Djava.util.prefs.userRoot=" + confined(Path.of(System.getProperty("java.util.prefs.userRoot")), run),
            "-cp", String.join(java.io.File.pathSeparator, classpath), "shed.Texteditor"));
        command.addAll(given.subList(main + 1, given.size()));
        var result = new ProcessBuilder(command).directory(confined(run.resolve("work"), run).toFile());
        result.environment().clear();
        result.environment().putAll(Map.of("HOME", home.toString(), "TMPDIR", run.resolve("tmp").toString(),
            "XDG_CONFIG_HOME", run.resolve("config").toString(), "XDG_DATA_HOME", run.resolve("data").toString(),
            "XDG_CACHE_HOME", run.resolve("cache").toString(), "XDG_STATE_HOME", run.resolve("state").toString(),
            "PATH", run.resolve("no-executables").toString(), "LANG", "en_US.UTF-8", "LC_ALL", "en_US.UTF-8", "TZ", "UTC"));
        return result;
    }

    static Process start(ProcessBuilder requested) throws IOException { return prepare(requested).start(); }

    static void stop(Process process) throws Exception {
        try {
            if (process.isAlive()) process.destroyForcibly();
            if (!process.waitFor(3, TimeUnit.SECONDS)) throw new IOException("Owned CLI process did not exit");
            if (process.descendants().anyMatch(ProcessHandle::isAlive)) throw new IOException("Unexpected CLI descendant");
        } finally {
            process.getOutputStream().close(); process.getInputStream().close(); process.getErrorStream().close();
        }
    }

    private static Path confined(Path requested, Path root) throws IOException {
        Path path = requested.toAbsolutePath();
        if (!path.equals(path.normalize()) || !path.startsWith(root)) throw new IOException("CLI fixture path outside disposable root");
        for (Path part = path; part != null && part.startsWith(root); part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IOException("Symbolic link in CLI fixture path");
        Path existing = path;
        while (!Files.exists(existing)) existing = existing.getParent();
        if (!existing.toRealPath().startsWith(root)) throw new IOException("CLI fixture identity outside disposable root");
        return path;
    }
}
