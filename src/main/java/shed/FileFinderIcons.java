package shed;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;

/** File-type glyphs for the File Finder; rendered only when a local Nerd Font can display them. */
final class FileFinderIcons {
    private static final String DEFAULT = "\ue64e";
    private static final String FOLDER = "\uf07b";
    private static final String OPEN_FOLDER = "\uf115";

    private FileFinderIcons() { }

    static String iconFor(String relativePath) {
        String path = relativePath == null ? "" : relativePath;
        String name = new java.io.File(path).getName().toLowerCase(Locale.ROOT);
        if ("pom.xml".equals(name)) return "\ue674";
        if ("package.json".equals(name) || "package-lock.json".equals(name)) return "\ue71e";
        if ("dockerfile".equals(name) || name.startsWith("dockerfile.")) return "\ue7b0";
        if ("makefile".equals(name) || "gnumakefile".equals(name)) return "\ue673";
        if (name.startsWith(".git")) return "\ue702";
        return switch (FileType.detect(new java.io.File(path), "")) {
            case RUST -> "\ue7a8";
            case PYTHON -> "\ue73c";
            case JAVASCRIPT -> "\ue74e";
            case TYPESCRIPT -> "\ue628";
            case GO -> "\ue627";
            case C -> "\ue61e";
            case CPP -> "\ue61d";
            case JAVA -> "\ue738";
            case KOTLIN -> "\ue634";
            case CSHARP -> "\ue648";
            case PHP -> "\ue608";
            case RUBY -> "\ue791";
            case SWIFT -> "\ue755";
            case HTML -> "\ue736";
            case CSS -> "\ue749";
            case JSON -> "\ue60b";
            case MARKDOWN -> "\ue73e";
            case SQL -> "\ue706";
            case SHELL -> "\ue795";
            case YAML, TOML, CMAKE -> "\ue615";
            case TEXT, UNKNOWN -> DEFAULT;
        };
    }

    static String iconForDirectory(java.io.File directory, boolean expanded) {
        String name = directory == null ? "" : directory.getName().toLowerCase(Locale.ROOT);
        if (".git".equals(name)) return "\ue702";
        if (".vscode".equals(name)) return "\ue70c";
        return expanded ? OPEN_FOLDER : FOLDER;
    }

    static Font availableNerdFont(Font textFont) {
        Font base = textFont == null ? new Font(Font.DIALOG, Font.PLAIN, 13) : textFont;
        try {
            if (canDisplayNerdIcons(base)) return base;
            for (String family : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT)) {
                if (!isNerdFontFamily(family)) continue;
                Font candidate = new Font(family, base.getStyle(), Math.max(12, base.getSize()));
                if (canDisplayNerdIcons(candidate)) return candidate;
            }
        } catch (SecurityException | java.awt.HeadlessException ignored) {
            // The Finder remains textual when local font discovery is unavailable.
        }
        return null;
    }

    private static boolean canDisplayNerdIcons(Font font) {
        return font != null && font.canDisplay(DEFAULT.codePointAt(0)) && font.canDisplay(FOLDER.codePointAt(0));
    }

    private static boolean isNerdFontFamily(String family) {
        String normalized = family == null ? "" : family.toLowerCase(Locale.ROOT);
        return normalized.contains("nerd") || normalized.matches(".*\\bnfm?\\b.*");
    }

    static ListCellRenderer<String> renderer(Texteditor editor, Map<String, String> pathsByCandidate) {
        Font textFont = editor.resolveUiFont();
        Font iconFont = availableNerdFont(textFont);
        return iconFont == null ? null : new Renderer(editor, pathsByCandidate == null ? Map.of() : Map.copyOf(pathsByCandidate), iconFont.getFamily(), iconFont.getStyle());
    }

    private static final class Renderer implements ListCellRenderer<String> {
        private final Texteditor editor;
        private final Map<String, String> paths;
        private final String iconFamily;
        private final int iconStyle;

        private Renderer(Texteditor editor, Map<String, String> paths, String iconFamily, int iconStyle) {
            this.editor = editor;
            this.paths = paths;
            this.iconFamily = iconFamily;
            this.iconStyle = iconStyle;
        }

        @Override public Component getListCellRendererComponent(JList<? extends String> list, String value, int index,
                                                                 boolean selected, boolean hasFocus) {
            String path = paths.getOrDefault(value, value == null ? "" : value);
            Color background = selected ? editor.configManager.getSelectionColor() : editor.configManager.getCommandBarBackground();
            Color foreground = selected ? editor.configManager.getSelectionTextColor() : editor.configManager.getCommandBarForeground();
            double zoom = editor.configManager.getUiZoom();
            Font textFont = editor.resolveUiFont();
            Font iconFont = new Font(iconFamily, iconStyle, Math.max(UiZoom.scale(12, zoom), textFont.getSize()));
            JPanel row = new JPanel(new BorderLayout(UiZoom.scale(8, zoom), 0));
            row.setOpaque(true);
            row.setBackground(background);
            row.setBorder(BorderFactory.createEmptyBorder(0, UiZoom.scale(6, zoom), 0, UiZoom.scale(6, zoom)));
            JLabel icon = new JLabel(iconFor(path));
            icon.setFont(iconFont);
            icon.setForeground(foreground);
            icon.setHorizontalAlignment(JLabel.CENTER);
            icon.setPreferredSize(new Dimension(UiZoom.scale(22, zoom), 0));
            JLabel label = new JLabel(path);
            label.setFont(textFont);
            label.setForeground(foreground);
            row.add(icon, BorderLayout.WEST);
            row.add(label, BorderLayout.CENTER);
            return row;
        }
    }
}
