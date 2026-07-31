package shed;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class CommandPathCompletion {
    private static final int DEFAULT_LIMIT = 12;

    private CommandPathCompletion() {
    }

    static List<String> suggestions(String commandBuffer, File baseDirectory) {
        Request request = Request.parse(commandBuffer);
        if (request == null) {
            return List.of();
        }
        File base = baseDirectory == null ? new File(".") : baseDirectory;
        File typed = resolve(base, request.pathPrefix());
        File directory = typed.isDirectory() ? typed : typed.getParentFile();
        String needle = typed.isDirectory() ? "" : typed.getName();
        if (directory == null || !directory.isDirectory()) {
            return List.of();
        }
        File[] files = directory.listFiles(file -> file.getName().startsWith(needle));
        if (files == null || files.length == 0) {
            return List.of();
        }
        Arrays.sort(files, Comparator.comparing(File::isFile).thenComparing(file -> file.getName().toLowerCase(Locale.ROOT)));
        List<String> values = new ArrayList<>();
        for (File file : files) {
            String replacement = displayPath(base, file, request.pathPrefix());
            if (file.isDirectory()) {
                replacement += File.separator;
            }
            values.add(request.prefix() + replacement);
            if (values.size() == DEFAULT_LIMIT) {
                break;
            }
        }
        return List.copyOf(values);
    }

    private static File resolve(File base, String path) {
        String expanded = path.startsWith("~" + File.separator) ? System.getProperty("user.home") + path.substring(1) : path;
        File value = new File(expanded);
        return value.isAbsolute() ? value : new File(base, expanded);
    }

    private static String displayPath(File base, File file, String typedPrefix) {
        if (new File(typedPrefix).isAbsolute() || typedPrefix.startsWith("~" + File.separator)) {
            return file.getPath();
        }
        try {
            return base.toPath().toAbsolutePath().normalize().relativize(file.toPath().toAbsolutePath().normalize()).toString();
        } catch (IllegalArgumentException ignored) {
            return file.getPath();
        }
    }

    private record Request(String prefix, String pathPrefix) {
        static Request parse(String commandBuffer) {
            if (commandBuffer == null || !commandBuffer.startsWith(":")) {
                return null;
            }
            int separator = commandBuffer.indexOf(' ');
            if (separator < 0) {
                return null;
            }
            String command = commandBuffer.substring(1, separator).trim().toLowerCase(Locale.ROOT);
            if (!List.of("e", "edit", "w", "write", "tree").contains(command)) {
                return null;
            }
            return new Request(commandBuffer.substring(0, separator + 1), commandBuffer.substring(separator + 1));
        }
    }
}
