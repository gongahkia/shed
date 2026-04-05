package shed;

public class LspService {
    public String languageId(FileType fileType) {
        switch (fileType) {
            case RUST:
                return "rust";
            case PYTHON:
                return "python";
            case JAVASCRIPT:
                return "javascript";
            case TYPESCRIPT:
                return "typescript";
            case GO:
                return "go";
            case C:
            case CPP:
                return "c";
            case JAVA:
                return "java";
            case HTML:
                return "html";
            case CSS:
                return "css";
            case JSON:
                return "json";
            case MARKDOWN:
                return "markdown";
            default:
                return "text";
        }
    }

    public String[] builtinCommand(String extension) {
        switch (extension) {
            case "rs":
                return new String[] {"rust-analyzer"};
            case "py":
                return new String[] {"pyright-langserver", "--stdio"};
            case "js":
            case "jsx":
            case "ts":
            case "tsx":
                return new String[] {"typescript-language-server", "--stdio"};
            case "go":
                return new String[] {"gopls"};
            case "c":
            case "cpp":
            case "h":
            case "hpp":
                return new String[] {"clangd"};
            default:
                return null;
        }
    }
}
