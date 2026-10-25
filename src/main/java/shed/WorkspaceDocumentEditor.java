package shed;

import java.util.List;

/** Explicit document identities and versions; adapters own UI affinity and batch atomicity. */
interface WorkspaceDocumentEditor extends WorkspaceDocumentSource {
    record TextChange(WorkspaceDocument expected, String text, WorkspaceDiskBinding diskBinding, boolean detach) {
        public TextChange {
            java.util.Objects.requireNonNull(expected); java.util.Objects.requireNonNull(text);
            if (detach && diskBinding != null) throw new IllegalArgumentException("Cannot bind and detach a document together");
        }
        public TextChange(WorkspaceDocument expected, String text, WorkspaceDiskBinding binding) { this(expected, text, binding, false); }
        public TextChange(WorkspaceDocument expected, String text) { this(expected, text, null); }
        static TextChange detach(WorkspaceDocument expected) { return new TextChange(expected, expected.content(), null, true); }
    }

    default List<WorkspaceDocument> applyTexts(List<TextChange> changes) {
        if (changes.isEmpty()) return List.of();
        throw new WorkspaceApiException(WorkspaceApiException.Code.NOT_CONNECTED, "Live document mutation is unavailable");
    }
}
