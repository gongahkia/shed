package shed;

import java.util.ArrayList;
import java.util.List;

final class GitHubPullRequestDetailModel {
    record Detail(String state, String author, String updatedAt, String additions, String deletions, String changedFiles, String body,
        List<String> files, String patch, String error) {
        Detail {
            state = state == null ? "" : state;
            author = author == null ? "" : author;
            updatedAt = updatedAt == null ? "" : updatedAt;
            additions = additions == null ? "" : additions;
            deletions = deletions == null ? "" : deletions;
            changedFiles = changedFiles == null ? "" : changedFiles;
            body = body == null ? "" : body;
            files = files == null ? List.of() : List.copyOf(files);
            patch = patch == null ? "" : patch;
            error = error == null ? "" : error;
        }

        boolean available() { return error.isEmpty(); }
    }

    private GitHubPullRequestDetailModel() { }

    static Detail fromResults(CommandResult metadata, CommandResult files, CommandResult patch) {
        String failure = failure(metadata, "gh pr view");
        if (failure != null) return new Detail("", "", "", "", "", "", "", List.of(), "", failure);
        failure = failure(files, "gh pr diff --name-only");
        if (failure != null) return new Detail("", "", "", "", "", "", "", List.of(), "", failure);
        failure = failure(patch, "gh pr diff");
        if (failure != null) return new Detail("", "", "", "", "", "", "", List.of(), "", failure);
        List<String> fields = nulSeparated(metadata.stdout);
        if (fields.size() != 7) return new Detail("", "", "", "", "", "", "", List.of(), "", "Pull-request metadata was malformed.");
        return new Detail(fields.get(0), fields.get(1), fields.get(2), fields.get(3), fields.get(4), fields.get(5), fields.get(6),
            lines(files.stdout), patch.stdout, "");
    }

    private static String failure(CommandResult result, String command) {
        if (result == null) return command + " returned no result.";
        if (result.exitCode != 0) return result.stderr.isBlank() ? (result.stdout.isBlank() ? command + " failed." : result.stdout.strip()) : result.stderr.strip();
        return result.stdout.endsWith("\n[shed: output truncated]") ? command + " output was truncated; increase process.output.max.bytes." : null;
    }

    private static List<String> nulSeparated(String value) {
        List<String> fields = new ArrayList<>(); String source = value == null ? "" : value; int start = 0;
        for (int index = 0; index < source.length(); index++) if (source.charAt(index) == '\u0000') { fields.add(source.substring(start, index)); start = index + 1; }
        if (start != source.length()) fields.add(source.substring(start));
        return fields;
    }

    private static List<String> lines(String value) {
        List<String> files = new ArrayList<>();
        for (String line : (value == null ? "" : value).split("\\R")) if (!line.isBlank()) files.add(line);
        return files;
    }
}
