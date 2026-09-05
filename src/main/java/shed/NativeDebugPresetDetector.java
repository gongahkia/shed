package shed;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finds concrete local native artifacts without inferring a source-file launch target. */
final class NativeDebugPresetDetector {
    private static final List<String> ARTIFACT_DIRECTORIES = List.of("build", "target/debug", "target/release");
    private static final int MAXIMUM_ARTIFACTS = 100;

    private NativeDebugPresetDetector() {
    }

    static DebugAdapterRegistry.Validation effective(DebugAdapterRegistry.Validation validation, Path workspace) {
        return DebugAdapterRegistry.withExternalConfigurations(validation, configurations(validation, workspace));
    }

    static Map<String, DebugAdapterRegistry.Configuration> configurations(DebugAdapterRegistry.Validation validation, Path workspace) {
        if (validation == null || !validation.valid() || workspace == null) return Map.of();
        Path root = workspace.toAbsolutePath().normalize();
        Map<String, DebugAdapterRegistry.Configuration> result = new LinkedHashMap<>();
        for (Path program : artifacts(root)) {
            addConfiguration(result, validation, root, program, BuiltInDebugAdapterSupport.NATIVE_GDB);
            addConfiguration(result, validation, root, program, BuiltInDebugAdapterSupport.NATIVE_LLDB);
        }
        return Map.copyOf(result);
    }

    static boolean isSuggestedConfiguration(String name) {
        return name != null && name.startsWith("suggested-native-");
    }

    private static List<Path> artifacts(Path root) {
        List<Path> result = new ArrayList<>();
        for (String relativeDirectory : ARTIFACT_DIRECTORIES) {
            if (result.size() >= MAXIMUM_ARTIFACTS) break;
            Path directory = root.resolve(relativeDirectory).normalize();
            if (!directory.startsWith(root) || Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                List<Path> sorted = new ArrayList<>();
                for (Path entry : entries) sorted.add(entry);
                sorted.sort((left, right) -> left.getFileName().toString().compareTo(right.getFileName().toString()));
                for (Path entry : sorted) {
                    if (result.size() >= MAXIMUM_ARTIFACTS) break;
                    if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(entry)
                        || !nativeExecutable(entry)) continue;
                    Path normalized = entry.toAbsolutePath().normalize();
                    if (normalized.startsWith(root)) result.add(normalized);
                }
            } catch (IOException | SecurityException ignored) {
                // Discovery is advisory. An unreadable build directory cannot block editing or configured debugging.
            }
        }
        return List.copyOf(result);
    }

    private static boolean nativeExecutable(Path candidate) {
        try (InputStream input = Files.newInputStream(candidate)) {
            byte[] header = input.readNBytes(4);
            if (header.length < 2) return false;
            if (header[0] == 'M' && header[1] == 'Z') return true;
            if (header.length < 4) return false;
            return (header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F')
                || (header[0] == (byte) 0xfe && header[1] == (byte) 0xed && header[2] == (byte) 0xfa && header[3] == (byte) 0xce)
                || (header[0] == (byte) 0xce && header[1] == (byte) 0xfa && header[2] == (byte) 0xed && header[3] == (byte) 0xfe)
                || (header[0] == (byte) 0xfe && header[1] == (byte) 0xed && header[2] == (byte) 0xfa && header[3] == (byte) 0xcf)
                || (header[0] == (byte) 0xcf && header[1] == (byte) 0xfa && header[2] == (byte) 0xed && header[3] == (byte) 0xfe)
                || (header[0] == (byte) 0xca && header[1] == (byte) 0xfe && header[2] == (byte) 0xba && header[3] == (byte) 0xbe)
                || (header[0] == (byte) 0xbe && header[1] == (byte) 0xba && header[2] == (byte) 0xfe && header[3] == (byte) 0xca);
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    private static void addConfiguration(Map<String, DebugAdapterRegistry.Configuration> result,
        DebugAdapterRegistry.Validation validation, Path root, Path program, String adapterId) {
        if (validation.registry().adapter(adapterId) == null) return;
        String relative = root.relativize(program).toString().replace(File.separatorChar, '/');
        String target = "${workspaceFolder}/" + relative;
        String name = uniqueName(result, validation, adapterId, program.getFileName().toString());
        DebugAdapterRegistry.Configuration configuration = new DebugAdapterRegistry.Configuration(name, adapterId,
            DebugAdapterRegistry.Request.LAUNCH, "workspace", target, "${workspaceFolder}", List.of(), "", "127.0.0.1", 0, List.of());
        if (DebugAdapterRegistry.externalConfigurationError(configuration, validation.registry().adapters()) == null) {
            result.put(name, configuration);
        }
    }

    private static String uniqueName(Map<String, DebugAdapterRegistry.Configuration> additions,
        DebugAdapterRegistry.Validation validation, String adapterId, String fileName) {
        String stem = fileName == null ? "artifact" : fileName.replaceAll("[^A-Za-z0-9_-]+", "-");
        stem = stem.replaceAll("^-+|-+$", "");
        if (stem.isBlank()) stem = "artifact";
        if (stem.length() > 48) stem = stem.substring(0, 48);
        String base = "suggested-" + adapterId + "-" + stem;
        String result = base;
        int suffix = 2;
        while (additions.containsKey(result) || validation.configurations().containsKey(result)) result = base + "-" + suffix++;
        return result;
    }
}
