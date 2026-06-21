package shed;

final class ResolvedTextEdit {
    final int startOffset;
    final int endOffset;
    final String newText;

    ResolvedTextEdit(int startOffset, int endOffset, String newText) {
        this.startOffset = Math.max(0, startOffset);
        this.endOffset = Math.max(this.startOffset, endOffset);
        this.newText = newText == null ? "" : newText;
    }
}
