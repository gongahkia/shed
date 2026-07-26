import AppKit
import ItsyConfig
@testable import ItsyApp
import ItsyEditor
import ItsyRender
import Testing

@MainActor @Test func editorPaneLifecycleInstallsConfigurationAndInteractions() {
	_ = NSApplication.shared
	var settings = ItsySettings.EditorSettings()
	settings.multipleSelections = false
	settings.fontRendering = .subpixel
	settings.keymap = .vim
	settings.cursorStyle = .automatic
	settings.wrap = .hard
	settings.wrapColumn = 96
	let pane = EditorPane()
	let document = ItsyDocument()
	let lifecycle = EditorPaneLifecycleController()
	var configuredDocument: ItsyDocument?
	lifecycle.install(
		pane,
		document: document,
		configuration: .init(editorSettings: settings, palette: .defaultLight)
	) { view, attachedDocument in
		configuredDocument = attachedDocument
		view.commandRequested = { $0 == "editor.lifecycle.test" }
	}

	#expect(configuredDocument === document)
	#expect(!pane.editorView.allowsMultipleSelections)
	#expect(pane.editorView.fontRenderingMode == .subpixel)
	#expect(pane.editorView.cursorStyle == .block)
	#expect(pane.editorView.commandRequested?("editor.lifecycle.test") == true)
	#expect(pane.editorView.commandRequested?("editor.lifecycle.other") == false)
}

@MainActor @Test func editorPaneLifecycleAppliesSettingsToEveryPane() {
	_ = NSApplication.shared
	var settings = ItsySettings.EditorSettings()
	settings.autoPairs = false
	settings.smartIndent = false
	settings.useSpaces = true
	settings.tabWidth = 2
	settings.keymap = .plain
	settings.cursorStyle = .automatic
	let first = EditorPane()
	let second = EditorPane()
	let lifecycle = EditorPaneLifecycleController()

	lifecycle.applyEditorSettings(settings, to: [first, second])

	for pane in [first, second] {
		#expect(pane.editorView.textEditBehaviorConfiguration == .init(autoPairs: false, smartIndent: false, indentationUnit: "  "))
		#expect(pane.editorView.cursorStyle == .bar)
	}
}

@MainActor @Test func editorPaneLifecycleRespectsModalCursorProfilesAndOverrides() {
	var settings = ItsySettings.EditorSettings()
	let pane = EditorPane()
	let lifecycle = EditorPaneLifecycleController()

	settings.keymap = .emacs
	settings.cursorStyle = .automatic
	lifecycle.applyEditorSettings(settings, to: [pane])
	#expect(pane.editorView.cursorStyle == .block)

	settings.keymap = .vim
	settings.cursorStyle = .bar
	lifecycle.applyEditorSettings(settings, to: [pane])
	#expect(pane.editorView.cursorStyle == .bar)

	settings.keymap = .plain
	settings.cursorStyle = .block
	lifecycle.applyEditorSettings(settings, to: [pane])
	#expect(pane.editorView.cursorStyle == .block)
}
