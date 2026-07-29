package shed;

import java.util.Objects;

final class CompletionRequestState {
    record Snapshot(long generation, String uri, int documentVersion, int caretOffset, String prefix) {
    }

    private long generation;

    Snapshot begin(String uri, int documentVersion, int caretOffset, String prefix) {
        return new Snapshot(++generation, uri, documentVersion, caretOffset, prefix == null ? "" : prefix);
    }

    void invalidate() {
        generation++;
    }

    boolean matches(Snapshot snapshot, String uri, int documentVersion, int caretOffset, String prefix) {
        return snapshot != null
            && snapshot.generation() == generation
            && snapshot.documentVersion() == documentVersion
            && snapshot.caretOffset() == caretOffset
            && Objects.equals(snapshot.uri(), uri)
            && Objects.equals(snapshot.prefix(), prefix == null ? "" : prefix);
    }
}
