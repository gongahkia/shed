package shed;

enum LspCapability {
    COMPLETION("completion", "completionProvider"),
    SIGNATURE_HELP("signature help", "signatureHelpProvider"),
    HOVER("hover", "hoverProvider"),
    DEFINITION("definition", "definitionProvider"),
    TYPE_DEFINITION("type definition", "typeDefinitionProvider"),
    IMPLEMENTATION("implementation", "implementationProvider"),
    DOCUMENT_HIGHLIGHTS("document highlights", "documentHighlightProvider"),
    CALL_HIERARCHY("call hierarchy", "callHierarchyProvider"),
    TYPE_HIERARCHY("type hierarchy", "typeHierarchyProvider"),
    REFERENCES("references", "referencesProvider"),
    RENAME("rename", "renameProvider"),
    CODE_ACTION("code actions", "codeActionProvider"),
    FORMATTING("formatting", "documentFormattingProvider"),
    SEMANTIC_TOKENS("semantic tokens", "semanticTokensProvider"),
    INLAY_HINTS("inlay hints", "inlayHintProvider"),
    CODE_LENS("code lenses", "codeLensProvider"),
    SELECTION_RANGES("selection ranges", "selectionRangeProvider"),
    DOCUMENT_LINKS("document links", "documentLinkProvider"),
    DOCUMENT_COLORS("document colors", "colorProvider"),
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
