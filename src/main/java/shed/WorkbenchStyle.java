package shed;

import java.awt.*;
import java.awt.geom.Path2D;
import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.*;
import javax.swing.plaf.basic.*;

/** Shared Swing delegates and metrics. Uses the active ConfigManager palette, not a second theme system. */
public final class WorkbenchStyle {
    private WorkbenchStyle() { }

    static void install(ConfigManager config) {
        UIManager.put("shed.zoom", config.getUiZoom());
        color("shed.surface", config.getNormalColor());
        color("shed.chrome", config.getStatusBarBackground());
        color("shed.raised", config.getRaisedSurfaceColor());
        color("shed.field", config.getFloatingSurfaceColor());
        color("shed.border", config.getSubtleBorderColor());
        color("shed.muted", config.getMutedForegroundColor());
        color("shed.focus", config.getFocusColor());
        color("shed.foreground", config.getEditorForeground());
        color("shed.selection", config.getSelectionColor());
        UIManager.put("ButtonUI", ButtonUI.class.getName());
        UIManager.put("ToggleButtonUI", ButtonUI.class.getName());
        UIManager.put("CheckBoxUI", CheckUI.class.getName());
        UIManager.put("RadioButtonUI", RadioUI.class.getName());
        UIManager.put("ComboBoxUI", ComboUI.class.getName());
        UIManager.put("ScrollBarUI", ScrollUI.class.getName());
        UIManager.put("TabbedPaneUI", TabsUI.class.getName());
        UIManager.put("SplitPaneUI", SplitUI.class.getName());
        UIManager.put("ListUI", ListUI.class.getName());
        UIManager.put("TableUI", TableUI.class.getName());
        UIManager.put("TreeUI", "javax.swing.plaf.basic.BasicTreeUI");
        UIManager.put("FileChooserUI", "javax.swing.plaf.metal.MetalFileChooserUI");
        UIManager.put("Menu.arrowIcon", new DisclosureIcon(false));
        UIManager.put("MenuBar.border", new BorderUIResource.EmptyBorderUIResource(0, 0, 1, 0));
        for (String type : new String[] {"Label", "TextArea", "TextPane", "EditorPane", "TextField", "PasswordField", "FormattedTextField", "TableHeader",
            "ProgressBar", "Spinner", "Slider", "ToolTip", "Separator", "PopupMenuSeparator", "PopupMenu",
            "MenuItem", "CheckBoxMenuItem", "RadioButtonMenuItem"}) {
            UIManager.put(type + "UI", "javax.swing.plaf.basic.Basic" + type + "UI");
        }
        for (String type : new String[] {"TextField", "PasswordField", "FormattedTextField", "ComboBox", "Spinner"}) {
            UIManager.put(type + ".border", new FieldBorder());
            color(type + ".inactiveForeground", config.getMutedForegroundColor());
        }
        UIManager.put("Button.border", padding(8, 12));
        UIManager.put("ToggleButton.border", padding(8, 12));
        UIManager.put("CheckBox.border", padding(8, 4));
        UIManager.put("RadioButton.border", padding(8, 4));
        UIManager.put("CheckBox.icon", new ChoiceIcon(false));
        UIManager.put("RadioButton.icon", new ChoiceIcon(true));
        UIManager.put("CheckBoxMenuItem.checkIcon", new ChoiceIcon(false));
        UIManager.put("RadioButtonMenuItem.checkIcon", new ChoiceIcon(true));
        UIManager.put("ScrollPane.border", new BorderUIResource.EmptyBorderUIResource(0, 0, 0, 0));
        UIManager.put("SplitPane.border", new BorderUIResource.EmptyBorderUIResource(0, 0, 0, 0));
        UIManager.put("SplitPane.dividerSize", px(6));
        UIManager.put("TableHeader.cellBorder", new BorderUIResource.CompoundBorderUIResource(
            new BorderUIResource.MatteBorderUIResource(0, 0, 1, 0, config.getSubtleBorderColor()), padding(8, 10)));
        UIManager.put("List.cellNoFocusBorder", padding(8, 10));
        UIManager.put("List.focusCellHighlightBorder", new SelectionBorder());
        UIManager.put("Table.focusCellHighlightBorder", new SelectionBorder());
        UIManager.put("Tree.drawsFocusBorderAroundIcon", false);
        UIManager.put("Tree.hash", config.getSubtleBorderColor());
        UIManager.put("Tree.textBackground", config.getFloatingSurfaceColor());
        UIManager.put("Tree.textForeground", config.getEditorForeground());
        UIManager.put("Tree.selectionBackground", config.getSelectionColor());
        UIManager.put("Tree.selectionForeground", config.getSelectionTextColor());
        UIManager.put("Tree.selectionBorderColor", config.getFocusColor());
        UIManager.put("Tree.expandedIcon", new DisclosureIcon(true));
        UIManager.put("Tree.collapsedIcon", new DisclosureIcon(false));
        UIManager.put("TitledBorder.border", new BorderUIResource.MatteBorderUIResource(1, 0, 0, 0, config.getSubtleBorderColor()));
        UIManager.put("PopupMenu.border", new BorderUIResource.CompoundBorderUIResource(
            new BorderUIResource.LineBorderUIResource(config.getSubtleBorderColor()), padding(4, 4)));
        UIManager.put("ToolTip.border", new BorderUIResource.CompoundBorderUIResource(
            new BorderUIResource.LineBorderUIResource(config.getSubtleBorderColor()), padding(6, 8)));
        color("ToolTip.background", config.getRaisedSurfaceColor());
        color("CheckBox.background", config.getNormalColor());
        color("RadioButton.background", config.getNormalColor());
        color("CheckBox.disabledText", config.getMutedForegroundColor());
        color("RadioButton.disabledText", config.getMutedForegroundColor());
        color("ComboBox.disabledForeground", config.getMutedForegroundColor());
        color("ComboBox.disabledBackground", config.getRaisedSurfaceColor());
        color("controlHighlight", config.getRaisedSurfaceColor());
        color("controlLtHighlight", config.getRaisedSurfaceColor());
        color("Spinner.arrowButtonBackground", config.getRaisedSurfaceColor());
        color("ProgressBar.selectionForeground", config.getEditorForeground());
        color("ProgressBar.selectionBackground", config.getSelectionTextColor());
        for (String type : new String[] {"Menu", "MenuItem", "CheckBoxMenuItem", "RadioButtonMenuItem"}) {
            UIManager.put(type + ".border", padding(7, 10));
            color(type + ".disabledForeground", config.getMutedForegroundColor());
            color(type + ".acceleratorForeground", config.getMutedForegroundColor());
            color(type + ".acceleratorSelectionForeground", config.getSelectionTextColor());
        }
    }

    static int px(int value) {
        Object zoom = UIManager.get("shed.zoom");
        return UiZoom.scale(value, zoom instanceof Number n ? n.doubleValue() : 1.0);
    }

    private static void color(String key, Color color) { UIManager.put(key, new ColorUIResource(color)); }
    private static Color color(String key) { return UIManager.getColor("shed." + key); }
    private static BorderUIResource.EmptyBorderUIResource padding(int vertical, int horizontal) {
        return new BorderUIResource.EmptyBorderUIResource(px(vertical), px(horizontal), px(vertical), px(horizontal));
    }
    private static Graphics2D brush(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        return g;
    }
    static void quiet(JButton button) { button.putClientProperty("shed.quiet", true); }

    static void emptyText(JComponent component, String text) {
        component.putClientProperty("shed.emptyText", text);
        component.getAccessibleContext().setAccessibleDescription(text);
    }

    private static void paintEmpty(Graphics g, JComponent component) {
        String text = component.getClientProperty("shed.emptyText") instanceof String value ? value : "No items to display";
        g.setFont(component.getFont()); g.setColor(color("muted"));
        FontMetrics metrics = g.getFontMetrics();
        // Keep guidance readable even in a narrow dock; the full text is accessible to assistive tools.
        int available = component.getWidth() - px(32);
        if (available <= 0) return;
        if (metrics.stringWidth(text) > available) {
            while (!text.isEmpty() && metrics.stringWidth(text + "…") > available) text = text.substring(0, text.length() - 1);
            text += "…";
        }
        g.drawString(text, Math.max(px(16), (component.getWidth() - metrics.stringWidth(text)) / 2), px(28) + metrics.getAscent());
    }

    public static class ListUI extends BasicListUI {
        public static ComponentUI createUI(JComponent c) { return new ListUI(); }
        @Override public void paint(Graphics g, JComponent c) {
            super.paint(g, c); if (((JList<?>) c).getModel().getSize() == 0) paintEmpty(g, c);
        }
    }
    public static class TableUI extends BasicTableUI {
        public static ComponentUI createUI(JComponent c) { return new TableUI(); }
        @Override public void paint(Graphics g, JComponent c) {
            super.paint(g, c); if (((JTable) c).getRowCount() == 0) paintEmpty(g, c);
        }
    }
    private record DisclosureIcon(boolean expanded) implements Icon, UIResource {
        public int getIconWidth() { return px(16); }
        public int getIconHeight() { return px(16); }
        public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = brush(graphics); g.setColor(color("muted")); g.translate(x, y);
            g.setStroke(new BasicStroke(Math.max(1f, px(1)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D path = new Path2D.Float();
            if (expanded) { path.moveTo(px(4), px(6)); path.lineTo(px(8), px(10)); path.lineTo(px(12), px(6)); }
            else { path.moveTo(px(6), px(4)); path.lineTo(px(10), px(8)); path.lineTo(px(6), px(12)); }
            g.draw(path); g.dispose();
        }
    }

    /** Padding and focus are painted by a border so text editing and native key actions remain Swing's. */
    static final class FieldBorder extends AbstractBorder implements UIResource {
        @Override public Insets getBorderInsets(Component c) { return getBorderInsets(c, new Insets(0, 0, 0, 0)); }
        @Override public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(px(10), px(10), px(10), px(10));
            return insets;
        }
        @Override public void paintBorder(Component c, Graphics graphics, int x, int y, int width, int height) {
            Graphics2D g = brush(graphics);
            Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            boolean focused = c.isFocusOwner() || c instanceof Container parent && focus != null && SwingUtilities.isDescendingFrom(focus, parent);
            g.setColor(color(focused ? "focus" : "border"));
            g.setStroke(new BasicStroke(focused ? px(2) : 1));
            g.drawRoundRect(x + 1, y + 1, Math.max(0, width - 3), Math.max(0, height - 3), px(6), px(6));
            g.dispose();
        }
    }

    static final class SelectionBorder extends AbstractBorder implements UIResource {
        @Override public Insets getBorderInsets(Component c) { return getBorderInsets(c, new Insets(0, 0, 0, 0)); }
        @Override public Insets getBorderInsets(Component c, Insets insets) { insets.set(px(8), px(10), px(8), px(10)); return insets; }
        @Override public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            g.setColor(color("focus")); g.drawRect(x, y, width - 1, height - 1);
        }
    }

    public static class ButtonUI extends BasicButtonUI {
        public static ComponentUI createUI(JComponent c) { return new ButtonUI(); }
        @Override protected void installDefaults(AbstractButton b) {
            super.installDefaults(b);
            LookAndFeel.installProperty(b, "opaque", false);
            LookAndFeel.installProperty(b, "rolloverEnabled", true);
        }
        @Override public Dimension getPreferredSize(JComponent c) {
            Dimension size = super.getPreferredSize(c);
            if (size != null) { size.height = Math.max(px(40), size.height); size.width = Math.max(px(40), size.width); }
            return size;
        }
        @Override public void update(Graphics graphics, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            ButtonModel model = b.getModel();
            boolean quiet = Boolean.TRUE.equals(b.getClientProperty("shed.quiet"));
            Graphics2D g = brush(graphics);
            Color fill = color("raised");
            if (model.isPressed() && model.isArmed() || model.isSelected()) fill = color("selection");
            else if (model.isRollover() && b.isEnabled()) fill = mix(fill, color("foreground"), .08);
            if (b.isContentAreaFilled() && (!quiet || model.isRollover() || model.isPressed() || model.isSelected())) {
                g.setColor(fill); g.fillRoundRect(1, 1, c.getWidth() - 2, c.getHeight() - 2, px(6), px(6));
            }
            boolean focused = b.isFocusPainted() && b.hasFocus();
            boolean primary = b instanceof JButton button && button.isDefaultButton();
            if (focused || primary || !quiet && b.isBorderPainted()) {
                g.setColor(color(focused || primary ? "focus" : "border"));
                g.setStroke(new BasicStroke(focused ? px(2) : 1));
                g.drawRoundRect(1, 1, c.getWidth() - 3, c.getHeight() - 3, px(6), px(6));
            }
            g.dispose();
            super.paint(graphics, c);
        }
        @Override protected void paintButtonPressed(Graphics g, AbstractButton b) { }
        @Override protected void paintFocus(Graphics g, AbstractButton b, Rectangle view, Rectangle text, Rectangle icon) { }
        @Override protected void paintText(Graphics g, JComponent c, Rectangle bounds, String text) {
            AbstractButton b = (AbstractButton) c;
            g.setColor(b.isEnabled() ? b.getForeground() : color("muted"));
            BasicGraphicsUtils.drawStringUnderlineCharAt(g, text, b.getDisplayedMnemonicIndex(), bounds.x,
                bounds.y + g.getFontMetrics().getAscent());
        }
    }

    public static class CheckUI extends BasicCheckBoxUI {
        public static ComponentUI createUI(JComponent c) { return new CheckUI(); }
        @Override public Dimension getPreferredSize(JComponent c) {
            Dimension size = super.getPreferredSize(c); size.height = Math.max(px(40), size.height); return size;
        }
    }
    public static class RadioUI extends BasicRadioButtonUI {
        public static ComponentUI createUI(JComponent c) { return new RadioUI(); }
        @Override public Dimension getPreferredSize(JComponent c) {
            Dimension size = super.getPreferredSize(c); size.height = Math.max(px(40), size.height); return size;
        }
    }
    private record ChoiceIcon(boolean radio) implements Icon, UIResource {
        public int getIconWidth() { return px(16); }
        public int getIconHeight() { return px(16); }
        public void paintIcon(Component c, Graphics graphics, int x, int y) {
            AbstractButton b = (AbstractButton) c;
            Graphics2D g = brush(graphics); g.translate(x, y);
            int size = getIconWidth() - 2;
            g.setColor(color(b.isSelected() ? "selection" : "field"));
            if (radio) g.fillOval(1, 1, size, size); else g.fillRoundRect(1, 1, size, size, px(4), px(4));
            g.setColor(color(b.isEnabled() ? (b.isSelected() || b.isFocusOwner() ? "focus" : "muted") : "border"));
            g.setStroke(new BasicStroke(Math.max(1f, px(1))));
            if (radio) g.drawOval(1, 1, size, size); else g.drawRoundRect(1, 1, size, size, px(4), px(4));
            if (b.isSelected()) {
                g.setColor(color(b.isEnabled() ? "foreground" : "muted"));
                if (radio) g.fillOval(px(5), px(5), px(6), px(6));
                else { g.setStroke(new BasicStroke(px(2), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    Path2D path = new Path2D.Float(); path.moveTo(px(4), px(8)); path.lineTo(px(7), px(11)); path.lineTo(px(12), px(5)); g.draw(path); }
            }
            g.dispose();
        }
    }

    public static class ComboUI extends BasicComboBoxUI {
        public static ComponentUI createUI(JComponent c) { return new ComboUI(); }
        @Override protected JButton createArrowButton() {
            JButton arrow = new JButton() {
                @Override protected void paintComponent(Graphics graphics) {
                    Graphics2D g = brush(graphics);
                    g.setColor(color(isEnabled() ? "muted" : "border"));
                    g.setStroke(new BasicStroke(Math.max(1f, px(1)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    int x = getWidth() / 2, y = getHeight() / 2;
                    g.drawLine(x - px(4), y - px(2), x, y + px(2)); g.drawLine(x, y + px(2), x + px(4), y - px(2));
                    g.dispose();
                }
            };
            arrow.setName("ComboBox.arrowButton"); arrow.setFocusable(false); arrow.setBorder(BorderFactory.createEmptyBorder());
            return arrow;
        }
        @Override public Dimension getMinimumSize(JComponent c) {
            Dimension size = super.getMinimumSize(c); size.height = Math.max(px(40), size.height); return size;
        }
        @Override public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean focus) {
            g.setColor(comboBox.getBackground()); g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }
    }

    public static class ScrollUI extends BasicScrollBarUI {
        public static ComponentUI createUI(JComponent c) { return new ScrollUI(); }
        @Override protected void configureScrollBarColors() { trackColor = color("surface"); thumbColor = color("border"); }
        @Override public Dimension getPreferredSize(JComponent c) { return new Dimension(px(10), px(10)); }
        @Override protected JButton createDecreaseButton(int orientation) { return emptyButton(); }
        @Override protected JButton createIncreaseButton(int orientation) { return emptyButton(); }
        private JButton emptyButton() {
            JButton b = new JButton(); b.setFocusable(false); b.setRequestFocusEnabled(false);
            b.setPreferredSize(new Dimension(0, 0)); b.setMinimumSize(new Dimension(0, 0)); b.setMaximumSize(new Dimension(0, 0)); return b;
        }
        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) { g.setColor(c.getBackground()); g.fillRect(r.x, r.y, r.width, r.height); }
        @Override protected void paintThumb(Graphics graphics, JComponent c, Rectangle r) {
            if (!c.isEnabled() || r.isEmpty()) return;
            Graphics2D g = brush(graphics); g.setColor(color(isDragging || isThumbRollover() ? "muted" : "border"));
            g.fillRoundRect(r.x + 2, r.y + 2, Math.max(2, r.width - 4), Math.max(2, r.height - 4), px(6), px(6)); g.dispose();
        }
    }

    public static class TabsUI extends BasicTabbedPaneUI {
        public static ComponentUI createUI(JComponent c) { return new TabsUI(); }
        @Override protected void installDefaults() {
            super.installDefaults(); tabInsets = new Insets(px(8), px(14), px(8), px(14));
            tabAreaInsets = new Insets(0, 0, 0, 0); contentBorderInsets = new Insets(1, 0, 0, 0);
            selectedTabPadInsets = new Insets(0, 0, 0, 0);
        }
        @Override protected int calculateTabHeight(int placement, int index, int height) { return Math.max(px(40), super.calculateTabHeight(placement, index, height)); }
        @Override protected int getTabLabelShiftX(int placement, int index, boolean selected) { return 0; }
        @Override protected int getTabLabelShiftY(int placement, int index, boolean selected) { return 0; }
        @Override protected void paintTabBackground(Graphics g, int placement, int index, int x, int y, int w, int h, boolean selected) {
            g.setColor(color(selected ? "surface" : getRolloverTab() == index ? "raised" : "chrome")); g.fillRect(x, y, w, h);
        }
        @Override protected void paintTabBorder(Graphics g, int placement, int index, int x, int y, int w, int h, boolean selected) {
            if (selected) { g.setColor(color("focus"));
                if (placement == LEFT || placement == RIGHT) g.fillRect(x, y, px(2), h);
                else g.fillRect(x, placement == BOTTOM ? y : y + h - px(2), w, px(2)); }
        }
        @Override protected void paintFocusIndicator(Graphics g, int placement, Rectangle[] rects, int index, Rectangle icon, Rectangle text, boolean selected) {
            if (tabPane.hasFocus() && selected) { Rectangle r = rects[index]; g.setColor(color("focus")); g.drawRect(r.x + 3, r.y + 3, r.width - 7, r.height - 7); }
        }
        @Override protected void paintContentBorder(Graphics g, int placement, int selected) { }
    }

    public static class SplitUI extends BasicSplitPaneUI {
        public static ComponentUI createUI(JComponent c) { return new SplitUI(); }
        @Override public BasicSplitPaneDivider createDefaultDivider() {
            return new BasicSplitPaneDivider(this) {
                @Override public void paint(Graphics g) {
                    g.setColor(color("chrome")); g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(color("border"));
                    if (orientation == JSplitPane.HORIZONTAL_SPLIT) g.drawLine(getWidth() / 2, 0, getWidth() / 2, getHeight());
                    else g.drawLine(0, getHeight() / 2, getWidth(), getHeight() / 2);
                    super.paintComponents(g);
                }
            };
        }
    }

    private static Color mix(Color a, Color b, double ratio) {
        return new Color((int) (a.getRed() * (1 - ratio) + b.getRed() * ratio),
            (int) (a.getGreen() * (1 - ratio) + b.getGreen() * ratio), (int) (a.getBlue() * (1 - ratio) + b.getBlue() * ratio));
    }
}
