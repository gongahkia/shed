package shed;

import java.util.List;

/** Adapter captures one immutable editor view; implementations own their thread-affinity rules. */
@FunctionalInterface
public interface WorkspaceDocumentSource {
    List<WorkspaceDocument> snapshots();
    default boolean connected() { return true; }

    static WorkspaceDocumentSource diskOnly() {
        return new WorkspaceDocumentSource() {
            public List<WorkspaceDocument> snapshots() { return List.of(); }
            public boolean connected() { return false; }
        };
    }
}
