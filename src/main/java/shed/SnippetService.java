package shed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SnippetService {

    public static class Snippet {
        public final String trigger;
        public final String body;
        public final String description;
        public final FileType fileType;

        public Snippet(String trigger, String body, String description, FileType fileType) {
            this.trigger = trigger;
            this.body = body;
            this.description = description;
            this.fileType = fileType;
        }
    }

    private final List<Snippet> builtins;
    private final List<Snippet> userSnippets;

    public SnippetService() {
        builtins = new ArrayList<>();
        userSnippets = new ArrayList<>();
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
        userSnippets.clear();
        // Config format: snippet.<filetype>.<trigger>=<body>
        // We parse through known config keys
    }

    public List<Snippet> getSnippetsFor(FileType fileType, String prefix) {
        List<Snippet> results = new ArrayList<>();
        for (Snippet s : userSnippets) {
            if ((s.fileType == fileType || s.fileType == null) && s.trigger.startsWith(prefix)) {
                results.add(s);
            }
        }
        for (Snippet s : builtins) {
            if ((s.fileType == fileType || s.fileType == null) && s.trigger.startsWith(prefix)) {
                results.add(s);
            }
        }
        return results;
    }

    public Snippet findExact(FileType fileType, String trigger) {
        for (Snippet s : userSnippets) {
            if ((s.fileType == fileType || s.fileType == null) && s.trigger.equals(trigger)) {
                return s;
            }
        }
        for (Snippet s : builtins) {
            if ((s.fileType == fileType || s.fileType == null) && s.trigger.equals(trigger)) {
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
        StringBuilder sb = new StringBuilder();
        sb.append("Snippets");
        if (fileType != null && fileType != FileType.UNKNOWN) {
            sb.append(" (").append(fileType.getDisplayName()).append(")");
        }
        sb.append("\n").append("=".repeat(40)).append("\n\n");

        List<Snippet> all = new ArrayList<>();
        for (Snippet s : userSnippets) {
            if (s.fileType == fileType || s.fileType == null || fileType == null) all.add(s);
        }
        for (Snippet s : builtins) {
            if (s.fileType == fileType || s.fileType == null || fileType == null) all.add(s);
        }

        if (all.isEmpty()) {
            sb.append("(no snippets)\n");
        } else {
            int maxTrigger = 0;
            for (Snippet s : all) maxTrigger = Math.max(maxTrigger, s.trigger.length());
            for (Snippet s : all) {
                sb.append(String.format("%-" + (maxTrigger + 2) + "s %s", s.trigger, s.description));
                if (s.fileType != null) sb.append(" [").append(s.fileType.getDisplayName()).append("]");
                sb.append("\n");
            }
        }
        sb.append("\nType trigger text then Ctrl-j to expand.");
        return sb.toString();
    }
}
