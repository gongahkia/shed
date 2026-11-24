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

    java.util.List<File> loadedDirectories(){
        var result=new java.util.ArrayList<File>();var nodes=rootNode().breadthFirstEnumeration();
        while(nodes.hasMoreElements()&&result.size()<128){var node=(Node)nodes.nextElement();if(node.childrenLoaded)result.add(node.file());}return java.util.List.copyOf(result);
    }
    void refreshListings(java.util.Map<File,File[]> listings){refreshListing(rootNode(),listings);}
    private void refreshListing(Node node,java.util.Map<File,File[]> listings){
        File[] listed=listings.get(node.file());if(listed==null)return;
        var previous=new java.util.LinkedHashMap<File,Node>();for(int i=0;i<node.getChildCount();i++){var child=(Node)node.getChildAt(i);previous.put(child.file(),child);}
        boolean changed=listed.length!=node.getChildCount();
        if(!changed)for(int i=0;i<listed.length;i++)if(!listed[i].getAbsoluteFile().equals(((Node)node.getChildAt(i)).file())){changed=true;break;}
        if(changed){node.removeAllChildren();for(File file:listed){Node old=previous.get(file.getAbsoluteFile());node.add(old!=null&&old.getAllowsChildren()==file.isDirectory()?old:new Node(file));}nodeStructureChanged(node);}
        for(int i=0;i<node.getChildCount();i++)refreshListing((Node)node.getChildAt(i),listings);
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
