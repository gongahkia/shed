import AppKit
import ItsyConfig
import ItsyEditor
import ItsyRender
@testable import ItsyApp
import Testing

@MainActor @Test func findBarAppliesPersistedDefaultOptionsToControls() {
	let controller = FindBarController()
	let settings = ItsySettings.FindSettings(usesRegex: true, isCaseSensitive: true, matchesWholeWord: true)
	controller.applyDefaultOptions(settings)
	#expect(controller.selectedOptions == settings)
}

@MainActor @Test func findBarQueryReplaceUpdatesCurrentAndAllMatches() throws {
	let controller = FindBarController()
	let editor = MetalTextView(frame: .zero)
	editor.editor = Editor(text: "cat cat")
	controller.currentEditorView = { editor }
	controller.beginQueryReplace(query: "cat")
	let stack = try #require(controller.view.subviews.first as? NSStackView)
	let fields = stack.arrangedSubviews.compactMap { $0 as? NSTextField }
	let replacement = try #require(fields.last)
	replacement.stringValue = "dog"
	let replace = try #require(stack.arrangedSubviews.compactMap { $0 as? NSButton }.first { $0.title == "Replace" })
	let replaceAll = try #require(stack.arrangedSubviews.compactMap { $0 as? NSButton }.first { $0.title == "Replace All" })
	replace.performClick(nil)
	#expect(editorStorageString(editor.editor) == "dog cat")
	replaceAll.performClick(nil)
	#expect(editorStorageString(editor.editor) == "dog dog")
}
