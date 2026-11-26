package shed;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;

/** Reads data from a VSIX in memory. Nothing in the archive is extracted or executed. */
final class VsCodeThemeImporter {
    private static final Map<String, String> UI = Map.ofEntries(
        Map.entry("editor.background", "normal"), Map.entry("editor.foreground", "foreground"),
        Map.entry("focusBorder", "accent"), Map.entry("editorCursor.foreground", "ui.caret"),
        Map.entry("editor.selectionBackground", "ui.selection"), Map.entry("editor.selectionForeground", "ui.selection.text"),
        Map.entry("editor.lineHighlightBackground", "ui.currentline"), Map.entry("editorGutter.background", "ui.linenumber.background"),
        Map.entry("editorLineNumber.foreground", "ui.linenumber.foreground"), Map.entry("editorLineNumber.activeForeground", "ui.linenumber.active"),
        Map.entry("statusBar.background", "ui.status.background"), Map.entry("statusBar.foreground", "ui.status.foreground"),
        Map.entry("input.background", "ui.command.background"), Map.entry("input.foreground", "ui.command.foreground"),
        Map.entry("editor.findMatchHighlightBackground", "ui.search.background"), Map.entry("editor.findMatchBackground", "ui.search.current.background"),
        Map.entry("sideBar.background", "theme.surface.elevated"), Map.entry("editorWidget.background", "theme.surface.floating"),
        Map.entry("panel.border", "theme.border.subtle"), Map.entry("descriptionForeground", "theme.foreground.muted"));
    private static final String LIMITATION = "Colors are adapted to Shed; TextMate scope specificity, semantic styling, font styles and icon themes are not reproduced exactly.";
    private VsCodeThemeImporter() { }

    static List<ImportedTheme> translate(byte[] archive, OpenVsxThemeService.Extension extension,
                                        String license, String repository) throws IOException {
        if (archive.length > 20 * 1024 * 1024) throw new IOException("Theme archive exceeds 20 MiB");
        Map<String, byte[]> files = archive(archive);
        Map<String, Object> manifest = json(files, "extension/package.json");
        if (!extension.publisher().equals(manifest.get("publisher")) || !extension.name().equals(manifest.get("name"))
            || !extension.version().equals(manifest.get("version"))) throw new IOException("VSIX manifest does not match registry identity");
        Map<String, Object> contributes = MiniJson.asObject(manifest.get("contributes"));
        List<Object> declarations = contributes == null ? null : MiniJson.asArray(contributes.get("themes"));
        if (declarations == null || declarations.isEmpty() || declarations.size() > 64)
            throw new IOException("Package must contribute 1–64 color themes");
        String licenseText = "";
        for (String path : List.of("extension/LICENSE", "extension/LICENSE.txt", "extension/LICENSE.md", "extension/license.txt")) {
            if (files.containsKey(path)) {
                if (files.get(path).length > 32768) throw new IOException("Theme license exceeds 32 KiB");
                licenseText = utf8(files.get(path));
                break;
            }
        }
        List<ImportedTheme> themes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object raw : declarations) {
            Map<String, Object> declaration = MiniJson.asObject(raw);
            if (declaration == null) throw new IOException("Invalid theme contribution");
            String path = reference("extension/package.json", required(declaration, "path"));
            String name = OpenVsxThemeService.text(declaration, "label", 180);
            if (name.isBlank()) name = extension.displayName();
            String id = "openvsx:" + extension.id() + ":" + OpenVsxThemeService.sha256(path.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
            if (!ids.add(id)) throw new IOException("Duplicate theme path in package");
            Map<String, String> workbench = new LinkedHashMap<>();
            List<Object> tokens = new ArrayList<>();
            List<String> warnings = new ArrayList<>(List.of(LIMITATION));
            if (licenseText.isEmpty()) warnings.add("Package declares " + license + "; no conventional LICENSE file was supplied.");
            collect(files, path, new HashSet<>(), workbench, tokens, warnings);
            Map<String, String> colors = translateColors(workbench, tokens, declaration.get("uiTheme"));
            ImportedTheme theme = new ImportedTheme(id, name, extension.publisher(), extension.name(), extension.version(),
                license, licenseText, repository, colors, warnings.stream().distinct().limit(12).toList());
            theme.validate();
            themes.add(theme);
        }
        return List.copyOf(themes);
    }

    private static Map<String, byte[]> archive(byte[] bytes) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        int count = 0, total = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                OpenVsxThemeService.checkCancelled(null);
                if (++count > 4096) throw new IOException("Theme archive contains too many entries");
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("\\") || name.contains(":") || name.contains("\u0000")
                    || List.of(name.split("/")).contains("..") || name.length() > 500)
                    throw new IOException("Unsafe theme archive path");
                if (!seen.add(name)) throw new IOException("Duplicate theme archive entry");
                if (entry.isDirectory()) continue;
                byte[] value = zip.readNBytes(1024 * 1024 + 1);
                total += value.length;
                if (value.length > 1024 * 1024 || total > 16 * 1024 * 1024)
                    throw new IOException("Theme archive exceeds expanded size limits");
                // Other files, including JavaScript, binaries and extension dependencies, are discarded.
                String lower = name.toLowerCase(Locale.ROOT);
                if (name.startsWith("extension/") && (lower.endsWith(".json") || lower.endsWith(".jsonc")
                    || lower.contains("/license"))) files.put(name, value);
                zip.closeEntry();
            }
        }
        return files;
    }

    private static void collect(Map<String, byte[]> files, String path, Set<String> stack, Map<String, String> colors,
                                List<Object> tokens, List<String> warnings) throws IOException {
        if (stack.size() >= 8 || !stack.add(path)) throw new IOException("Theme include cycle or depth limit");
        Map<String, Object> data = json(files, path);
        if (data.get("include") instanceof String include) collect(files, reference(path, include), stack, colors, tokens, warnings);
        Map<String, Object> values = MiniJson.asObject(data.get("colors"));
        if (values != null) for (var value : values.entrySet()) {
            if (value.getValue() instanceof String color) colors.put(value.getKey(), color);
        }
        Object tokenColors = data.get("tokenColors");
        if (tokenColors instanceof String) throw new IOException("TextMate plist theme files are not supported yet; choose a JSON color theme");
        List<Object> rules = MiniJson.asArray(tokenColors);
        if (rules != null) {
            if (rules.size() > 8192 || tokens.size() + rules.size() > 8192) throw new IOException("Too many syntax theme rules");
            tokens.addAll(rules);
        }
        if (data.containsKey("semanticTokenColors")) warnings.add("Semantic-token rules are not imported; Shed uses its own syntax categories.");
        stack.remove(path);
    }

    private static Map<String, String> translateColors(Map<String, String> source, List<Object> tokens, Object uiTheme) throws IOException {
        boolean light = "vs".equals(uiTheme) || "hc-light".equals(uiTheme);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("normal", light ? "#FAFAFC" : "#24272E");
        result.put("foreground", light ? "#292D3E" : "#E6EDF3");
        result.put("accent", light ? "#315EBD" : "#82AAFF");
        result.put("string", light ? "#36744A" : "#C3E88D");
        if (source.containsKey("editor.background")) result.put("normal", hex(source.get("editor.background"), result.get("normal")));
        for (var entry : UI.entrySet()) {
            if (entry.getValue().equals("normal")) continue;
            String raw = source.get(entry.getKey());
            if (raw != null) result.put(entry.getValue(), hex(raw, result.get("normal")));
        }
        if (result.containsKey("accent")) result.put("theme.focus", result.get("accent"));
        for (Object item : tokens) {
            Map<String, Object> rule = MiniJson.asObject(item);
            if (rule == null) continue;
            Map<String, Object> settings = MiniJson.asObject(rule.get("settings"));
            if (settings == null || !(settings.get("foreground") instanceof String color)) continue;
            List<Object> scopes = MiniJson.asArray(rule.get("scope"));
            if (scopes == null && rule.get("scope") instanceof String scope) scopes = new ArrayList<>(List.of(scope.split(",")));
            if (scopes == null) {
                result.put("foreground", hex(color, result.get("normal")));
                continue;
            }
            for (Object scope : scopes) if (scope instanceof String selector) {
                String role = syntaxRole(selector.strip());
                if (role != null) result.put(role, hex(color, result.get("normal")));
            }
        }
        if (result.containsKey("ui.syntax.string")) result.put("string", result.get("ui.syntax.string"));
        return Map.copyOf(result);
    }

    private static String syntaxRole(String scope) {
        if (scope.contains(" ") || scope.contains("-") || scope.contains(":")) return null;
        if (scope.equals("comment") || scope.startsWith("comment.")) return "ui.syntax.comment";
        if (scope.equals("string") || scope.startsWith("string.")) return "ui.syntax.string";
        if (scope.equals("keyword") || scope.startsWith("keyword.") || scope.equals("storage") || scope.startsWith("storage.")) return "ui.syntax.keyword";
        if (scope.startsWith("constant.numeric")) return "ui.syntax.number";
        if (scope.equals("constant") || scope.startsWith("constant.")) return "ui.syntax.constant";
        if (scope.startsWith("entity.name.function") || scope.startsWith("support.function")) return "ui.syntax.function";
        if (scope.startsWith("entity.name.type") || scope.startsWith("support.type") || scope.startsWith("support.class")) return "ui.syntax.type";
        if (scope.startsWith("entity.name.tag") || scope.startsWith("meta.annotation")) return "ui.syntax.annotation";
        return null;
    }

    static String hex(String raw, String background) throws IOException {
        if (!raw.matches("#[0-9a-fA-F]{3,4}|#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) throw new IOException("Unsupported theme color value");
        String digits = raw.substring(1);
        if (digits.length() <= 4) {
            StringBuilder expanded = new StringBuilder();
            for (char c : digits.toCharArray()) expanded.append(c).append(c);
            digits = expanded.toString();
        }
        Color color = Color.decode("#" + digits.substring(0, 6));
        double alpha = digits.length() == 8 ? Integer.parseInt(digits.substring(6), 16) / 255d : 1;
        Color base = Color.decode(background);
        return String.format("#%02X%02X%02X", (int) Math.round(color.getRed() * alpha + base.getRed() * (1 - alpha)),
            (int) Math.round(color.getGreen() * alpha + base.getGreen() * (1 - alpha)),
            (int) Math.round(color.getBlue() * alpha + base.getBlue() * (1 - alpha)));
    }

    private static Map<String, Object> json(Map<String, byte[]> files, String path) throws IOException {
        byte[] value = files.get(path);
        if (value == null) throw new IOException("Theme JSON file is missing or unsupported: " + path);
        String text = utf8(value);
        return Jsonc.parseObject(text.startsWith("\uFEFF") ? text.substring(1) : text);
    }
    private static String utf8(byte[] bytes) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException error) { throw new IOException("Theme data is not valid UTF-8", error); }
    }
    private static String required(Map<String, Object> data, String key) throws IOException {
        if (!(data.get(key) instanceof String value) || value.isBlank()) throw new IOException("Theme " + key + " is missing");
        return value;
    }
    private static String reference(String from, String path) throws IOException {
        if (path.contains("\\") || path.contains(":") || path.startsWith("/") || path.contains("\u0000") || path.length() > 500)
            throw new IOException("Unsafe theme reference");
        Path resolved = Path.of(from).getParent().resolve(path).normalize();
        if (!resolved.startsWith(Path.of("extension"))) throw new IOException("Theme reference escapes its package");
        return resolved.toString().replace('\\', '/');
    }
}
