import AppKit
import ItsyConfig
import ItsyEditor
import ItsyRender

@MainActor final class EditorPaneLifecycleController {
	struct Configuration {
		let editorSettings: ItsySettings.EditorSettings
		let palette: EditorColorPalette
	}

	func install(
		_ pane: EditorPane,
		document: ItsyDocument,
		configuration: Configuration,
		configureInteractions: (MetalTextView, ItsyDocument) -> Void
	) {
		recordBenchStage("editor_pane_install_begin")
		let view = pane.editorView
		document.attach(view)
		recordBenchStage("editor_pane_attach_end")
		recordBenchStage("editor_pane_preferences_begin")
		let preferences = EditorPreferences(settings: configuration.editorSettings)
		recordBenchStage("editor_pane_preferences_end")
		recordBenchStage("editor_pane_appearance_begin")
		apply(preferences, palette: configuration.palette, to: view)
		view.allowsMultipleSelections = configuration.editorSettings.multipleSelections
		view.fontRenderingMode = glyphRenderingMode(configuration.editorSettings.fontRendering)
		view.cursorStyle = cursorStyle(for: configuration.editorSettings)
		recordBenchStage("editor_pane_appearance_end")
		recordBenchStage("editor_pane_keymap_begin")
		view.keymapEngine = ItsyAppKeymap.makeEngine()
		recordBenchStage("editor_pane_keymap_end")
		configureInteractions(view, document)
		recordBenchStage("editor_pane_install_end")
	}

	func applyEditorPreferences(_ preferences: EditorPreferences, palette: EditorColorPalette, to panes: [EditorPane]) {
		for pane in panes {
			apply(preferences, palette: palette, to: pane.editorView)
		}
	}

	func applyEditorSettings(_ settings: ItsySettings.EditorSettings, to panes: [EditorPane]) {
		let indentationUnit = settings.useSpaces ? String(repeating: " ", count: settings.tabWidth) : "\t"
		for pane in panes {
			pane.editorView.textEditBehaviorConfiguration = TextEditBehaviorConfiguration(
				autoPairs: settings.autoPairs,
				smartIndent: settings.smartIndent,
				indentationUnit: indentationUnit,
				detectIndentation: settings.detectIndentation
			)
			pane.editorView.allowsMultipleSelections = settings.multipleSelections
			pane.editorView.fontRenderingMode = glyphRenderingMode(settings.fontRendering)
			pane.editorView.cursorStyle = cursorStyle(for: settings)
		}
	}

	func applyTheme(_ palette: EditorColorPalette, to panes: [EditorPane]) {
		for pane in panes {
			pane.editorView.applyEditorColorPalette(palette)
		}
	}

	func reloadKeymap(in panes: [EditorPane]) {
		for pane in panes {
			pane.editorView.keymapEngine = ItsyAppKeymap.makeEngine()
		}
	}

	private func apply(_ preferences: EditorPreferences, palette: EditorColorPalette, to view: MetalTextView) {
		view.configureEditorAppearance(
			fontName: preferences.fontName,
			fontSize: preferences.fontSize,
			showsLineNumbers: preferences.showLineNumbers
		)
		view.configureEditorBehavior(
			lineNumberMode: lineNumberMode(preferences.lineNumberMode),
			wrapMode: wrapMode(preferences.wrap),
			hardWrapColumn: preferences.wrapColumn
		)
		view.applyEditorColorPalette(palette)
	}

	private func lineNumberMode(_ mode: ItsySettings.LineNumberMode) -> MetalLineNumberMode {
		switch mode {
		case .off: .off
		case .absolute: .absolute
		case .relative: .relative
		}
	}

	private func wrapMode(_ mode: ItsySettings.WrapMode) -> MetalWrapMode {
		switch mode {
		case .none: .none
		case .soft: .soft
		case .hard: .hard
		}
	}

	private func cursorStyle(for settings: ItsySettings.EditorSettings) -> MetalCursorStyle {
		switch settings.cursorStyle {
		case .block: .block
		case .bar: .bar
		case .automatic:
			switch settings.keymap {
			case .vim, .emacs: .block
			case .plain: .bar
			}
		}
	}

	private func glyphRenderingMode(_ mode: ItsySettings.FontRenderingMode) -> GlyphAtlas.RenderingMode {
		switch mode {
		case .grayscale: .grayscale
		case .subpixel: .subpixel
		}
	}
}
