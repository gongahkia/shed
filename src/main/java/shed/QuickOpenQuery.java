package shed;

import java.util.regex.Pattern;

/** Only a trailing positive line[:column] is syntax; drive letters and other colons are literal. */
record QuickOpenQuery(String fileQuery, int line, int column) {
    private static final Pattern LOCATION = Pattern.compile("^(.*?):([0-9]+)(?::([0-9]+))?$", Pattern.DOTALL);

    static QuickOpenQuery parse(String input) {
        String query = input == null ? "" : input;
        var match = LOCATION.matcher(query);
        if (match.matches()) {
            try {
                int line = Integer.parseInt(match.group(2));
                int column = match.group(3) == null ? 1 : Integer.parseInt(match.group(3));
                if (line > 0 && column > 0) return new QuickOpenQuery(match.group(1), line, column);
            } catch (NumberFormatException ignored) { }
        }
        return new QuickOpenQuery(query, 0, 0);
    }

    boolean hasLocation() { return line > 0; }
}
