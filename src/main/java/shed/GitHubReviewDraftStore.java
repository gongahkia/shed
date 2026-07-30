package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    record Receipt(Target target, String action, String fingerprint, String acknowledgedAt) {
        Receipt {
            if (target == null || action == null || action.isBlank() || fingerprint == null || fingerprint.isBlank()) {
                throw new IllegalArgumentException("review receipt target, action, and fingerprint are required");
            }
            acknowledgedAt = acknowledgedAt == null ? "" : acknowledgedAt;
        }
    }

    private record Stored(List<Draft> drafts, List<Receipt> receipts) { }

    GitHubReviewDraftStore(Path file) {
        this.file = file == null ? null : file.toAbsolutePath().normalize();
    }

    synchronized Draft load(Target target) throws IOException {
        requireTarget(target);
        for (Draft draft : read().drafts()) if (draft.target().equals(target)) return draft;
        return null;
    }

    synchronized void save(Target target, String body) throws IOException {
        requireTarget(target);
        Draft draft = new Draft(target, body, Instant.now().toString());
        Stored stored = read();
        List<Draft> drafts = new ArrayList<>(stored.drafts());
        drafts.removeIf(existing -> existing.target().equals(target));
        drafts.add(draft);
        write(new Stored(drafts, stored.receipts()));
    }

    synchronized boolean discard(Target target) throws IOException {
        requireTarget(target);
        Stored stored = read();
        List<Draft> drafts = new ArrayList<>(stored.drafts());
        boolean removed = drafts.removeIf(existing -> existing.target().equals(target));
        if (!removed) return false;
        if (drafts.isEmpty() && stored.receipts().isEmpty()) Files.deleteIfExists(requireFile()); else write(new Stored(drafts, stored.receipts()));
        return true;
    }

    synchronized boolean acknowledged(Target target, String action, String body) throws IOException {
        requireTarget(target);
        String fingerprint = fingerprint(target, action, body);
        for (Receipt receipt : read().receipts()) if (receipt.fingerprint().equals(fingerprint)) return true;
        return false;
    }

    synchronized void acknowledge(Target target, String action, String body) throws IOException {
        requireTarget(target);
        String fingerprint = fingerprint(target, action, body);
        Stored stored = read();
        List<Draft> drafts = new ArrayList<>(stored.drafts());
        List<Receipt> receipts = new ArrayList<>(stored.receipts());
        drafts.removeIf(draft -> draft.target().equals(target));
        if (receipts.stream().noneMatch(receipt -> receipt.fingerprint().equals(fingerprint))) {
            receipts.add(new Receipt(target, action, fingerprint, Instant.now().toString()));
        }
        write(new Stored(drafts, receipts));
    }

    private Stored read() throws IOException {
        Path target = requireFile();
        if (!Files.exists(target)) return new Stored(new ArrayList<>(), new ArrayList<>());
        if (!Files.isRegularFile(target)) throw new IOException("local review-draft store is not a regular file");
        try {
            Map<String, Object> document = requireObject(MiniJson.parse(Files.readString(target, StandardCharsets.UTF_8)), "draft document");
            Integer version = MiniJson.asInt(document.get("version"));
            if (version == null || version != VERSION) throw new IOException("unsupported local review-draft version");
            List<Object> values = MiniJson.asArray(document.get("drafts"));
            if (values == null) throw new IOException("local review drafts must be an array");
            List<Draft> drafts = new ArrayList<>();
            for (Object value : values) drafts.add(decode(requireObject(value, "draft")));
            List<Object> acknowledged = MiniJson.asArray(document.get("acknowledgements"));
            if (acknowledged == null && document.containsKey("acknowledgements")) throw new IOException("local review acknowledgements must be an array");
            List<Receipt> receipts = new ArrayList<>();
            if (acknowledged != null) for (Object value : acknowledged) receipts.add(decodeReceipt(requireObject(value, "review acknowledgement")));
            return new Stored(drafts, receipts);
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid local review drafts: " + error.getMessage(), error);
        }
    }

    private void write(Stored stored) throws IOException {
        Path target = requireFile();
        Files.createDirectories(target.getParent());
        List<Object> values = new ArrayList<>();
        for (Draft draft : stored.drafts()) values.add(encode(draft));
        List<Object> acknowledgements = new ArrayList<>();
        for (Receipt receipt : stored.receipts()) acknowledgements.add(encode(receipt));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", VERSION);
        document.put("drafts", values);
        document.put("acknowledgements", acknowledgements);
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

    private static Map<String, Object> encode(Receipt receipt) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("repository", receipt.target().repository());
        value.put("pullRequest", receipt.target().pullRequest());
        value.put("action", receipt.action());
        value.put("fingerprint", receipt.fingerprint());
        value.put("acknowledgedAt", receipt.acknowledgedAt());
        return value;
    }

    private static Receipt decodeReceipt(Map<String, Object> value) {
        return new Receipt(new Target(requireString(value.get("repository"), "receipt repository"), requireString(value.get("pullRequest"), "receipt pull request")),
            requireString(value.get("action"), "receipt action"), requireString(value.get("fingerprint"), "receipt fingerprint"),
            requireString(value.get("acknowledgedAt"), "receipt acknowledgedAt"));
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

    private static String fingerprint(Target target, String action, String body) throws IOException {
        String value = (action == null ? "" : action.trim()) + '\u0000' + target.repository() + '\u0000' + target.pullRequest() + '\u0000'
            + (body == null ? "" : body);
        if (action == null || action.isBlank()) throw new IllegalArgumentException("review action is required");
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }
}
