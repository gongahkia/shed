package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GitHubReviewDraftStore {
    static final String FILE_NAME = "github-review-drafts-v1.json";
    private static final int VERSION = 1;
    private final Path file;

    record Target(String repository, String pullRequest) {
        Target {
            repository = repository == null ? "" : repository.trim();
            pullRequest = pullRequest == null ? "" : pullRequest.trim();
            if (repository.isEmpty() || !pullRequest.matches("[0-9]+")) throw new IllegalArgumentException("repository and pull-request number are required");
        }
    }

    record Draft(Target target, String body, String savedAt) {
        Draft {
            if (target == null || body == null || body.isBlank()) throw new IllegalArgumentException("draft target and body are required");
            savedAt = savedAt == null ? "" : savedAt;
        }
    }

    GitHubReviewDraftStore(Path file) {
        this.file = file == null ? null : file.toAbsolutePath().normalize();
    }

    synchronized Draft load(Target target) throws IOException {
        requireTarget(target);
        for (Draft draft : readAll()) if (draft.target().equals(target)) return draft;
        return null;
    }

    synchronized void save(Target target, String body) throws IOException {
        requireTarget(target);
        Draft draft = new Draft(target, body, Instant.now().toString());
        List<Draft> drafts = readAll();
        drafts.removeIf(existing -> existing.target().equals(target));
        drafts.add(draft);
        writeAll(drafts);
    }

    synchronized boolean discard(Target target) throws IOException {
        requireTarget(target);
        List<Draft> drafts = readAll();
        boolean removed = drafts.removeIf(existing -> existing.target().equals(target));
        if (!removed) return false;
        if (drafts.isEmpty()) Files.deleteIfExists(requireFile()); else writeAll(drafts);
        return true;
    }

    private List<Draft> readAll() throws IOException {
        Path target = requireFile();
        if (!Files.exists(target)) return new ArrayList<>();
        if (!Files.isRegularFile(target)) throw new IOException("local review-draft store is not a regular file");
        try {
            Map<String, Object> document = requireObject(MiniJson.parse(Files.readString(target, StandardCharsets.UTF_8)), "draft document");
            Integer version = MiniJson.asInt(document.get("version"));
            if (version == null || version != VERSION) throw new IOException("unsupported local review-draft version");
            List<Object> values = MiniJson.asArray(document.get("drafts"));
            if (values == null) throw new IOException("local review drafts must be an array");
            List<Draft> drafts = new ArrayList<>();
            for (Object value : values) drafts.add(decode(requireObject(value, "draft")));
            return drafts;
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid local review drafts: " + error.getMessage(), error);
        }
    }

    private void writeAll(List<Draft> drafts) throws IOException {
        Path target = requireFile();
        Files.createDirectories(target.getParent());
        List<Object> values = new ArrayList<>();
        for (Draft draft : drafts) values.add(encode(draft));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", VERSION);
        document.put("drafts", values);
        AtomicFileWriter.write(target, MiniJson.stringify(document).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> encode(Draft draft) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("repository", draft.target().repository());
        value.put("pullRequest", draft.target().pullRequest());
        value.put("body", draft.body());
        value.put("savedAt", draft.savedAt());
        return value;
    }

    private static Draft decode(Map<String, Object> value) {
        return new Draft(new Target(requireString(value.get("repository"), "draft repository"), requireString(value.get("pullRequest"), "draft pull request")),
            requireString(value.get("body"), "draft body"), requireString(value.get("savedAt"), "draft savedAt"));
    }

    private Path requireFile() throws IOException {
        if (file == null || file.getParent() == null) throw new IOException("local review-draft path is unavailable");
        return file;
    }

    private static void requireTarget(Target target) {
        if (target == null) throw new IllegalArgumentException("draft target is required");
    }

    private static Map<String, Object> requireObject(Object value, String field) {
        Map<String, Object> object = MiniJson.asObject(value);
        if (object == null) throw new IllegalArgumentException(field + " must be an object");
        return object;
    }

    private static String requireString(Object value, String field) {
        String text = MiniJson.asString(value);
        if (text == null) throw new IllegalArgumentException(field + " must be a string");
        return text;
    }
}
