package shed;

enum LspCapability {
    COMPLETION("completion", "completionProvider"),
    SIGNATURE_HELP("signature help", "signatureHelpProvider"),
    HOVER("hover", "hoverProvider"),
    DEFINITION("definition", "definitionProvider"),
    REFERENCES("references", "referencesProvider"),
    RENAME("rename", "renameProvider"),
    CODE_ACTION("code actions", "codeActionProvider"),
    FORMATTING("formatting", "documentFormattingProvider"),
    SEMANTIC_TOKENS("semantic tokens", "semanticTokensProvider"),
    INLAY_HINTS("inlay hints", "inlayHintProvider"),
    DOCUMENT_SYMBOLS("document symbols", "documentSymbolProvider"),
    WORKSPACE_SYMBOLS("workspace symbols", "workspaceSymbolProvider"),
    EXECUTE_COMMAND("command execution", "executeCommandProvider");

    private final String displayName;
    private final String serverField;

    LspCapability(String displayName, String serverField) {
        this.displayName = displayName;
        this.serverField = serverField;
    }

    String displayName() {
        return displayName;
    }

    String serverField() {
        return serverField;
    }
}
