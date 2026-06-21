package shed;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;

final class DramaticUiController {
    private final Texteditor editor;

    DramaticUiController(Texteditor editor) {
        this.editor = editor;
    }

    public String toggleMinimap() {
        EditorPane pane = editor.getActivePane();
        if (pane == null) return "No active pane";
        JScrollPane sp = pane.getScrollPane();
        if (editor.activeMinimapPanel != null && editor.activeMinimapPanel.getParent() != null) {
            MinimapPanel panelToRemove = editor.activeMinimapPanel;
            java.awt.Container parent = panelToRemove.getParent();
            Runnable removePanel = () -> {
                if (parent != null) {
                    parent.remove(panelToRemove);
                    parent.revalidate();
                    parent.repaint();
                }
                if (editor.activeMinimapPanel == panelToRemove) {
                    editor.activeMinimapPanel = null;
                }
                sp.revalidate();
                sp.repaint();
            };
            animateMinimapWidth(panelToRemove, panelToRemove.getPixelWidth(), 0, removePanel);
            animateEditorHostTint(editor.configManager.getCommandColor());
            return "Minimap hidden";
        }
        editor.activeMinimapPanel = new MinimapPanel(pane.getTextArea());
        editor.activeMinimapPanel.setColors(editor.configManager.getLineNumberBackground(), editor.configManager.getEditorForeground());
        int initialWidth = editor.dramaticPanelAnimationsEnabled && dramaticMotionAllowed() ? 0 : editor.dramaticMinimapWidth;
        editor.activeMinimapPanel.setPixelWidth(initialWidth);
        java.awt.Container parent = sp.getParent();
        if (parent instanceof JPanel) {
            ((JPanel) parent).add(editor.activeMinimapPanel, BorderLayout.EAST);
        } else {
            // wrap the scroll pane
            JPanel wrapper = new JPanel(new BorderLayout());
            if (parent != null) {
                int idx = -1;
                for (int i = 0; i < parent.getComponentCount(); i++) {
                    if (parent.getComponent(i) == sp) { idx = i; break; }
                }
                if (idx >= 0) parent.remove(idx);
                wrapper.add(sp, BorderLayout.CENTER);
                wrapper.add(editor.activeMinimapPanel, BorderLayout.EAST);
                if (idx >= 0) parent.add(wrapper, idx);
            }
        }
        sp.getViewport().addChangeListener(e -> { if (editor.activeMinimapPanel != null) editor.activeMinimapPanel.repaint(); });
        sp.revalidate();
        sp.repaint();
        animateMinimapWidth(editor.activeMinimapPanel, initialWidth, editor.dramaticMinimapWidth, null);
        animateEditorHostTint(editor.configManager.getVisualColor());
        return "Minimap shown";
    }


    public String toggleZenMode() {
        editor.zenModeEnabled = !editor.zenModeEnabled;
        updateZenModeLayout();
        return editor.zenModeEnabled ? "Zen mode enabled" : "Zen mode disabled";
    }


    void updateZenModeLayout() {
        Color editorBackground = editor.getModeBackground(editor.editorState.mode == null ? EditorMode.NORMAL : editor.editorState.mode);
        Color marginBackground = editor.zenModeEnabled ? fadedMarginColor(editorBackground) : editorBackground;
        editor.editorHostPanel.setBackground(marginBackground);
        editor.editorHostPanel.setOpaque(true);

        for (EditorPane pane : editor.editorPanes) {
            JScrollPane scrollPane = pane.getScrollPane();
            JTextArea area = pane.getTextArea();
            area.setBackground(editorBackground);
            scrollPane.setOpaque(true);
            scrollPane.getViewport().setOpaque(true);
            scrollPane.setBackground(marginBackground);
            scrollPane.getViewport().setBackground(editorBackground);
            if (!editor.zenModeEnabled) {
                scrollPane.setBorder(null);
                continue;
            }
            int width = editor.getWidth();
            int desired = editor.configManager.getZenModeWidth() * Math.max(8, area.getFontMetrics(area.getFont()).charWidth('M'));
            int horizontalPadding = Math.max(12, (width - desired) / 2);
            scrollPane.setBorder(BorderFactory.createEmptyBorder(0, horizontalPadding, 0, horizontalPadding));
        }
        editor.editorHostPanel.revalidate();
        editor.editorHostPanel.repaint();
    }


    Color fadedMarginColor(Color base) {
        return blendColor(base, editor.configManager.getEditorForeground(), 0.12);
    }


    Color blendColor(Color base, Color overlay, double ratio) {
        double clamped = Math.max(0.0, Math.min(1.0, ratio));
        int r = (int) Math.round(base.getRed() * (1.0 - clamped) + overlay.getRed() * clamped);
        int g = (int) Math.round(base.getGreen() * (1.0 - clamped) + overlay.getGreen() * clamped);
        int b = (int) Math.round(base.getBlue() * (1.0 - clamped) + overlay.getBlue() * clamped);
        return new Color(r, g, b);
    }


    void refreshDramaticSettings() {
        boolean wasEnabled = editor.dramaticUiEnabled;
        editor.dramaticUiEnabled = editor.configManager.getDramaticUiEnabled();
        editor.dramaticIdentityEnabled = editor.dramaticUiEnabled && editor.configManager.getDramaticIdentityEnabled();
        editor.dramaticModeTransitionsEnabled = editor.dramaticUiEnabled && editor.configManager.getDramaticModeTransitionsEnabled();
        editor.dramaticCommandPaletteEnabled = editor.dramaticUiEnabled && editor.configManager.getDramaticCommandPaletteEnabled();
        editor.dramaticEditingFeedbackEnabled = editor.dramaticUiEnabled && editor.configManager.getDramaticEditingFeedbackEnabled();
        editor.dramaticPanelAnimationsEnabled = editor.dramaticUiEnabled && editor.configManager.getDramaticPanelAnimationsEnabled();
        editor.dramaticSoundEnabled = editor.dramaticUiEnabled && editor.configManager.getDramaticSoundEnabled();
        editor.dramaticSoundPack = editor.configManager.getDramaticSoundPack();
        editor.dramaticSoundVolume = editor.configManager.getDramaticSoundVolume();
        editor.dramaticSoundModeCueEnabled = editor.configManager.getDramaticSoundModeCueEnabled();
        editor.dramaticSoundNavigateCueEnabled = editor.configManager.getDramaticSoundNavigateCueEnabled();
        editor.dramaticSoundSuccessCueEnabled = editor.configManager.getDramaticSoundSuccessCueEnabled();
        editor.dramaticSoundErrorCueEnabled = editor.configManager.getDramaticSoundErrorCueEnabled();
        editor.dramaticReducedMotionEnabled = editor.configManager.getDramaticReducedMotionEnabled();
        editor.dramaticPerformanceGuardrailsEnabled = editor.configManager.getDramaticPerformanceGuardrailsEnabled();
        editor.dramaticPerformanceCpuThreshold = Math.max(0.1, Math.min(1.0, editor.configManager.getDramaticPerformanceCpuThreshold()));
        editor.dramaticPerformanceLineThreshold = editor.configManager.getDramaticPerformanceLineThreshold();
        editor.dramaticAnimationMs = Math.max(80, editor.configManager.getDramaticAnimationMs());
        editor.dramaticMinimapWidth = Math.max(40, editor.configManager.getDramaticMinimapWidth());
        editor.whichKeyHintsEnabled = editor.configManager.getWhichKeyHintsEnabled();
        if (wasEnabled && !editor.dramaticUiEnabled) {
            if (editor.modeTransitionTimer != null) editor.modeTransitionTimer.stop();
            if (editor.feedbackPulseTimer != null) editor.feedbackPulseTimer.stop();
            if (editor.hostTintTimer != null) editor.hostTintTimer.stop();
            if (editor.splitAnimationTimer != null) editor.splitAnimationTimer.stop();
            if (editor.minimapWidthTimer != null) editor.minimapWidthTimer.stop();
            editor.modeTransitionTimer = null;
            editor.feedbackPulseTimer = null;
            editor.hostTintTimer = null;
            editor.splitAnimationTimer = null;
            editor.minimapWidthTimer = null;
            clearFeedbackPulse();
        }
    }


    boolean dramaticMotionAllowed() {
        return editor.dramaticUiEnabled
            && !editor.dramaticReducedMotionEnabled
            && editor.dramaticAnimationMs > 0
            && !isDramaticPerformanceThrottled();
    }


    boolean isDramaticPerformanceThrottled() {
        if (!editor.dramaticPerformanceGuardrailsEnabled) {
            return false;
        }
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer != null && buffer.isLargeFile()) {
            return true;
        }
        if (editor.writingArea != null && editor.writingArea.getLineCount() >= editor.dramaticPerformanceLineThreshold) {
            return true;
        }
        double cpuLoad = cachedProcessCpuLoad();
        return cpuLoad >= 0.0 && cpuLoad >= editor.dramaticPerformanceCpuThreshold;
    }


    double cachedProcessCpuLoad() {
        long now = System.currentTimeMillis();
        if (now - editor.cachedProcessCpuLoadAtMillis < 1200) {
            return editor.cachedProcessCpuLoad;
        }
        editor.cachedProcessCpuLoadAtMillis = now;
        editor.cachedProcessCpuLoad = readProcessCpuLoad();
        return editor.cachedProcessCpuLoad;
    }


    double readProcessCpuLoad() {
        try {
            Object osBean = ManagementFactory.getOperatingSystemMXBean();
            Method method = osBean.getClass().getMethod("getProcessCpuLoad");
            method.setAccessible(true);
            Object value = method.invoke(osBean);
            if (value instanceof Number) {
                double load = ((Number) value).doubleValue();
                if (load >= 0.0 && load <= 1.0) {
                    return load;
                }
            }
        } catch (Exception ignored) {
        }
        return -1.0;
    }


    int animationDelayForSteps(int steps) {
        return Math.max(12, editor.dramaticAnimationMs / Math.max(1, steps));
    }


    double easeOut(double t) {
        double clamped = Math.max(0.0, Math.min(1.0, t));
        double inverse = 1.0 - clamped;
        return 1.0 - inverse * inverse * inverse;
    }


    void applyDramaticFooterStyling() {
        if (editor.statusBar == null || editor.commandBar == null || editor.editorState == null) {
            return;
        }
        Color baseStatus = editor.configManager.getStatusBarBackground();
        Color baseCommand = editor.configManager.getCommandBarBackground();
        Color modeAccent = editor.getModeBackground(editor.editorState.mode == null ? EditorMode.NORMAL : editor.editorState.mode);

        if (editor.dramaticIdentityEnabled) {
            Color statusBg = blendColor(baseStatus, modeAccent, 0.20);
            Color commandBg = blendColor(baseCommand, modeAccent, 0.15);
            editor.statusBar.setBackground(statusBg);
            editor.commandBar.setBackground(commandBg);
            editor.statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 4, 0, 0, modeAccent),
                BorderFactory.createEmptyBorder(5, 8, 5, 10)
            ));
            editor.commandBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, blendColor(modeAccent, editor.configManager.getEditorForeground(), 0.45)),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)
            ));
            if (editor.writingArea != null) {
                Font baseFont = editor.writingArea.getFont();
                editor.statusBar.setFont(baseFont.deriveFont(Font.BOLD, baseFont.getSize2D() + 1.0f));
                editor.commandBar.setFont(baseFont.deriveFont(Font.PLAIN, baseFont.getSize2D()));
            }
            return;
        }

        editor.statusBar.setBackground(baseStatus);
        editor.commandBar.setBackground(baseCommand);
        editor.statusBar.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        editor.commandBar.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        if (editor.writingArea != null) {
            Font baseFont = editor.writingArea.getFont();
            editor.statusBar.setFont(baseFont.deriveFont(Font.PLAIN, baseFont.getSize2D()));
            editor.commandBar.setFont(baseFont.deriveFont(Font.PLAIN, baseFont.getSize2D()));
        }
    }


    void animateModeTransition(EditorMode fromMode, EditorMode toMode) {
        if (!editor.dramaticModeTransitionsEnabled) {
            return;
        }
        if (fromMode == toMode || editor.writingArea == null) {
            return;
        }
        playCue(CueType.MODE_CHANGE);
        Color fromColor = editor.getModeBackground(fromMode == null ? toMode : fromMode);
        Color toColor = editor.getModeBackground(toMode);
        if (!dramaticMotionAllowed()) {
            editor.writingArea.setBackground(toColor);
            applyDramaticFooterStyling();
            return;
        }

        if (editor.modeTransitionTimer != null) {
            editor.modeTransitionTimer.stop();
        }

        int steps = Math.max(6, Math.min(20, editor.dramaticAnimationMs / 14));
        final int[] tick = new int[] {0};
        editor.modeTransitionTimer = new Timer(animationDelayForSteps(steps), ev -> {
            double t = easeOut((double) tick[0] / steps);
            Color blended = blendColor(fromColor, toColor, t);
            editor.writingArea.setBackground(blended);
            if (editor.editorHostPanel != null) {
                editor.editorHostPanel.setBackground(editor.zenModeEnabled ? fadedMarginColor(blended) : blended);
                editor.editorHostPanel.repaint();
            }
            tick[0]++;
            if (tick[0] > steps) {
                editor.modeTransitionTimer.stop();
                editor.modeTransitionTimer = null;
                updateZenModeLayout();
                applyDramaticFooterStyling();
            }
        });
        editor.modeTransitionTimer.start();
    }


    void clearFeedbackPulse() {
        if (editor.feedbackPulseTag != null && editor.writingArea != null) {
            editor.writingArea.getHighlighter().removeHighlight(editor.feedbackPulseTag);
            editor.feedbackPulseTag = null;
        }
    }


    void pulseCaretLine(Color color) {
        if (!editor.dramaticEditingFeedbackEnabled || editor.writingArea == null) {
            return;
        }
        if (editor.feedbackPulseTimer != null) {
            editor.feedbackPulseTimer.stop();
        }
        clearFeedbackPulse();

        int line;
        int start;
        int end;
        try {
            line = Math.max(0, editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition()));
            start = editor.writingArea.getLineStartOffset(line);
            end = editor.writingArea.getLineEndOffset(line);
        } catch (BadLocationException e) {
            return;
        }

        if (!dramaticMotionAllowed()) {
            try {
                editor.feedbackPulseTag = editor.writingArea.getHighlighter().addHighlight(start, end, new DefaultHighlighter.DefaultHighlightPainter(new Color(color.getRed(), color.getGreen(), color.getBlue(), 80)));
            } catch (BadLocationException ignored) {
                return;
            }
            Timer cleanup = new Timer(140, ev -> {
                clearFeedbackPulse();
                ((Timer) ev.getSource()).stop();
            });
            cleanup.setRepeats(false);
            cleanup.start();
            return;
        }

        int steps = Math.max(5, Math.min(14, editor.dramaticAnimationMs / 18));
        final int[] tick = new int[] {0};
        editor.feedbackPulseTimer = new Timer(animationDelayForSteps(steps), ev -> {
            clearFeedbackPulse();
            double progress = (double) tick[0] / steps;
            int alpha = (int) Math.round(130 * (1.0 - progress));
            alpha = Math.max(0, Math.min(255, alpha));
            try {
                editor.feedbackPulseTag = editor.writingArea.getHighlighter().addHighlight(
                    start,
                    end,
                    new DefaultHighlighter.DefaultHighlightPainter(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha))
                );
            } catch (BadLocationException ignored) {
            }
            tick[0]++;
            if (tick[0] > steps) {
                editor.feedbackPulseTimer.stop();
                editor.feedbackPulseTimer = null;
                clearFeedbackPulse();
            }
        });
        editor.feedbackPulseTimer.start();
    }


    void animateEditorHostTint(Color tint) {
        if (!editor.dramaticPanelAnimationsEnabled || editor.editorHostPanel == null || tint == null) {
            return;
        }
        if (editor.hostTintTimer != null) {
            editor.hostTintTimer.stop();
        }
        if (!dramaticMotionAllowed()) {
            editor.editorHostPanel.setBackground(blendColor(editor.editorHostPanel.getBackground(), tint, 0.20));
            editor.editorHostPanel.repaint();
            return;
        }

        final Color base = editor.editorHostPanel.getBackground();
        int steps = Math.max(6, Math.min(20, editor.dramaticAnimationMs / 14));
        final int[] tick = new int[] {0};
        editor.hostTintTimer = new Timer(animationDelayForSteps(steps), ev -> {
            double progress = (double) tick[0] / steps;
            double ratio = 0.30 * (1.0 - progress);
            editor.editorHostPanel.setBackground(blendColor(base, tint, ratio));
            editor.editorHostPanel.repaint();
            tick[0]++;
            if (tick[0] > steps) {
                editor.hostTintTimer.stop();
                editor.hostTintTimer = null;
                updateZenModeLayout();
            }
        });
        editor.hostTintTimer.start();
    }


    void animateSplitForPane(EditorPane pane, double startRatio, double targetRatio) {
        if (!editor.dramaticPanelAnimationsEnabled || pane == null || editor.windowLayoutRoot == null) {
            return;
        }
        if (!dramaticMotionAllowed()) {
            return;
        }
        if (editor.splitAnimationTimer != null) {
            editor.splitAnimationTimer.stop();
        }

        int steps = Math.max(5, Math.min(16, editor.dramaticAnimationMs / 16));
        final int[] tick = new int[] {0};
        final double delta = (targetRatio - startRatio) / Math.max(1, steps);
        editor.splitAnimationTimer = new Timer(animationDelayForSteps(steps), ev -> {
            boolean changed = editor.windowLayoutRoot.adjustRatio(pane, delta);
            if (changed) {
                editor.renderWindowLayout();
            }
            tick[0]++;
            if (tick[0] > steps || !changed) {
                editor.splitAnimationTimer.stop();
                editor.splitAnimationTimer = null;
            }
        });
        editor.splitAnimationTimer.start();
    }


    void animateMinimapWidth(MinimapPanel panel, int fromWidth, int toWidth, Runnable onFinish) {
        if (panel == null) {
            if (onFinish != null) {
                onFinish.run();
            }
            return;
        }
        if (editor.minimapWidthTimer != null) {
            editor.minimapWidthTimer.stop();
        }
        if (!editor.dramaticPanelAnimationsEnabled || !dramaticMotionAllowed()) {
            panel.setPixelWidth(toWidth);
            if (onFinish != null) {
                onFinish.run();
            }
            return;
        }
        int steps = Math.max(5, Math.min(14, editor.dramaticAnimationMs / 18));
        final int[] tick = new int[] {0};
        editor.minimapWidthTimer = new Timer(animationDelayForSteps(steps), ev -> {
            double t = easeOut((double) tick[0] / steps);
            int width = (int) Math.round(fromWidth + (toWidth - fromWidth) * t);
            panel.setPixelWidth(width);
            tick[0]++;
            if (tick[0] > steps) {
                editor.minimapWidthTimer.stop();
                editor.minimapWidthTimer = null;
                panel.setPixelWidth(toWidth);
                if (onFinish != null) {
                    onFinish.run();
                }
            }
        });
        editor.minimapWidthTimer.start();
    }


    void clearPaneJumpFlash() {
        if (editor.paneJumpFlashTarget != null && editor.paneJumpFlashTarget.getScrollPane() != null) {
            editor.paneJumpFlashTarget.getScrollPane().setBorder(editor.paneJumpFlashOriginalBorder);
            editor.paneJumpFlashTarget.getScrollPane().revalidate();
            editor.paneJumpFlashTarget.getScrollPane().repaint();
        }
        editor.paneJumpFlashTarget = null;
        editor.paneJumpFlashOriginalBorder = null;
    }


    void flashPaneJump(EditorPane pane) {
        if (!editor.dramaticPanelAnimationsEnabled || pane == null || pane.getScrollPane() == null) {
            return;
        }
        if (editor.paneJumpFlashTimer != null) {
            editor.paneJumpFlashTimer.stop();
            editor.paneJumpFlashTimer = null;
        }
        clearPaneJumpFlash();

        JScrollPane scrollPane = pane.getScrollPane();
        editor.paneJumpFlashTarget = pane;
        editor.paneJumpFlashOriginalBorder = scrollPane.getBorder();
        Color accent = blendColor(editor.configManager.getCaretColor(), editor.configManager.getSelectionColor(), 0.35);
        animateEditorHostTint(accent);

        if (!dramaticMotionAllowed()) {
            scrollPane.setBorder(BorderFactory.createLineBorder(accent, 2));
            editor.paneJumpFlashTimer = new Timer(120, ev -> {
                clearPaneJumpFlash();
                editor.paneJumpFlashTimer.stop();
                editor.paneJumpFlashTimer = null;
            });
            editor.paneJumpFlashTimer.setRepeats(false);
            editor.paneJumpFlashTimer.start();
            return;
        }

        int steps = Math.max(4, Math.min(12, editor.dramaticAnimationMs / 20));
        final int[] tick = new int[] {0};
        editor.paneJumpFlashTimer = new Timer(animationDelayForSteps(steps), ev -> {
            double t = (double) tick[0] / steps;
            int alpha = (int) Math.round((1.0 - t) * 180);
            alpha = Math.max(0, Math.min(255, alpha));
            scrollPane.setBorder(BorderFactory.createLineBorder(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), alpha), 2));
            tick[0]++;
            if (tick[0] > steps) {
                editor.paneJumpFlashTimer.stop();
                editor.paneJumpFlashTimer = null;
                clearPaneJumpFlash();
            }
        });
        editor.paneJumpFlashTimer.start();
    }


    void playCue(CueType cueType) {
        if (!editor.dramaticSoundEnabled) {
            return;
        }
        if (editor.dramaticSoundVolume <= 0) {
            return;
        }
        if (cueType == CueType.MODE_CHANGE && !editor.dramaticSoundModeCueEnabled) {
            return;
        }
        if (cueType == CueType.NAVIGATE && !editor.dramaticSoundNavigateCueEnabled) {
            return;
        }
        if (cueType == CueType.SUCCESS && !editor.dramaticSoundSuccessCueEnabled) {
            return;
        }
        if (cueType == CueType.ERROR && !editor.dramaticSoundErrorCueEnabled) {
            return;
        }

        int[] pattern = cuePattern(cueType);
        for (int delay : pattern) {
            Timer beep = new Timer(Math.max(0, delay), ev -> {
                Toolkit.getDefaultToolkit().beep();
                ((Timer) ev.getSource()).stop();
            });
            beep.setRepeats(false);
            beep.start();
        }
    }


    int[] cuePattern(CueType cueType) {
        String pack = editor.dramaticSoundPack == null ? "default" : editor.dramaticSoundPack;
        int[] base;
        switch (pack) {
            case "soft":
                switch (cueType) {
                    case MODE_CHANGE: base = new int[] {0}; break;
                    case NAVIGATE: base = new int[] {0}; break;
                    case SUCCESS: base = new int[] {0, 80}; break;
                    case ERROR: base = new int[] {0, 120}; break;
                    default: base = new int[] {0}; break;
                }
                break;
            case "cinema":
            case "dramatic":
                switch (cueType) {
                    case MODE_CHANGE: base = new int[] {0, 35}; break;
                    case NAVIGATE: base = new int[] {0, 45}; break;
                    case SUCCESS: base = new int[] {0, 60, 120}; break;
                    case ERROR: base = new int[] {0, 60, 120, 180}; break;
                    default: base = new int[] {0}; break;
                }
                break;
            default:
                switch (cueType) {
                    case MODE_CHANGE: base = new int[] {0}; break;
                    case NAVIGATE: base = new int[] {0}; break;
                    case SUCCESS: base = new int[] {0, 70}; break;
                    case ERROR: base = new int[] {0, 90, 180}; break;
                    default: base = new int[] {0}; break;
                }
                break;
        }
        int maxBeeps;
        if (editor.dramaticSoundVolume >= 80) {
            maxBeeps = base.length;
        } else if (editor.dramaticSoundVolume >= 50) {
            maxBeeps = Math.max(1, base.length - 1);
        } else {
            maxBeeps = 1;
        }
        int[] limited = new int[maxBeeps];
        System.arraycopy(base, 0, limited, 0, maxBeeps);
        return limited;
    }

}
