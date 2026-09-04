package shed;

// File Type Enum
// Detects a simple user-facing file type label for status and syntax support

// Obligatory editing their own source code GIF for text editors :)

import java.io.File;

public enum FileType {
    RUST("rust"),
    PYTHON("python"),
    JAVASCRIPT("javascript"),
    TYPESCRIPT("typescript"),
    GO("go"),
    C("c"),
    CPP("cpp"),
    JAVA("java"),
    KOTLIN("kotlin"),
    CSHARP("csharp"),
    PHP("php"),
    RUBY("ruby"),
    SWIFT("swift"),
    HTML("html"),
    CSS("css"),
    JSON("json"),
    MARKDOWN("markdown"),
    YAML("yaml"),
    TOML("toml"),
    SQL("sql"),
    SHELL("shell"),
    TEXT("text"),
    UNKNOWN("unknown");

    private final String displayName;

    FileType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static FileType detect(File file, String content) {
        if (file != null) {
            String name = file.getName().toLowerCase();
            if (name.equals("rakefile") || name.equals("gemfile") || name.equals("guardfile") || name.equals("capfile")) {
                return RUBY;
            }
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String extension = name.substring(dot + 1);
                switch (extension) {
                    case "rs":
                        return RUST;
                    case "py":
                        return PYTHON;
                    case "js":
                    case "jsx":
                    case "mjs":
                    case "cjs":
                        return JAVASCRIPT;
                    case "ts":
                    case "tsx":
                    case "mts":
                    case "cts":
                        return TYPESCRIPT;
                    case "go":
                        return GO;
                    case "c":
                    case "h":
                        return C;
                    case "cc":
                    case "cpp":
                    case "cxx":
                    case "hpp":
                    case "hxx":
                        return CPP;
                    case "java":
                        return JAVA;
                    case "kt":
                    case "kts":
                        return KOTLIN;
                    case "cs":
                    case "csx":
                        return CSHARP;
                    case "php":
                    case "phtml":
                    case "php3":
                    case "php4":
                    case "php5":
                    case "phps":
                        return PHP;
                    case "rb":
                    case "rake":
                    case "gemspec":
                        return RUBY;
                    case "swift":
                        return SWIFT;
                    case "html":
                    case "htm":
                    case "xhtml":
                    case "xml":
                        return HTML;
                    case "css":
                    case "scss":
                    case "less":
                        return CSS;
                    case "json":
                    case "jsonc":
                        return JSON;
                    case "md":
                    case "markdown":
                        return MARKDOWN;
                    case "yml":
                    case "yaml":
                        return YAML;
                    case "toml":
                        return TOML;
                    case "sql":
                        return SQL;
                    case "sh":
                    case "bash":
                    case "zsh":
                    case "fish":
                        return SHELL;
                    case "txt":
                        return TEXT;
                    default:
                        break;
                }
            }
        }

        String firstLine = content == null ? "" : content.lines().findFirst().orElse("");
        if (firstLine.startsWith("#!")) {
            if (firstLine.contains("python")) {
                return PYTHON;
            }
            if (firstLine.contains("node")) {
                return JAVASCRIPT;
            }
            if (firstLine.contains("ruby")) {
                return RUBY;
            }
            if (firstLine.contains("php")) {
                return PHP;
            }
            if (firstLine.contains("sh") || firstLine.contains("bash") || firstLine.contains("zsh") || firstLine.contains("fish")) {
                return SHELL;
            }
        }

        return UNKNOWN;
    }
}
