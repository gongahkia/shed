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
                return "c";
            case CPP:
                return "cpp";
            case JAVA:
                return "java";
            case KOTLIN:
                return "kotlin";
            case CSHARP:
                return "csharp";
            case PHP:
                return "php";
            case RUBY:
                return "ruby";
            case SWIFT:
                return "swift";
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

    public java.util.List<String> getBuiltinExtensions() {
        return java.util.List.of("java", "rs", "py", "js", "jsx", "ts", "tsx", "go", "c", "cc", "cpp", "cxx", "h", "hpp", "hxx",
            "json", "jsonc", "html", "htm", "xhtml", "css", "scss", "less", "md", "markdown", "kt", "kts", "cs", "csx", "php", "phtml",
            "php3", "php4", "php5", "phps", "rb", "rake", "gemspec", "swift");
    }

    public String[] builtinCommand(String extension) {
        switch (extension) {
            case "java":
                return new String[] {ManagedLanguageCatalog.java().command()};
            case "rs":
                return new String[] {ManagedLanguageCatalog.rust().command()};
            case "py":
                return new String[] {ManagedLanguageCatalog.python().command(), "--stdio"};
            case "js":
            case "jsx":
            case "ts":
            case "tsx":
                return new String[] {ManagedLanguageCatalog.typescriptJavascript().command(), "--stdio"};
            case "go":
                return new String[] {ManagedLanguageCatalog.go().command()};
            case "c":
            case "cc":
            case "cpp":
            case "cxx":
            case "h":
            case "hpp":
            case "hxx":
                return new String[] {ManagedLanguageCatalog.cCpp().command()};
            case "json":
            case "jsonc":
                return new String[] {ManagedLanguageCatalog.json().command(), "--stdio"};
            case "html":
            case "htm":
            case "xhtml":
                return new String[] {ManagedLanguageCatalog.html().command(), "--stdio"};
            case "css":
            case "scss":
            case "less":
                return new String[] {ManagedLanguageCatalog.css().command(), "--stdio"};
            case "md":
            case "markdown":
                return new String[] {ManagedLanguageCatalog.markdown().command(), "--stdio"};
            case "kt":
            case "kts":
                return new String[] {ManagedLanguageCatalog.kotlin().command()};
            case "cs":
            case "csx":
                return new String[] {ManagedLanguageCatalog.csharp().command()};
            case "php":
            case "phtml":
            case "php3":
            case "php4":
            case "php5":
            case "phps":
                return new String[] {ManagedLanguageCatalog.php().command(), "--stdio"};
            case "rb":
            case "rake":
            case "gemspec":
                return new String[] {ManagedLanguageCatalog.ruby().command()};
            case "swift":
                return new String[] {ManagedLanguageCatalog.swift().command()};
            default:
                return null;
        }
    }
}
