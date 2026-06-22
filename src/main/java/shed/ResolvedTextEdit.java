package shed;

final class ResolvedTextEdit {
    final int startOffset;
    final int endOffset;
    final String newText;
    final int order;

    ResolvedTextEdit(int startOffset, int endOffset, String newText) {
        this(startOffset, endOffset, newText, 0);
    }

    ResolvedTextEdit(int startOffset, int endOffset, String newText, int order) {
        this.startOffset = Math.max(0, startOffset);
        this.endOffset = Math.max(this.startOffset, endOffset);
        this.newText = newText == null ? "" : newText;
        this.order = order;
    }
}
