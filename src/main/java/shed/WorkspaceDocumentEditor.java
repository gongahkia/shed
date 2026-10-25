package shed;

import java.util.List;

/** Explicit document identities and versions; adapters own UI affinity and batch atomicity. */
interface WorkspaceDocumentEditor extends WorkspaceDocumentSource {
    record TextChange(WorkspaceDocument expected, String text, WorkspaceDiskBinding diskBinding) {
        public TextChange { java.util.Objects.requireNonNull(expected); java.util.Objects.requireNonNull(text); }
        public TextChange(WorkspaceDocument expected, String text) { this(expected, text, null); }
    }

    default List<WorkspaceDocument> applyTexts(List<TextChange> changes) {
        if (changes.isEmpty()) return List.of();
        throw new WorkspaceApiException(WorkspaceApiException.Code.NOT_CONNECTED, "Live document mutation is unavailable");
    }
}
