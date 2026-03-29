// File Type Enum
// Detects a simple user-facing file type label for status and syntax support

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
    HTML("html"),
    CSS("css"),
    JSON("json"),
    MARKDOWN("markdown"),
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
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String extension = name.substring(dot + 1);
                switch (extension) {
                    case "rs":
                        return RUST;
                    case "py":
                        return PYTHON;
                    case "js":
                        return JAVASCRIPT;
                    case "ts":
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
                    case "html":
                    case "htm":
                        return HTML;
                    case "css":
                        return CSS;
                    case "json":
                        return JSON;
                    case "md":
                    case "markdown":
                        return MARKDOWN;
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
            if (firstLine.contains("sh") || firstLine.contains("bash")) {
                return TEXT;
            }
        }

        return UNKNOWN;
    }
}
