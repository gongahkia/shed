package shed;

import java.awt.GraphicsEnvironment;
import java.util.Comparator;
import java.util.List;

/** Resolves configured font-family names against the Java runtime's installed font catalog. */
final class FontFamilyCatalog {
    private FontFamilyCatalog() {
    }

    static String resolve(String requestedFamily) {
        if (requestedFamily == null || requestedFamily.isBlank()) {
            return null;
        }
        String requested = requestedFamily.trim();
        for (String installed : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
            if (installed.equalsIgnoreCase(requested)) {
                return installed;
            }
        }
        return null;
    }

    static List<String> closest(String requestedFamily, int limit) {
        if (requestedFamily == null || requestedFamily.isBlank() || limit <= 0) {
            return List.of();
        }
        String requested = normalized(requestedFamily);
        return java.util.Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
            .sorted(Comparator.comparingInt(family -> distance(requested, normalized(family))))
            .limit(limit)
            .toList();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT);
    }

    private static int distance(String first, String second) {
        int[] previous = new int[second.length() + 1];
        int[] current = new int[second.length() + 1];
        for (int index = 0; index <= second.length(); index++) previous[index] = index;
        for (int firstIndex = 1; firstIndex <= first.length(); firstIndex++) {
            current[0] = firstIndex;
            for (int secondIndex = 1; secondIndex <= second.length(); secondIndex++) {
                int cost = first.charAt(firstIndex - 1) == second.charAt(secondIndex - 1) ? 0 : 1;
                current[secondIndex] = Math.min(Math.min(current[secondIndex - 1] + 1, previous[secondIndex] + 1), previous[secondIndex - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[second.length()];
    }
}
