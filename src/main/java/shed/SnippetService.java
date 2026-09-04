package shed;

import shed.api.SnippetContribution;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

public class SnippetService {

    record LoadResult(int loaded, List<String> errors) {}

    public static class Snippet {
        public final String trigger;
        public final String body;
        public final String description;
        public final FileType fileType;
        public final String languageId;

        public Snippet(String trigger, String body, String description, FileType fileType) {
            this(trigger, body, description, fileType, "");
        }

        public Snippet(String trigger, String body, String description, FileType fileType, String languageId) {
            this.trigger = trigger;
            this.body = body;
            this.description = description;
            this.fileType = fileType;
            this.languageId = languageId == null ? "" : languageId.trim();
        }
    }

    private final List<Snippet> builtins;
    private final List<Snippet> userSnippets;
    private Path loadedDirectory;
    private String directoryFingerprint;

    public SnippetService() {
        builtins = new ArrayList<>();
        userSnippets = new ArrayList<>();
        loadedDirectory = null;
        directoryFingerprint = "";
        registerBuiltins();
    }

    private void registerBuiltins() {
        // Java snippets
        add("main", "public static void main(String[] args) {\n    $0\n}", "main method", FileType.JAVA);
        add("sout", "System.out.println($0);", "println", FileType.JAVA);
        add("serr", "System.err.println($0);", "stderr println", FileType.JAVA);
        add("fori", "for (int i = 0; i < $1; i++) {\n    $0\n}", "for loop", FileType.JAVA);
        add("fore", "for ($1 item : $2) {\n    $0\n}", "for-each loop", FileType.JAVA);
        add("if", "if ($1) {\n    $0\n}", "if block", FileType.JAVA);
        add("ife", "if ($1) {\n    $0\n} else {\n    \n}", "if-else block", FileType.JAVA);
        add("try", "try {\n    $0\n} catch ($1 e) {\n    \n}", "try-catch", FileType.JAVA);
        add("class", "public class $1 {\n    $0\n}", "class declaration", FileType.JAVA);

        // Python snippets
        add("def", "def $1($2):\n    $0", "function", FileType.PYTHON);
        add("class", "class $1:\n    def __init__(self$2):\n        $0", "class", FileType.PYTHON);
        add("if", "if $1:\n    $0", "if block", FileType.PYTHON);
        add("ife", "if $1:\n    $0\nelse:\n    ", "if-else", FileType.PYTHON);
        add("for", "for $1 in $2:\n    $0", "for loop", FileType.PYTHON);
        add("with", "with $1 as $2:\n    $0", "with statement", FileType.PYTHON);
        add("try", "try:\n    $0\nexcept $1:\n    pass", "try-except", FileType.PYTHON);
        add("main", "if __name__ == \"__main__\":\n    $0", "main guard", FileType.PYTHON);

        // JavaScript/TypeScript snippets
        add("fn", "function $1($2) {\n    $0\n}", "function", FileType.JAVASCRIPT);
        add("afn", "($1) => {\n    $0\n}", "arrow function", FileType.JAVASCRIPT);
        add("cl", "console.log($0);", "console.log", FileType.JAVASCRIPT);
        add("if", "if ($1) {\n    $0\n}", "if block", FileType.JAVASCRIPT);
        add("for", "for (let i = 0; i < $1; i++) {\n    $0\n}", "for loop", FileType.JAVASCRIPT);
        add("fore", "for (const $1 of $2) {\n    $0\n}", "for-of loop", FileType.JAVASCRIPT);
        add("try", "try {\n    $0\n} catch (err) {\n    \n}", "try-catch", FileType.JAVASCRIPT);
        add("class", "class $1 {\n    constructor($2) {\n        $0\n    }\n}", "class", FileType.JAVASCRIPT);
        // TS-specific
        add("fn", "function $1($2): $3 {\n    $0\n}", "typed function", FileType.TYPESCRIPT);
        add("afn", "($1): $2 => {\n    $0\n}", "typed arrow function", FileType.TYPESCRIPT);
        add("int", "interface $1 {\n    $0\n}", "interface", FileType.TYPESCRIPT);
        add("type", "type $1 = {\n    $0\n};", "type alias", FileType.TYPESCRIPT);

        // Rust snippets
        add("fn", "fn $1($2) -> $3 {\n    $0\n}", "function", FileType.RUST);
        add("pfn", "pub fn $1($2) -> $3 {\n    $0\n}", "pub function", FileType.RUST);
        add("struct", "struct $1 {\n    $0\n}", "struct", FileType.RUST);
        add("impl", "impl $1 {\n    $0\n}", "impl block", FileType.RUST);
        add("match", "match $1 {\n    $0\n}", "match", FileType.RUST);
        add("if", "if $1 {\n    $0\n}", "if block", FileType.RUST);
        add("test", "#[test]\nfn $1() {\n    $0\n}", "test function", FileType.RUST);
        add("main", "fn main() {\n    $0\n}", "main function", FileType.RUST);

        // Go snippets
        add("fn", "func $1($2) $3 {\n    $0\n}", "function", FileType.GO);
        add("main", "func main() {\n    $0\n}", "main function", FileType.GO);
        add("if", "if $1 {\n    $0\n}", "if block", FileType.GO);
        add("ife", "if err != nil {\n    $0\n}", "error check", FileType.GO);
        add("for", "for $1 := range $2 {\n    $0\n}", "for range", FileType.GO);
        add("struct", "type $1 struct {\n    $0\n}", "struct", FileType.GO);
        add("test", "func Test$1(t *testing.T) {\n    $0\n}", "test function", FileType.GO);

        // C/C++ snippets
        add("main", "int main(int argc, char *argv[]) {\n    $0\n    return 0;\n}", "main function", FileType.C);
        add("if", "if ($1) {\n    $0\n}", "if block", FileType.C);
        add("for", "for (int i = 0; i < $1; i++) {\n    $0\n}", "for loop", FileType.C);
        add("struct", "typedef struct {\n    $0\n} $1;", "struct", FileType.C);
        add("inc", "#include <$0>", "include", FileType.C);
        add("main", "int main(int argc, char *argv[]) {\n    $0\n    return 0;\n}", "main function", FileType.CPP);
        add("class", "class $1 {\npublic:\n    $1();\n    ~$1();\nprivate:\n    $0\n};", "class", FileType.CPP);

        // Markdown snippets
        add("link", "[$1]($2)", "link", FileType.MARKDOWN);
        add("img", "![$1]($2)", "image", FileType.MARKDOWN);
        add("table", "| $1 | $2 |\n| --- | --- |\n| $0 |  |", "table", FileType.MARKDOWN);
        add("code", "```$1\n$0\n```", "code block", FileType.MARKDOWN);
        add("task", "- [ ] $0", "task item", FileType.MARKDOWN);
        add("details", "<details>\n<summary>$1</summary>\n\n$0\n\n</details>", "details/summary", FileType.MARKDOWN);

        // HTML snippets
        add("html", "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n    <meta charset=\"UTF-8\">\n    <title>$1</title>\n</head>\n<body>\n    $0\n</body>\n</html>", "html boilerplate", FileType.HTML);
        add("div", "<div class=\"$1\">\n    $0\n</div>", "div", FileType.HTML);
        add("a", "<a href=\"$1\">$0</a>", "anchor", FileType.HTML);
        add("img", "<img src=\"$1\" alt=\"$0\" />", "image", FileType.HTML);
    }

    private void add(String trigger, String body, String description, FileType fileType) {
        builtins.add(new Snippet(trigger, body, description, fileType));
    }

    public void addUserSnippet(String trigger, String body, String description, FileType fileType) {
        userSnippets.add(new Snippet(trigger, body, description, fileType));
    }

    public void loadFromConfig(ConfigManager config) {
        if (config == null) return;
        Path directory = Path.of(config.getSnippetsDirectory()).toAbsolutePath().normalize();
        if (directory.equals(loadedDirectory)) reloadIfChanged();
        else loadFromDirectory(directory);
    }

    public LoadResult loadFromDirectory(Path directory) {
        userSnippets.clear();
        loadedDirectory = directory == null ? null : directory.toAbsolutePath().normalize();
        directoryFingerprint = fingerprint(loadedDirectory);
        if (loadedDirectory == null || !Files.isDirectory(loadedDirectory)) return new LoadResult(0, List.of());
        List<String> errors = new ArrayList<>();
        int loaded = 0;
        try (Stream<Path> entries = Files.list(loadedDirectory)) {
            List<Path> files = entries.filter(Files::isRegularFile)
                .filter(this::isSnippetFile).sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
            for (Path file : files) loaded += loadFile(file, errors);
        } catch (IOException error) {
            errors.add("Cannot read " + loadedDirectory + ": " + error.getMessage());
        }
        return new LoadResult(loaded, List.copyOf(errors));
    }

    public boolean reloadIfChanged() {
        String current = fingerprint(loadedDirectory);
        if (current.equals(directoryFingerprint)) return false;
        loadFromDirectory(loadedDirectory);
        return true;
    }

    public Path getLoadedDirectory() {
        return loadedDirectory;
    }

    private boolean isSnippetFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".json") || name.endsWith(".code-snippets");
    }

    private int loadFile(Path file, List<String> errors) {
        try {
            Map<String, Object> snippets = MiniJson.asObject(MiniJson.parse(Files.readString(file, StandardCharsets.UTF_8)));
            if (snippets == null) {
                errors.add("Expected object: " + file.getFileName());
                return 0;
            }
            int loaded = 0;
            FileType fileType = fileTypeForFilename(file);
            for (Map.Entry<String, Object> entry : snippets.entrySet()) {
                Map<String, Object> definition = MiniJson.asObject(entry.getValue());
                if (definition == null) {
                    errors.add("Invalid snippet " + entry.getKey() + " in " + file.getFileName());
                    continue;
                }
                String body = body(definition.get("body"));
                if (body == null) {
                    errors.add("Missing body for " + entry.getKey() + " in " + file.getFileName());
                    continue;
                }
                String description = MiniJson.asString(definition.get("description"));
                List<String> prefixes = prefixes(definition.get("prefix"), entry.getKey());
                List<FileType> scopes = scopes(definition.get("scope"), fileType);
                for (String prefix : prefixes) {
                    for (FileType scope : scopes) {
                        if (prefix.isBlank()) continue;
                        userSnippets.add(new Snippet(prefix, body, description == null ? entry.getKey() : description, scope));
                        loaded++;
                    }
                }
            }
            return loaded;
        } catch (RuntimeException | IOException error) {
            errors.add("Cannot load " + file.getFileName() + ": " + error.getMessage());
            return 0;
        }
    }

    private String body(Object value) {
        String direct = MiniJson.asString(value);
        if (direct != null) return direct;
        List<Object> lines = MiniJson.asArray(value);
        if (lines == null) return null;
        List<String> values = new ArrayList<>();
        for (Object line : lines) {
            String text = MiniJson.asString(line);
            if (text == null) return null;
            values.add(text);
        }
        return String.join("\n", values);
    }

    private List<String> prefixes(Object value, String fallback) {
        String direct = MiniJson.asString(value);
        if (direct != null) return List.of(direct);
        List<Object> values = MiniJson.asArray(value);
        if (values == null) return List.of(fallback);
        List<String> prefixes = new ArrayList<>();
        for (Object item : values) {
            String prefix = MiniJson.asString(item);
            if (prefix != null) prefixes.add(prefix);
        }
        return prefixes.isEmpty() ? List.of(fallback) : List.copyOf(prefixes);
    }

    private List<FileType> scopes(Object value, FileType fallback) {
        String scope = MiniJson.asString(value);
        if (scope == null || scope.isBlank()) return singletonScope(fallback);
        List<FileType> types = new ArrayList<>();
        for (String name : scope.split(",")) {
            FileType type = fileTypeForName(name.trim());
            if (type != null) types.add(type);
        }
        return types.isEmpty() ? singletonScope(fallback) : List.copyOf(types);
    }

    private List<FileType> singletonScope(FileType type) {
        List<FileType> scopes = new ArrayList<>();
        scopes.add(type);
        return scopes;
    }

    private FileType fileTypeForFilename(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.equals("snippets.json") || name.endsWith(".code-snippets")) return null;
        int dot = name.lastIndexOf('.');
        return fileTypeForName(dot < 0 ? name : name.substring(0, dot));
    }

    private FileType fileTypeForName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ').trim();
        for (FileType type : FileType.values()) {
            if (type.getDisplayName().equals(normalized)) return type;
        }
        return switch (normalized) {
            case "js", "jsx" -> FileType.JAVASCRIPT;
            case "ts", "tsx" -> FileType.TYPESCRIPT;
            case "c++", "cxx" -> FileType.CPP;
            case "md" -> FileType.MARKDOWN;
            case "plain text", "global" -> null;
            default -> null;
        };
    }

    private String fingerprint(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) return "";
        try (Stream<Path> entries = Files.list(directory)) {
            StringBuilder fingerprint = new StringBuilder();
            for (Path file : entries.filter(Files::isRegularFile).filter(this::isSnippetFile)
                .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                FileTime modified = Files.getLastModifiedTime(file);
                fingerprint.append(file.getFileName()).append(':').append(Files.size(file)).append(':').append(modified.toMillis()).append(';');
            }
            return fingerprint.toString();
        } catch (IOException error) {
            return "!" + error.getClass().getName() + ':' + error.getMessage();
        }
    }

    public List<Snippet> getSnippetsFor(FileType fileType, String prefix) {
        return getSnippetsFor(fileType, "", prefix, List.of());
    }

    public List<Snippet> getSnippetsFor(FileType fileType, String languageId, String prefix, List<SnippetContribution> contributed) {
        reloadIfChanged();
        List<Snippet> results = new ArrayList<>();
        for (Snippet s : userSnippets) {
            if (matches(s, fileType, languageId) && s.trigger.startsWith(prefix)) {
                results.add(s);
            }
        }
        for (SnippetContribution contribution : contributed == null ? List.<SnippetContribution>of() : contributed) {
            if (contribution != null && contribution.languageId().equalsIgnoreCase(languageId == null ? "" : languageId)
                && contribution.trigger().startsWith(prefix == null ? "" : prefix)) {
                results.add(new Snippet(contribution.trigger(), contribution.body(), contribution.description(), null, contribution.languageId()));
            }
        }
        for (Snippet s : builtins) {
            if (matches(s, fileType, languageId) && s.trigger.startsWith(prefix)) {
                results.add(s);
            }
        }
        return results;
    }

    public Snippet findExact(FileType fileType, String trigger) {
        return findExact(fileType, "", trigger, List.of());
    }

    public Snippet findExact(FileType fileType, String languageId, String trigger, List<SnippetContribution> contributed) {
        reloadIfChanged();
        for (Snippet s : userSnippets) {
            if (matches(s, fileType, languageId) && s.trigger.equals(trigger)) {
                return s;
            }
        }
        for (SnippetContribution contribution : contributed == null ? List.<SnippetContribution>of() : contributed) {
            if (contribution != null && contribution.languageId().equalsIgnoreCase(languageId == null ? "" : languageId)
                && contribution.trigger().equals(trigger)) {
                return new Snippet(contribution.trigger(), contribution.body(), contribution.description(), null, contribution.languageId());
            }
        }
        for (Snippet s : builtins) {
            if (matches(s, fileType, languageId) && s.trigger.equals(trigger)) {
                return s;
            }
        }
        return null;
    }

    public String expand(Snippet snippet) {
        if (snippet == null) return null;
        // Strip tabstop markers ($0, $1, $2...) for simple insertion
        // $0 marks final cursor position
        return snippet.body.replaceAll("\\$\\d+", "");
    }

    public int cursorOffset(Snippet snippet) {
        if (snippet == null) return -1;
        String body = snippet.body;
        int idx = body.indexOf("$0");
        if (idx >= 0) {
            // Count how many $N markers precede $0
            String before = body.substring(0, idx);
            String cleaned = before.replaceAll("\\$\\d+", "");
            return cleaned.length();
        }
        return -1;
    }

    public String listSnippets(FileType fileType) {
        return listSnippets(fileType, "", List.of());
    }

    public String listSnippets(FileType fileType, String languageId, List<SnippetContribution> contributed) {
        reloadIfChanged();
        StringBuilder sb = new StringBuilder();
        sb.append("Snippets");
        if (fileType != null && fileType != FileType.UNKNOWN) {
            sb.append(" (").append(fileType.getDisplayName()).append(")");
        }
        sb.append("\n").append("=".repeat(40)).append("\n\n");

        List<Snippet> all = new ArrayList<>();
        for (Snippet s : userSnippets) {
            if (fileType == null || matches(s, fileType, languageId)) all.add(s);
        }
        for (SnippetContribution contribution : contributed == null ? List.<SnippetContribution>of() : contributed) {
            if (contribution != null && contribution.languageId().equalsIgnoreCase(languageId == null ? "" : languageId)) {
                all.add(new Snippet(contribution.trigger(), contribution.body(), contribution.description(), null, contribution.languageId()));
            }
        }
        for (Snippet s : builtins) {
            if (fileType == null || matches(s, fileType, languageId)) all.add(s);
        }

        if (all.isEmpty()) {
            sb.append("(no snippets)\n");
        } else {
            int maxTrigger = 0;
            for (Snippet s : all) maxTrigger = Math.max(maxTrigger, s.trigger.length());
            for (Snippet s : all) {
                sb.append(String.format("%-" + (maxTrigger + 2) + "s %s", s.trigger, s.description));
                if (s.fileType != null) sb.append(" [").append(s.fileType.getDisplayName()).append("]");
                else if (!s.languageId.isBlank()) sb.append(" [").append(s.languageId).append("]");
                sb.append("\n");
            }
        }
        sb.append("\nType a trigger then Ctrl-j, or use Ctrl-n for completion.");
        return sb.toString();
    }

    private boolean matches(Snippet snippet, FileType fileType, String languageId) {
        if (snippet == null) return false;
        if (!snippet.languageId.isBlank()) return snippet.languageId.equalsIgnoreCase(languageId == null ? "" : languageId);
        return snippet.fileType == fileType || snippet.fileType == null;
    }
}
