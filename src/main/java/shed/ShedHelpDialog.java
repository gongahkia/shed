package shed;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Searchable, local help window shared by F1, the Help menu, and :help. */
final class ShedHelpDialog extends JDialog {
    private record Topic(String id, String title, String group, String summary) { }

    private static final List<Topic> TOPICS = List.of(
        new Topic("start", "Getting started", "START HERE", "Open a file, edit, save, and find your way around."),
        new Topic("plain", "Plain editing", "EDITING", "Native text editing and everyday shortcuts."),
        new Topic("keymap", "Keymap profiles", "EDITING", "Choose Plain, Vim, or Emacs and inspect bindings."),
        new Topic("emacs", "Emacs editing", "EDITING", "Navigation, editing, and Ctrl-X chords."),
        new Topic("textobjects", "Text objects", "VIM EDITING", "Select words, paragraphs, quotes, and brackets."),
        new Topic("surround", "Surround", "VIM EDITING", "Add, change, and remove delimiters."),
        new Topic("registers", "Registers", "VIM EDITING", "Named and special yank registers."),
        new Topic("macros", "Macros", "VIM EDITING", "Record and replay editing actions."),
        new Topic("tree", "File explorer", "NAVIGATION", "Browse and manage files in the side tree."),
        new Topic("symbols", "Symbols", "NAVIGATION", "Jump to a class, function, or heading."),
        new Topic("windows", "Split windows", "NAVIGATION", "Create splits and move focus between them."),
        new Topic("lsp", "Language services", "CODE TOOLS", "Completion, definitions, diagnostics, and formatting."),
        new Topic("tasks", "Tasks", "CODE TOOLS", "Run project tasks and inspect quickfix results."),
        new Topic("git", "Git", "CODE TOOLS", "Inspect and change repository state."),
        new Topic("session", "Sessions and workspaces", "WORKSPACE", "Restore files and manage workspace indexes."),
        new Topic("settings", "Settings", "WORKSPACE", "Configure Shed and open the typed settings reference."),
        new Topic("settings-reference", "Settings reference", "WORKSPACE", "Every setting, default, and allowed value."),
        new Topic("perf", "Performance diagnostics", "WORKSPACE", "Read local timings and benchmark the workspace index."),
        new Topic("remote", "Remote workspaces", "INTEGRATIONS", "Connect through explicit local mirror actions."),
        new Topic("container", "Dev Containers", "INTEGRATIONS", "Build, connect, and run container actions."),
        new Topic("docker", "Docker", "INTEGRATIONS", "Inspect and control local containers."),
        new Topic("compose", "Docker Compose", "INTEGRATIONS", "Run services with the installed Compose tool."),
        new Topic("database", "PostgreSQL", "INTEGRATIONS", "Run explicit database commands."),
        new Topic("update", "Updates", "INTEGRATIONS", "Manage consent for signed update checks."),
        new Topic("keybind", "Custom keybindings", "REFERENCE", "Configure Vim key mappings."),
        new Topic("commands", "Command aliases", "REFERENCE", "Define aliases for built-in commands."),
        new Topic("reference", "All commands", "REFERENCE", "Browse the complete command and motion reference.")
    );

    private final Texteditor editor;
    private final DefaultListModel<Topic> topicModel = new DefaultListModel<>();
    private final JList<Topic> topicList = new JList<>(topicModel);
    private final JTextField search = new JTextField();
    private final JEditorPane article = new JEditorPane();
    private final JScrollPane articleScroll = new JScrollPane(article);
    private final JLabel resultCount = new JLabel();
    private Topic selected;
    private String renderedAppearance;

    static void showFor(Texteditor editor, String topic) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> showFor(editor, topic));
            return;
        }
        for (Window window : editor.getOwnedWindows()) {
            if (window instanceof ShedHelpDialog dialog && dialog.isDisplayable()) {
                dialog.openTopic(topic);
                dialog.setVisible(true);
                dialog.toFront();
                return;
            }
        }
        ShedHelpDialog dialog = new ShedHelpDialog(editor);
        dialog.openTopic(topic);
        dialog.setVisible(true);
    }

    private ShedHelpDialog(Texteditor editor) {
        super(editor, "Shed Help", false);
        this.editor = editor;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        KeyboardFocusSupport.installEscape(getRootPane(), this::dispose);
        setLayout(new BorderLayout(0, 0));
        add(header(), BorderLayout.NORTH);
        add(body(), BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);
        editor.editorUiController.prepareDialog(this, 1040, 720);
        pack();
        setLocationRelativeTo(editor);
        addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override public void windowGainedFocus(java.awt.event.WindowEvent event) {
                if (!appearanceKey().equals(renderedAppearance)) render();
            }
        });
        installSearchShortcut();
    }

    private JPanel header() {
        JPanel panel = new JPanel(new BorderLayout(14, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(18, 22, 16, 22));
        JPanel identity = new JPanel(new BorderLayout(0, 3));
        JLabel title = new JLabel("Shed Help");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 6f));
        identity.add(title, BorderLayout.NORTH);
        identity.add(new JLabel("Guides, shortcuts, and command reference"), BorderLayout.SOUTH);
        panel.add(identity, BorderLayout.WEST);
        search.putClientProperty("JTextField.placeholderText", "Search help");
        search.setToolTipText("Search topics and their contents (Ctrl/Cmd+F)");
        AccessibilitySupport.describe(search, "Search help", "Filter help topics by title, summary, or content.");
        search.setPreferredSize(new Dimension(300, 34));
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { filterTopics(); }
            @Override public void removeUpdate(DocumentEvent event) { filterTopics(); }
            @Override public void changedUpdate(DocumentEvent event) { filterTopics(); }
        });
        panel.add(search, BorderLayout.EAST);
        return panel;
    }

    private JSplitPane body() {
        topicList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        topicList.setCellRenderer(new TopicRenderer());
        topicList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && topicList.getSelectedValue() != null) select(topicList.getSelectedValue());
        });
        AccessibilitySupport.describe(topicList, "Help topics", "Choose a topic to read its guide and commands.");
        JScrollPane navigation = new JScrollPane(topicList);
        navigation.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
        navigation.setPreferredSize(new Dimension(250, 600));

        article.setContentType("text/html");
        article.setEditable(false);
        article.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        article.addHyperlinkListener(event -> {
            if (event.getEventType() != javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) return;
            String target = event.getDescription();
            if (target != null && target.startsWith("shed:")) {
                openTopic(target.substring(5));
            }
        });
        AccessibilitySupport.describe(article, "Help article", "The selected Shed help topic. Links open other help topics.");
        articleScroll.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 12));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navigation, articleScroll);
        split.setResizeWeight(0.24);
        split.setDividerLocation(250);
        return split;
    }

    private JPanel footer() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 22, 12, 22));
        panel.add(resultCount, BorderLayout.WEST);
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        JPanel actions = new JPanel(new BorderLayout());
        actions.add(close, BorderLayout.EAST);
        panel.add(actions, BorderLayout.EAST);
        return panel;
    }

    private void installSearchShortcut() {
        int modifier = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_F, modifier), "focus-help-search");
        getRootPane().getActionMap().put("focus-help-search", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                search.requestFocusInWindow();
                search.selectAll();
            }
        });
    }

    private void openTopic(String requested) {
        String id = canonicalTopic(requested);
        Topic topic = TOPICS.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
        if (topic == null) {
            search.setText(requested == null ? "" : requested.trim());
            search.requestFocusInWindow();
            return;
        }
        if (!search.getText().isEmpty()) search.setText("");
        filterTopics();
        topicList.setSelectedValue(topic, true);
        select(topic);
    }

    private void filterTopics() {
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        Topic previous = selected;
        topicModel.clear();
        for (Topic topic : TOPICS) {
            if (matches(topic, query)) topicModel.addElement(topic);
        }
        resultCount.setText(query.isEmpty() ? "Browse topics" : topicModel.size() + " matching topic" + (topicModel.size() == 1 ? "" : "s"));
        if (previous != null && topicModel.contains(previous)) topicList.setSelectedValue(previous, true);
        else if (!topicModel.isEmpty()) topicList.setSelectedIndex(0);
        else {
            selected = null;
            article.setText(page("No results", "Try another word or a command name, such as <code>:lsp</code>.", ""));
        }
        if (selected != null && topicModel.contains(selected)) render();
    }

    private boolean matches(Topic topic, String query) {
        if (query.isEmpty()) return true;
        String content = switch (topic.id()) {
            case "start" -> INTRO_SEARCH;
            case "settings" -> SETTINGS_SEARCH;
            case "settings-reference" -> editor.configManager.typedSettingsReference();
            default -> editor.helpService.getHelpText(topic.id().equals("reference") ? "" : topic.id(), editor.VERSION);
        };
        String haystack = (topic.title() + " " + topic.group() + " " + topic.summary() + " " + content).toLowerCase(Locale.ROOT);
        for (String word : query.split("\\s+")) if (!haystack.contains(word)) return false;
        return true;
    }

    private void select(Topic topic) {
        selected = topic;
        render();
        articleScroll.getVerticalScrollBar().setValue(0);
    }

    private void render() {
        if (selected == null) return;
        String content = switch (selected.id()) {
            case "start" -> intro();
            case "settings" -> settings();
            case "settings-reference" -> settingsReference();
            default -> referenceContent(selected);
        };
        article.setText(page(selected.title(), selected.summary(), content));
        article.setCaretPosition(0);
        renderedAppearance = appearanceKey();
    }

    private String appearanceKey() {
        return editor.configManager.getThemeId() + ":" + editor.configManager.getEditorForeground().getRGB()
            + ":" + editor.configManager.getNormalColor().getRGB() + ":" + editor.configManager.getUiZoom()
            + ":" + editor.configManager.getUiFontFamily() + ":" + editor.configManager.getUiFontSize();
    }

    private String referenceContent(Topic topic) {
        String raw = editor.helpService.getHelpText(topic.id().equals("reference") ? "" : topic.id(), editor.VERSION);
        List<String> rows = new ArrayList<>();
        StringBuilder html = new StringBuilder();
        for (String source : raw.split("\\R")) {
            String line = source.trim();
            if (line.isEmpty() || line.startsWith("Help:") || line.startsWith("Shed v")
                || line.equals("note: this is a help buffer. use :q to return.")) continue;
            if (line.matches("[A-Z][A-Z /&-]+") && line.length() < 42) {
                flushRows(rows, html);
                html.append("<h2>").append(mark(line)).append("</h2>");
                continue;
            }
            String[] columns = line.split("\\s{2,}", 2);
            if (columns.length == 2 && !columns[0].isBlank() && columns[0].length() < 50) {
                rows.add("<tr><td valign='top'><code>" + mark(columns[0]) + "</code></td><td>" + mark(columns[1]) + "</td></tr>");
            } else {
                flushRows(rows, html);
                html.append("<p>").append(mark(line)).append("</p>");
            }
        }
        flushRows(rows, html);
        return html.toString();
    }

    private static void flushRows(List<String> rows, StringBuilder html) {
        if (rows.isEmpty()) return;
        html.append("<table width='100%' cellspacing='0' cellpadding='7'>");
        for (String row : rows) html.append(row);
        html.append("</table>");
        rows.clear();
    }

    private String intro() {
        return "<h2>Make your first edit</h2>"
            + "<ol><li>Open a file with <code>Ctrl/Cmd+O</code>, or choose <b>File → Open</b>.</li>"
            + "<li>Type directly. Shed starts in the Plain keymap profile.</li>"
            + "<li>Save with <code>Ctrl/Cmd+S</code>. Use <code>Ctrl/Cmd+F</code> to find text.</li></ol>"
            + "<h2>Where to go next</h2>"
            + "<p><a href='shed:plain'>Plain editing</a> shows everyday shortcuts. "
            + "<a href='shed:keymap'>Keymap profiles</a> covers Vim and Emacs. "
            + "<a href='shed:tree'>File explorer</a> helps you browse a workspace.</p>"
            + "<p>Use <code>Ctrl/Cmd+Shift+P</code> for the command palette. "
            + "In Vim mode, <code>:help topic</code> opens the matching page. F1 opens Help from any profile.</p>"
            + "<h2>Local by default</h2>"
            + "<p>Shed is a desktop editor. Language services, Git, tasks, containers, and remote connections "
            + "use installed tools or explicit actions. <a href='shed:lsp'>Language services</a> and "
            + "<a href='shed:perf'>performance diagnostics</a> have their own guides.</p>";
    }

    private String settings() {
        return "<h2>Configure Shed</h2>"
            + "<p>Open <code>~/.shed/config.toml</code> with <code>:settings</code>. "
            + "Use <code>:reload</code> after external changes, or <code>:set key=value</code> for this session.</p>"
            + "<p>Project overrides can live in <code>.shed.toml</code>. Shed asks before trusting them on first open.</p>"
            + "<h2>Find a setting</h2>"
            + "<p><code>:config inspector</code> opens the typed settings inspector. "
            + "<a href='shed:settings-reference'>Open the complete settings reference</a> for every key and default.</p>"
            + "<p>See <a href='shed:keymap'>keymap profiles</a> and <a href='shed:keybind'>custom keybindings</a> "
            + "for keyboard configuration.</p>";
    }

    private String settingsReference() {
        String query = search.getText().trim();
        List<TypedSettings.Descriptor> descriptors = query.isEmpty()
            ? editor.configManager.typedSettingDescriptors() : editor.configManager.searchTypedSettings(query);
        StringBuilder html = new StringBuilder("<p>Use <code>:config inspector</code> to inspect or change a setting.</p>");
        html.append("<p>").append(descriptors.size()).append(" setting")
            .append(descriptors.size() == 1 ? "" : "s").append(" shown</p>");
        for (TypedSettings.Descriptor descriptor : descriptors) {
            html.append("<h2><code>").append(mark(descriptor.key())).append("</code></h2>")
                .append("<p>").append(mark(descriptor.description())).append("</p>")
                .append("<table width='100%' cellspacing='0' cellpadding='7'>")
                .append("<tr><td>Type</td><td>").append(mark(descriptor.type())).append("</td></tr>")
                .append("<tr><td>Default</td><td><code>").append(mark(descriptor.defaultValue())).append("</code></td></tr>")
                .append("<tr><td>Allowed</td><td>").append(mark(descriptor.allowedValues())).append("</td></tr>")
                .append("<tr><td>Applies</td><td>").append(mark(descriptor.applyBehavior())).append("</td></tr>")
                .append("</table>");
        }
        return html.toString();
    }

    private String page(String title, String summary, String content) {
        Color foreground = editor.configManager.getEditorForeground();
        Color background = editor.configManager.getNormalColor();
        Color accent = AccessibilitySupport.readableText(editor.configManager.getCaretColor(), background);
        String configuredFont = editor.configManager.getUiFontFamily();
        String font = configuredFont == null || configuredFont.isBlank() ? Font.SANS_SERIF : configuredFont.replace("'", "");
        int size = UiZoom.scale(Math.max(12, editor.configManager.getUiFontSize()), editor.configManager.getUiZoom());
        int titleSize = UiZoom.scale(22, editor.configManager.getUiZoom());
        int headingSize = UiZoom.scale(15, editor.configManager.getUiZoom());
        return "<html><head><style type='text/css'>"
            + "body { font-family:'" + escape(font) + "'; font-size:" + size + "pt; color:" + hex(foreground)
            + "; background:" + hex(background) + "; margin:22px 30px 36px 30px; }"
            + "h1 { font-size:" + titleSize + "pt; margin-bottom:6px; } h2 { font-size:" + headingSize + "pt; margin-top:24px; }"
            + "p, li, td { line-height:1.3; } td { border-bottom:1px solid " + hex(blend(background, foreground, 0.18)) + "; }"
            + "a { color:" + hex(accent) + "; } code { font-family:monospace; font-size:" + size + "pt; }"
            + "</style></head><body><h1>" + escape(title) + "</h1><p>" + escape(summary) + "</p>"
            + content + "</body></html>";
    }

    private String mark(String source) {
        if (source == null) return "";
        String query = search.getText().trim();
        if (query.isEmpty()) return escape(source);
        String needle = query.split("\\s+", 2)[0];
        String lower = source.toLowerCase(Locale.ROOT);
        int cursor = 0;
        int next;
        StringBuilder result = new StringBuilder();
        while ((next = lower.indexOf(needle.toLowerCase(Locale.ROOT), cursor)) >= 0) {
            result.append(escape(source.substring(cursor, next)));
            result.append("<b><u>").append(escape(source.substring(next, next + needle.length()))).append("</u></b>");
            cursor = next + needle.length();
        }
        return result.append(escape(source.substring(cursor))).toString();
    }

    private static String canonicalTopic(String value) {
        String topic = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (topic) {
            case "", "open", "home", "intro", "getting-started" -> "start";
            case "all", "full", "reference" -> "reference";
            case "config", "configuration" -> "settings";
            case "keymaps" -> "keymap";
            case "split", "vsplit" -> "windows";
            case "reg" -> "registers";
            case "macro" -> "macros";
            case "text-objects", "objects" -> "textobjects";
            case "completion" -> "lsp";
            case "symbol", "sym" -> "symbols";
            case "task" -> "tasks";
            case "docker-compose" -> "compose";
            case "containers" -> "docker";
            case "devcontainer" -> "container";
            case "remotes" -> "remote";
            case "db" -> "database";
            case "updates" -> "update";
            case "sessions" -> "session";
            case "performance" -> "perf";
            case "keybinding", "keybindings" -> "keybind";
            case "alias", "aliases" -> "commands";
            default -> topic;
        };
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String hex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static Color blend(Color first, Color second, double ratio) {
        return new Color((int) Math.round(first.getRed() * (1 - ratio) + second.getRed() * ratio),
            (int) Math.round(first.getGreen() * (1 - ratio) + second.getGreen() * ratio),
            (int) Math.round(first.getBlue() * (1 - ratio) + second.getBlue() * ratio));
    }

    private final class TopicRenderer extends DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                 boolean selected, boolean focused) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focused);
            if (value instanceof Topic topic) {
                boolean showGroup = index == 0 || !topic.group().equals(topicModel.get(index - 1).group());
                label.setText(showGroup ? "<html><small>" + escape(topic.group()) + "</small><br>" + escape(topic.title()) + "</html>"
                    : topic.title());
                label.setBorder(BorderFactory.createEmptyBorder(showGroup ? 9 : 5, 12, 5, 8));
                label.setToolTipText(topic.summary());
            }
            return label;
        }
    }

    private static final String INTRO_SEARCH = "open file edit save find command palette Vim Emacs local desktop";
    private static final String SETTINGS_SEARCH = "config.toml .shed.toml reload set inspector reference keymap";
}
