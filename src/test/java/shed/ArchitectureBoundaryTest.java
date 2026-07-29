package shed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
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
        Pattern type = Pattern.compile("\\b" + Pattern.quote(typeName) + "\\b");
        assertFalse(type.matcher(code).find(), source + " must not depend on " + typeName);
    }

    private static String codeOnly(String source) {
        String withoutBlockComments = source.replaceAll("(?s)/\\*.*?\\*/", " ");
        String withoutLineComments = withoutBlockComments.replaceAll("(?m)//.*$", " ");
        return withoutLineComments.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'", " ");
    }

    private static String globToRegex(String glob) {
        return glob.replace(".", "\\.").replace("*", ".*");
    }
}
