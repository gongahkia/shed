package shed;

final class ReplacementResult {
    final String updatedText;
    final int matchCount;
    final int firstMatchOffset;

    ReplacementResult(String updatedText, int matchCount, int firstMatchOffset) {
        this.updatedText = updatedText;
        this.matchCount = matchCount;
        this.firstMatchOffset = firstMatchOffset;
    }
}
