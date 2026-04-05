package shed;

public class SubstituteService {
    public Result replaceLiteral(String text, String pattern, String replacement, boolean replaceAll) {
        if (text == null) {
            text = "";
        }
        if (pattern == null || pattern.isEmpty()) {
            return new Result(text, 0, -1);
        }
        if (replacement == null) {
            replacement = "";
        }

        StringBuilder builder = new StringBuilder();
        int searchFrom = 0;
        int matchCount = 0;
        int firstMatchOffset = -1;

        while (searchFrom <= text.length()) {
            int matchIndex = text.indexOf(pattern, searchFrom);
            if (matchIndex < 0) {
                break;
            }

            if (firstMatchOffset < 0) {
                firstMatchOffset = matchIndex;
            }

            builder.append(text, searchFrom, matchIndex);
            builder.append(replacement);
            searchFrom = matchIndex + pattern.length();
            matchCount++;

            if (!replaceAll) {
                break;
            }
        }

        if (matchCount == 0) {
            return new Result(text, 0, -1);
        }

        builder.append(text.substring(searchFrom));
        return new Result(builder.toString(), matchCount, firstMatchOffset);
    }

    public static final class Result {
        private final String updatedText;
        private final int matchCount;
        private final int firstMatchOffset;

        public Result(String updatedText, int matchCount, int firstMatchOffset) {
            this.updatedText = updatedText;
            this.matchCount = matchCount;
            this.firstMatchOffset = firstMatchOffset;
        }

        public String getUpdatedText() {
            return updatedText;
        }

        public int getMatchCount() {
            return matchCount;
        }

        public int getFirstMatchOffset() {
            return firstMatchOffset;
        }
    }
}
