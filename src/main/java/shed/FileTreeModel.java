package shed;

import java.io.File;
import java.util.function.Function;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;

/** Directory-backed model that loads a directory only when the user expands it. */
final class FileTreeModel extends DefaultTreeModel {
    private final Function<File, File[]> children;

    FileTreeModel(File root, Function<File, File[]> children) {
        super(new Node(root), true);
        this.children = children == null ? file -> new File[0] : children;
        loadChildren(rootNode());
    }

    Node rootNode() {
        return (Node) getRoot();
    }

    void loadChildren(Node node) {
        if (node == null || !node.file().isDirectory() || node.childrenLoaded) return;
        node.removeAllChildren();
        File[] listed = children.apply(node.file());
        if (listed != null) {
            for (File child : listed) {
                if (child != null) node.add(new Node(child));
            }
        }
        node.childrenLoaded = true;
        nodeStructureChanged(node);
    }

    static final class Node extends DefaultMutableTreeNode {
        private final File file;
        private boolean childrenLoaded;

        Node(File file) {
            super(file == null ? new File("") : file, file != null && file.isDirectory());
            this.file = file == null ? new File("") : file.getAbsoluteFile();
        }

        File file() {
            return file;
        }

        @Override public String toString() {
            String name = file.getName();
            return name == null || name.isBlank() ? file.getAbsolutePath() : name;
        }
    }
}
