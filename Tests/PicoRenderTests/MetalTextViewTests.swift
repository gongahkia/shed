import AppKit
import PicoEditor
import PicoKeymap
@testable import PicoRender
import Testing

@Test func dirtyFlagPreventsIdleDisplayLinkDraws() {
	let view = MetalTextView(frame: .zero)
	#expect(view.consumeDirtyForDisplayLink())
	#expect(!view.consumeDirtyForDisplayLink())
	view.markDirty()
	#expect(view.consumeDirtyForDisplayLink())
	#expect(!view.consumeDirtyForDisplayLink())
}

@Test func cursorAndSelectionBuildSolidOverlayInstances() {
	let view = MetalTextView(frame: .zero)
	view.setSelectionRects([.init(x: 4, y: 8, width: 20, height: 10)])
	view.setCursor(x: 30, y: 8, height: 10)
	let instances = view.solidOverlayInstances(scale: 2)
	#expect(instances.count == 2)
	#expect(instances[0].size == SIMD2<Float>(40, 20))
	#expect(instances[1].screenOrigin == SIMD2<Float>(60, 16))
	#expect(instances[1].size == SIMD2<Float>(4, 20))
}

@Test func selectingUTF8RangesMergesOverlaps() {
	let view = MetalTextView(frame: .zero)
	view.editor = Editor(text: "abcdef")
	view.selectUTF8Ranges([1 ..< 4, 3 ..< 5])
	#expect(view.editor.selections.primary == Selection(anchor: 1, head: 5))
	#expect(view.editor.selections.secondaries.isEmpty)
}

@Test func viewportTracksVisibleLineRangeAndScrollOffsets() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	view.lineHeight = 20
	view.lineCount = 100_000
	#expect(view.visibleLineRange == 0 ..< 6)
	_ = view.consumeDirtyForDisplayLink()
	view.scroll(deltaX: 12, deltaY: 60)
	#expect(view.topLineIndex == 3)
	#expect(view.xOffset == 12)
	#expect(view.visibleLineRange == 3 ..< 9)
	#expect(view.consumeDirtyForDisplayLink())
	view.scroll(deltaX: -100, deltaY: -1_000)
	#expect(view.topLineIndex == 0)
	#expect(view.xOffset == 0)
}

@Test func keyHandlingEditsEditorAndMarksDirty() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	_ = view.consumeDirtyForDisplayLink()
	for character in "hello world" {
		#expect(view.handleKey(characters: String(character), charactersIgnoringModifiers: String(character), keyCode: 0))
	}
	#expect(view.editor.text == "hello world")
	#expect(view.consumeDirtyForDisplayLink())
	#expect(view.handleKey(characters: nil, charactersIgnoringModifiers: nil, keyCode: 123))
	#expect(view.editor.selections.primary.head == 10)
	#expect(view.handleKey(characters: nil, charactersIgnoringModifiers: nil, keyCode: 51))
	#expect(view.editor.text == "hello word")
}

@Test func keymapProfileChangesTextHandlingWithoutRecompile() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	view.keymapEngine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("h")], commandID: "editor.moveLeft"),
		KeyBinding(mode: .normal, chord: [Key("i")], commandID: "mode.insert"),
	])

	#expect(view.handleKey(characters: "h", charactersIgnoringModifiers: "h", keyCode: 0))
	#expect(view.editor.text == "")
	#expect(view.handleKey(characters: "i", charactersIgnoringModifiers: "i", keyCode: 0))
	#expect(view.handleKey(characters: "x", charactersIgnoringModifiers: "x", keyCode: 0))
	#expect(view.editor.text == "x")
}

@Test func keymapCountRepeatsMotionCommands() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	view.editor = Editor(text: "one two three")
	view.keymapEngine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("w")], commandID: "editor.moveWordForward"),
	])

	#expect(view.handleKey(characters: "2", charactersIgnoringModifiers: "2", keyCode: 0))
	#expect(view.handleKey(characters: "w", charactersIgnoringModifiers: "w", keyCode: 0))
	#expect(view.editor.selections.primary.head == 8)
}

@Test func vimDeleteOperatorAppliesMotionAndLine() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	view.editor = Editor(text: "one two\nthree\n")
	view.keymapEngine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("d")], commandID: "vim.operator.delete"),
		KeyBinding(mode: .operatorPending, chord: [Key("w")], commandID: "editor.moveWordForward"),
		KeyBinding(mode: .operatorPending, chord: [Key("d")], commandID: "vim.operator.line.delete"),
	])

	#expect(view.handleKey(characters: "d", charactersIgnoringModifiers: "d", keyCode: 0))
	#expect(view.handleKey(characters: "w", charactersIgnoringModifiers: "w", keyCode: 0))
	#expect(view.editor.text == "two\nthree\n")
	#expect(view.handleKey(characters: "d", charactersIgnoringModifiers: "d", keyCode: 0))
	#expect(view.handleKey(characters: "d", charactersIgnoringModifiers: "d", keyCode: 0))
	#expect(view.editor.text == "three\n")
}

@Test func vimChangeAndYankLineOperators() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	view.editor = Editor(text: "alpha\nbeta\n")
	view.keymapEngine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("c")], commandID: "vim.operator.change"),
		KeyBinding(mode: .normal, chord: [Key("y")], commandID: "vim.operator.yank"),
		KeyBinding(mode: .operatorPending, chord: [Key("c")], commandID: "vim.operator.line.change"),
		KeyBinding(mode: .operatorPending, chord: [Key("y")], commandID: "vim.operator.line.yank"),
	])

	#expect(view.handleKey(characters: "y", charactersIgnoringModifiers: "y", keyCode: 0))
	#expect(view.handleKey(characters: "y", charactersIgnoringModifiers: "y", keyCode: 0))
	#expect(NSPasteboard.general.string(forType: .string) == "alpha\n")
	#expect(view.handleKey(characters: "c", charactersIgnoringModifiers: "c", keyCode: 0))
	#expect(view.handleKey(characters: "c", charactersIgnoringModifiers: "c", keyCode: 0))
	#expect(view.editor.text == "beta\n")
	#expect(view.keymapEngine.mode == .insert)
}

@Test func textInputClientCommitsAndMarksIMEText() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	let noReplacement = NSRange(location: NSNotFound, length: 0)
	view.insertText("a", replacementRange: noReplacement)
	#expect(view.editor.text == "a")
	#expect(view.selectedRange() == NSRange(location: 1, length: 0))
	view.setMarkedText("かな", selectedRange: NSRange(location: 2, length: 0), replacementRange: noReplacement)
	#expect(view.editor.text == "aかな")
	#expect(view.hasMarkedText())
	#expect(view.markedRange() == NSRange(location: 1, length: 2))
	#expect(view.selectedRange() == NSRange(location: 3, length: 0))
	view.insertText("仮名", replacementRange: noReplacement)
	#expect(view.editor.text == "a仮名")
	#expect(!view.hasMarkedText())
	#expect(view.attributedSubstring(forProposedRange: NSRange(location: 1, length: 2), actualRange: nil)?.string == "仮名")
	view.doCommand(by: #selector(NSResponder.deleteBackward(_:)))
	#expect(view.editor.text == "a仮")
}

@Test func typedTextBuildsGlyphInstancesForRendering() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	for character in "hello world" {
		view.handleKey(characters: String(character), charactersIgnoringModifiers: String(character), keyCode: 0)
	}
	let glyphs = view.textGlyphInstances(scale: 2)
	#expect(glyphs.count == 11)
	#expect(glyphs.allSatisfy { $0.size.x > 0 && $0.size.y > 0 })
}
