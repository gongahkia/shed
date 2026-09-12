package shed;

import java.awt.GraphicsEnvironment;

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
}
