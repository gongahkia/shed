package shed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

public class ArchitectureBoundaryTest {
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "shed");
    @Test
    void servicesDoNotDependOnSwingCompositionOrControllers() throws Exception {
        List<String> controllers = controllerNames();
        for (Path source : sourceFiles("*Service.java")) {
            String code = codeOnly(Files.readString(source));
            assertNoTypeReference(source, code, "Texteditor");
            for (String controller : controllers) {
                assertNoTypeReference(source, code, controller);
            }
        }
    }

    @Test
    void controllersDoNotDependOnPeersOrBecomePublic() throws Exception {
        List<String> controllers = controllerNames();
        for (Path source : sourceFiles("*Controller.java")) {
            String controller = source.getFileName().toString().replace(".java", "");
            String code = codeOnly(Files.readString(source));
            for (String peer : controllers) {
                if (!peer.equals(controller)) {
                    assertNoTypeReference(source, code, peer);
                }
            }
            Class<?> type = Class.forName("shed." + controller);
            assertFalse(Modifier.isPublic(type.getModifiers()), controller + " must remain package-private");
        }
    }

    private static List<Path> sourceFiles(String glob) throws IOException {
        assertTrue(Files.isDirectory(SOURCE_ROOT), "missing source root: " + SOURCE_ROOT);
        try (Stream<Path> files = Files.list(SOURCE_ROOT)) {
            return files.filter(path -> path.getFileName().toString().matches(globToRegex(glob))).toList();
        }
    }

    private static List<String> controllerNames() throws IOException {
        return sourceFiles("*Controller.java").stream()
            .map(path -> path.getFileName().toString().replace(".java", ""))
            .toList();
    }

    private static void assertNoTypeReference(Path source, String code, String typeName) {
        assertFalse(containsIdentifier(code, typeName), source + " must not depend on " + typeName);
    }

    private static String codeOnly(String source) {
        StringBuilder code = new StringBuilder(source.length());
        boolean lineComment = false;
        boolean blockComment = false;
        char quoted = '\0';
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n') {
                    lineComment = false;
                    code.append(current);
                } else {
                    code.append(' ');
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    code.append("  ");
                    index++;
                } else {
                    code.append(current == '\n' ? '\n' : ' ');
                }
                continue;
            }
            if (quoted != '\0') {
                code.append(' ');
                if (!escaped && current == quoted) {
                    quoted = '\0';
                }
                escaped = !escaped && current == '\\';
                continue;
            }
            if (current == '/' && next == '/') {
                lineComment = true;
                code.append("  ");
                index++;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                code.append("  ");
                index++;
            } else if (current == '\"' || current == '\'') {
                quoted = current;
                escaped = false;
                code.append(' ');
            } else {
                code.append(current);
            }
        }
        return code.toString();
    }

    private static boolean containsIdentifier(String source, String identifier) {
        int start = source.indexOf(identifier);
        while (start >= 0) {
            int end = start + identifier.length();
            boolean startsIdentifier = start > 0 && Character.isJavaIdentifierPart(source.charAt(start - 1));
            boolean endsIdentifier = end < source.length() && Character.isJavaIdentifierPart(source.charAt(end));
            if (!startsIdentifier && !endsIdentifier) {
                return true;
            }
            start = source.indexOf(identifier, end);
        }
        return false;
    }

    private static String globToRegex(String glob) {
        return glob.replace(".", "\\.").replace("*", ".*");
    }
}
