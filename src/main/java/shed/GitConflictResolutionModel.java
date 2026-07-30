package shed;

import java.util.List;

final class GitConflictResolutionModel {
    record Side(boolean present, String content) {
        Side {
            content = content == null ? "" : content;
        }

        String display() {
            return present ? content : "(Git has no content at this conflict stage.)\n";
        }
    }

    record Conflict(String path, String sourceDigest, String sourceContent, Side base, Side ours, Side theirs) {
        Conflict {
            if (path == null || path.isBlank()) throw new IllegalArgumentException("conflict path is required");
            sourceDigest = sourceDigest == null ? "" : sourceDigest;
            sourceContent = sourceContent == null ? "" : sourceContent;
            base = base == null ? new Side(false, "") : base;
            ours = ours == null ? new Side(false, "") : ours;
            theirs = theirs == null ? new Side(false, "") : theirs;
        }

        @Override
        public String toString() {
            return path;
        }
    }

    private GitConflictResolutionModel() { }

    static String validateResult(String result) {
        if (result == null) return "Resolution content is required.";
        for (String marker : List.of("<<<<<<<", "=======", ">>>>>>>")) {
            if (result.contains(marker)) return "Resolution still contains Git conflict marker: " + marker;
        }
        return null;
    }
}
