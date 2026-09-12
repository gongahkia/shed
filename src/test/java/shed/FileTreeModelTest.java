package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTreeModelTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsOnlyExpandedDirectoriesAndKeepsFilesAsLeaves() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace"));
        Path source = Files.createDirectory(root.resolve("src"));
        Files.writeString(source.resolve("Main.java"), "class Main {}\n");
        Files.writeString(root.resolve("README.md"), "# workspace\n");

        FileTreeModel model = new FileTreeModel(root.toFile(), this::children);
        FileTreeModel.Node rootNode = model.rootNode();
        FileTreeModel.Node sourceNode = (FileTreeModel.Node) rootNode.getChildAt(0);
        FileTreeModel.Node readmeNode = (FileTreeModel.Node) rootNode.getChildAt(1);

        assertEquals("src", sourceNode.toString());
        assertEquals(0, sourceNode.getChildCount());
        assertFalse(model.isLeaf(sourceNode));
        assertTrue(model.isLeaf(readmeNode));

        model.loadChildren(sourceNode);

        assertEquals(1, sourceNode.getChildCount());
        assertEquals("Main.java", sourceNode.getChildAt(0).toString());
    }

    private File[] children(File directory) {
        File[] children = directory.listFiles();
        if (children == null) return new File[0];
        Arrays.sort(children, (left, right) -> {
            if (left.isDirectory() != right.isDirectory()) return left.isDirectory() ? -1 : 1;
            return left.getName().compareToIgnoreCase(right.getName());
        });
        return children;
    }
}
