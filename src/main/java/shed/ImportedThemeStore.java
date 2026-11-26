package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded offline palettes and provenance; no downloaded code or archives are installed. */
final class ImportedThemeStore {
    static final int MAX_BYTES = 1024 * 1024;
    private final Path file;

    ImportedThemeStore(Path shedDirectory) { file = shedDirectory.toAbsolutePath().normalize().resolve("themes/open-vsx.json"); }

    List<ImportedTheme> load() throws IOException { return decode(read()); }

    synchronized List<ImportedTheme> install(List<ImportedTheme> themes) throws IOException {
        if (themes.isEmpty() || themes.size() > 64) throw new IOException("A package must contain 1–64 supported themes");
        String packageId = themes.getFirst().packageId();
        for (ImportedTheme theme : themes) {
            theme.validate();
            if (!theme.packageId().equals(packageId)) throw new IOException("Mixed theme packages are not supported");
        }
        byte[] before = read();
        List<ImportedTheme> next = new ArrayList<>(decode(before));
        next.removeIf(theme -> theme.packageId().equals(packageId));
        next.addAll(themes);
        write(before, next);
        return List.copyOf(next);
    }

    synchronized List<ImportedTheme> remove(String packageId) throws IOException {
        byte[] before = read();
        List<ImportedTheme> next = new ArrayList<>(decode(before));
        next.removeIf(theme -> theme.packageId().equals(packageId));
        write(before, next);
        return List.copyOf(next);
    }

    private byte[] read() throws IOException {
        validatePaths(false);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new byte[0];
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Installed theme catalog exceeds 1 MiB");
            return bytes;
        }
    }

    private List<ImportedTheme> decode(byte[] bytes) throws IOException {
        if (bytes.length == 0) return List.of();
        Map<String, Object> root = Jsonc.parseObject(new String(bytes, StandardCharsets.UTF_8));
        List<Object> entries = MiniJson.asArray(root.get("themes"));
        if (!Integer.valueOf(1).equals(MiniJson.asInt(root.get("version"))) || entries == null || entries.size() > 128)
            throw new IOException("Unsupported installed theme catalog");
        Map<String, ImportedTheme> result = new LinkedHashMap<>();
        for (Object entry : entries) {
            ImportedTheme theme = ImportedTheme.fromJson(MiniJson.asObject(entry));
            if (result.put(theme.id(), theme) != null) throw new IOException("Duplicate installed theme identity");
        }
        return List.copyOf(result.values());
    }

    private void write(byte[] before, List<ImportedTheme> themes) throws IOException {
        if (themes.size() > 128) throw new IOException("Installed theme limit is 128; remove unused packages first");
        for (ImportedTheme theme : themes) theme.validate();
        byte[] bytes = MiniJson.stringify(Map.of("version", 1, "themes", themes.stream().map(ImportedTheme::toJson).toList()))
            .getBytes(StandardCharsets.UTF_8);
        decode(bytes);
        if (bytes.length > MAX_BYTES) throw new IOException("Installed theme catalog exceeds 1 MiB");
        validatePaths(true);
        AtomicFileWriter.write(file, bytes, () -> {
            validatePaths(false);
            if (!Arrays.equals(before, read())) throw new IOException("Themes changed in another window; reload the gallery and retry");
        });
    }

    private void validatePaths(boolean create) throws IOException {
        // Check path components, not string prefixes; never traverse a catalog/directory symlink.
        Path current = file.getRoot();
        for (Path part : file) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Theme storage must not use symbolic links");
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (current.equals(file) ? !Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)
                    : !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid theme storage path");
            } else if (create && !current.equals(file)) Files.createDirectory(current);
        }
    }
}
