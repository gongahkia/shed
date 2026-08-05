package shed;

record LspDocumentChange(int startLine, int startCharacter, int endLine, int endCharacter, String text) {
    LspDocumentChange {
        if (startLine < 0 || startCharacter < 0 || endLine < startLine || endCharacter < 0) {
            throw new IllegalArgumentException("invalid LSP document range");
        }
        text = text == null ? "" : text;
    }
}
