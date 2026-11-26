package shed;

import java.awt.Color;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A translated palette, never an executable extension or a second styling engine. */
record ImportedTheme(String id, String name, String publisher, String extension, String version,
                     String license, String licenseText, String repository, Map<String, String> colors, List<String> limitations) {
    static final Set<String> ROLES = Set.of("normal", "foreground", "accent", "string", "ui.caret", "ui.selection",
        "ui.selection.text", "ui.currentline", "ui.linenumber.background", "ui.linenumber.foreground",
        "ui.linenumber.active", "ui.status.background", "ui.status.foreground", "ui.command.background",
        "ui.command.foreground", "ui.search.background", "ui.search.current.background", "theme.surface.elevated",
        "theme.surface.floating", "theme.border.subtle", "theme.focus", "theme.foreground.muted",
        "ui.syntax.keyword", "ui.syntax.string", "ui.syntax.comment", "ui.syntax.type", "ui.syntax.function",
        "ui.syntax.constant", "ui.syntax.annotation", "ui.syntax.number");

    ImportedTheme {
        colors = Map.copyOf(colors);
        limitations = List.copyOf(limitations);
    }

    String packageId() { return publisher + "." + extension; }
    String displayName() { return name + " · " + packageId(); }
    Color color(String role) { return Color.decode(colors.get(role)); }
    ThemePreview preview() {
        Color background = color("normal");
        return new ThemePreview(id, displayName(), background, background, background, background, background,
            color("foreground"), color("accent"), color("string"));
    }

    void validate() throws IOException {
        if (!id.matches("openvsx:[A-Za-z0-9_.-]{1,257}:[a-f0-9]{16}")
            || !id.startsWith("openvsx:" + packageId() + ":")) throw new IOException("Invalid imported theme identity");
        OpenVsxThemeService.identifier(publisher);
        OpenVsxThemeService.identifier(extension);
        OpenVsxThemeService.identifier(version);
        if (name.isBlank() || name.length() > 180 || license.length() > 180 || licenseText.length() > 32768 || repository.length() > 500
            || name.indexOf('<') >= 0 || name.chars().anyMatch(Character::isISOControl)
            || limitations.size() > 12 || limitations.stream().anyMatch(s -> s.length() > 240)
            || colors.size() > ROLES.size() || !colors.keySet().containsAll(Set.of("normal", "foreground", "accent", "string")))
            throw new IOException("Invalid imported theme data");
        for (var entry : colors.entrySet()) {
            if (!ROLES.contains(entry.getKey()) || !entry.getValue().matches("#[0-9A-Fa-f]{6}"))
                throw new IOException("Unsupported imported color role or value");
        }
    }

    Map<String, Object> toJson() {
        return Map.of("id", id, "name", name, "publisher", publisher, "extension", extension, "version", version,
            "license", license, "licenseText", licenseText, "repository", repository, "colors", colors, "limitations", limitations);
    }

    static ImportedTheme fromJson(Map<String, Object> data) throws IOException {
        try {
            Map<String, String> colors = new LinkedHashMap<>();
            MiniJson.asObject(data.get("colors")).forEach((k, v) -> colors.put(k, (String) v));
            List<String> limitations = MiniJson.asArray(data.get("limitations")).stream().map(String.class::cast).toList();
            ImportedTheme theme = new ImportedTheme((String) data.get("id"), (String) data.get("name"),
                (String) data.get("publisher"), (String) data.get("extension"), (String) data.get("version"),
                (String) data.get("license"), (String) data.get("licenseText"), (String) data.get("repository"), colors, limitations);
            theme.validate();
            return theme;
        } catch (RuntimeException error) { throw new IOException("Invalid installed theme catalog", error); }
    }
}
