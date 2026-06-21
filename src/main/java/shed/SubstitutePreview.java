package shed;

final class SubstitutePreview {
    final String pattern;
    final int startLine;
    final int endLine;

    SubstitutePreview(String pattern, int startLine, int endLine) {
        this.pattern = pattern;
        this.startLine = startLine;
        this.endLine = endLine;
    }
}
