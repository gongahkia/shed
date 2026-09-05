package shed;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finds conventional .NET application outputs without guessing project assembly names or output paths. */
final class DotnetDebugPresetDetector {
    private static final List<String> CONFIGURATIONS = List.of("Debug", "Release");

    private DotnetDebugPresetDetector() {
    }

    static DebugAdapterRegistry.Validation effective(DebugAdapterRegistry.Validation validation, Path workspace) {
        return DebugAdapterRegistry.withExternalConfigurations(validation, configurations(validation, workspace));
    }

    static Map<String, DebugAdapterRegistry.Configuration> configurations(DebugAdapterRegistry.Validation validation, Path workspace) {
        if (validation == null || !validation.valid() || workspace == null
            || validation.registry().adapter(BuiltInDebugAdapterSupport.CSHARP_NETCOREDBG) == null) return Map.of();
        Path root = workspace.toAbsolutePath().normalize();
        Map<String, DebugAdapterRegistry.Configuration> result = new LinkedHashMap<>();
        for (String projectName : projectNames(root)) {
            for (String configuration : CONFIGURATIONS) {
                Path output = root.resolve("bin").resolve(configuration).normalize();
                if (!output.startsWith(root) || Files.isSymbolicLink(output)
                    || !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) continue;
                addApplicationOutput(result, validation, root, output.resolve(projectName + ".dll"), projectName);
                for (Path framework : directDirectories(output)) {
                    addApplicationOutput(result, validation, root, framework.resolve(projectName + ".dll"), projectName);
                }
            }
        }
        return Map.copyOf(result);
    }

    static boolean isSuggestedConfiguration(String name) {
        return name != null && name.startsWith("suggested-csharp-netcoredbg-");
    }

    private static List<String> projectNames(Path root) {
        List<String> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, "*.csproj")) {
            for (Path project : entries) {
                if (Files.isSymbolicLink(project) || !Files.isRegularFile(project, LinkOption.NOFOLLOW_LINKS)) continue;
                String name = project.getFileName().toString();
                result.add(name.substring(0, name.length() - ".csproj".length()));
            }
        } catch (IOException | SecurityException ignored) {
            return List.of();
        }
        result.sort(String::compareTo);
        return List.copyOf(result);
    }

    private static List<Path> directDirectories(Path parent) {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
            for (Path entry : entries) {
                if (!Files.isSymbolicLink(entry) && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) result.add(entry);
            }
        } catch (IOException | SecurityException ignored) {
            return List.of();
        }
        result.sort((left, right) -> left.getFileName().toString().compareTo(right.getFileName().toString()));
        return List.copyOf(result);
    }

    private static void addApplicationOutput(Map<String, DebugAdapterRegistry.Configuration> result,
        DebugAdapterRegistry.Validation validation, Path root, Path assembly, String projectName) {
        Path runtimeConfiguration = assembly.resolveSibling(projectName + ".runtimeconfig.json");
        if (Files.isSymbolicLink(assembly) || Files.isSymbolicLink(runtimeConfiguration)
            || !Files.isRegularFile(assembly, LinkOption.NOFOLLOW_LINKS)
            || !Files.isRegularFile(runtimeConfiguration, LinkOption.NOFOLLOW_LINKS)) return;
        Path program = assembly.toAbsolutePath().normalize();
        if (!program.startsWith(root)) return;
        String target = "${workspaceFolder}/" + root.relativize(program).toString().replace('\\', '/');
        String name = uniqueName(result, validation, projectName);
        DebugAdapterRegistry.Configuration configuration = new DebugAdapterRegistry.Configuration(name,
            BuiltInDebugAdapterSupport.CSHARP_NETCOREDBG, DebugAdapterRegistry.Request.LAUNCH, "workspace", target,
            "${workspaceFolder}", List.of(), "", "127.0.0.1", 0, List.of());
        if (DebugAdapterRegistry.externalConfigurationError(configuration, validation.registry().adapters()) == null) {
            result.put(name, configuration);
        }
    }

    private static String uniqueName(Map<String, DebugAdapterRegistry.Configuration> additions,
        DebugAdapterRegistry.Validation validation, String projectName) {
        String stem = projectName == null ? "application" : projectName.replaceAll("[^A-Za-z0-9_-]+", "-");
        stem = stem.replaceAll("^-+|-+$", "");
        if (stem.isBlank()) stem = "application";
        if (stem.length() > 48) stem = stem.substring(0, 48);
        String base = "suggested-csharp-netcoredbg-" + stem;
        String result = base;
        int suffix = 2;
        while (additions.containsKey(result) || validation.configurations().containsKey(result)) result = base + "-" + suffix++;
        return result;
    }
}
