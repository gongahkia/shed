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

@Test func metalLayerUsesTripleBufferedSDRDrawable() throws {
	let view = MetalTextView(frame: .zero)
	let layer = try #require(view.makeBackingLayer() as? CAMetalLayer)
	#expect(layer.maximumDrawableCount == 3)
	#expect(!layer.wantsExtendedDynamicRangeContent)
}

@Test func textViewExposesCurrentLineToAccessibility() throws {
	let view = MetalTextView(frame: .zero)
	view.editor = Editor(text: "alpha\nbeta\n")
	view.selectUTF8Range(6 ..< 6)

	#expect(view.isAccessibilityElement())
	#expect(view.accessibilityRole() == .textArea)
	#expect(view.accessibilityLabel() == "Editor")
	#expect(try #require(view.accessibilityValue() as? String) == "Line 2: beta")
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

@Test func commandDSelectsCurrentWordThenAddsNextMatch() {
	let view = MetalTextView(frame: .zero)
	view.editor = Editor(text: "foo bar foo foo")
	view.editor.setSelection(SelectionSet(primary: Selection(anchor: 1, head: 1)))
	view.keymapEngine = KeymapEngine(modeStack: [.insert], bindings: [
		KeyBinding(mode: .insert, chord: [Key("d", modifiers: .command)], commandID: "editor.addNextSelection"),
	])

	#expect(view.handleKey(characters: "d", charactersIgnoringModifiers: "d", keyCode: 0, modifierFlags: .command))
	#expect(view.editor.selections.primary.range == 0 ..< 3)
	#expect(view.handleKey(characters: "d", charactersIgnoringModifiers: "d", keyCode: 0, modifierFlags: .command))
	#expect(view.editor.selections.primary.range == 0 ..< 3)
	#expect(view.editor.selections.secondaries == [Selection(anchor: 8, head: 11)])
}

@Test func commandClickTogglesAdditionalCursor() {
	let view = MetalTextView(frame: .zero)
	view.editor = Editor(text: "abcdef")
	view.toggleAdditionalCursor(at: 3)
	#expect(view.editor.selections.primary == Selection(anchor: 0, head: 0))
	#expect(view.editor.selections.secondaries == [Selection(anchor: 3, head: 3)])
	view.toggleAdditionalCursor(at: 3)
	#expect(view.editor.selections.primary == Selection(anchor: 0, head: 0))
	#expect(view.editor.selections.secondaries.isEmpty)
	view.toggleAdditionalCursor(at: 0)
	#expect(view.editor.selections.primary == Selection(anchor: 0, head: 0))
	#expect(view.editor.selections.secondaries.isEmpty)
}

@Test func optionDragBuildsColumnCursors() {
	let view = MetalTextView(frame: .zero)
	view.editor = Editor(text: "abc\nde\nfghi\n")
	view.updateColumnCursors(anchor: 1, head: 8)
	#expect(view.editor.selections.primary == Selection(anchor: 1, head: 1))
	#expect(view.editor.selections.secondaries == [
		Selection(anchor: 5, head: 5),
		Selection(anchor: 8, head: 8),
	])
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

@Test func textGlyphInstancesUseHighlightColors() throws {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	view.editor = Editor(text: "abc\n")
	let red = SIMD4<Float>(1, 0, 0, 1)
	view.highlightSpans = [TextHighlightSpan(range: 0 ..< 1, color: red)]
	let instances = view.textGlyphInstances(scale: 1)
	let first = try #require(instances.first)
	#expect(first.color == red)
}

@Test func lineShapeCacheInvalidatesAfterSameLengthEdit() throws {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	view.editor = Editor(text: "iii\n")
	let narrow = try #require(view.textGlyphInstances(scale: 1).first)
	view.editor.setSelection(SelectionSet(primary: Selection(anchor: 0, head: 1)))
	view.editor.insert("W")
	let wide = try #require(view.textGlyphInstances(scale: 1).first)
	#expect(wide.size.x > narrow.size.x)
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

@Test func vimJumpBackReturnsToPreviousJumpSelection() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	view.editor = Editor(text: "one\ntwo\nthree")
	view.editor.setSelection(SelectionSet(primary: Selection(anchor: 4, head: 4)))
	view.keymapEngine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("g"), Key("g")], commandID: "editor.moveBufferStart"),
		KeyBinding(mode: .normal, chord: [Key("'"), Key("'")], commandID: "vim.jumpBack"),
	])

	#expect(view.handleKey(characters: "g", charactersIgnoringModifiers: "g", keyCode: 0))
	#expect(view.handleKey(characters: "g", charactersIgnoringModifiers: "g", keyCode: 0))
	#expect(view.editor.selections.primary.head == 0)
	#expect(view.handleKey(characters: "'", charactersIgnoringModifiers: "'", keyCode: 0))
	#expect(view.handleKey(characters: "'", charactersIgnoringModifiers: "'", keyCode: 0))
	#expect(view.editor.selections.primary.head == 4)
	#expect(view.handleKey(characters: "'", charactersIgnoringModifiers: "'", keyCode: 0))
	#expect(view.handleKey(characters: "'", charactersIgnoringModifiers: "'", keyCode: 0))
	#expect(view.editor.selections.primary.head == 0)
}

@Test func emacsStandardMotionsMoveCursor() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	view.editor = Editor(text: "one two\nthree")
	view.keymapEngine = KeymapEngine(modeStack: [.emacs], bindings: [
		KeyBinding(mode: .emacs, chord: [Key("f", modifiers: .control)], commandID: "editor.moveRight"),
		KeyBinding(mode: .emacs, chord: [Key("b", modifiers: .control)], commandID: "editor.moveLeft"),
		KeyBinding(mode: .emacs, chord: [Key("n", modifiers: .control)], commandID: "editor.moveDown"),
		KeyBinding(mode: .emacs, chord: [Key("p", modifiers: .control)], commandID: "editor.moveUp"),
		KeyBinding(mode: .emacs, chord: [Key("f", modifiers: .option)], commandID: "editor.moveWordForward"),
		KeyBinding(mode: .emacs, chord: [Key("b", modifiers: .option)], commandID: "editor.moveWordBackward"),
		KeyBinding(mode: .emacs, chord: [Key(",", modifiers: [.option, .shift])], commandID: "editor.moveBufferStart"),
		KeyBinding(mode: .emacs, chord: [Key(".", modifiers: [.option, .shift])], commandID: "editor.moveBufferEnd"),
	])

	#expect(view.handleKey(characters: "\u{06}", charactersIgnoringModifiers: "f", keyCode: 0, modifierFlags: .control))
	#expect(view.editor.selections.primary.head == 1)
	#expect(view.handleKey(characters: "\u{02}", charactersIgnoringModifiers: "b", keyCode: 0, modifierFlags: .control))
	#expect(view.editor.selections.primary.head == 0)
	#expect(view.handleKey(characters: "", charactersIgnoringModifiers: "n", keyCode: 0, modifierFlags: .control))
	#expect(view.editor.selections.primary.head == 8)
	#expect(view.handleKey(characters: "", charactersIgnoringModifiers: "p", keyCode: 0, modifierFlags: .control))
	#expect(view.editor.selections.primary.head == 0)
	#expect(view.handleKey(characters: "f", charactersIgnoringModifiers: "f", keyCode: 0, modifierFlags: .option))
	#expect(view.editor.selections.primary.head == 4)
	#expect(view.handleKey(characters: "b", charactersIgnoringModifiers: "b", keyCode: 0, modifierFlags: .option))
	#expect(view.editor.selections.primary.head == 0)
	#expect(view.handleKey(characters: "<", charactersIgnoringModifiers: ",", keyCode: 0, modifierFlags: [.option, .shift]))
	#expect(view.editor.selections.primary.head == 0)
	#expect(view.handleKey(characters: ">", charactersIgnoringModifiers: ".", keyCode: 0, modifierFlags: [.option, .shift]))
	#expect(view.editor.selections.primary.head == view.editor.rope.length)
}

@Test func emacsKillCopyAndYankUseKillRingAndClipboard() {
	withPasteboardLock {
		let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
		view.editor = Editor(text: "alpha beta gamma")
		view.keymapEngine = KeymapEngine(modeStack: [.emacs], bindings: [
			KeyBinding(mode: .emacs, chord: [Key("w", modifiers: .control)], commandID: "emacs.killRegion"),
			KeyBinding(mode: .emacs, chord: [Key("w", modifiers: .option)], commandID: "emacs.copyRegion"),
			KeyBinding(mode: .emacs, chord: [Key("y", modifiers: .control)], commandID: "emacs.yank"),
		])
		NSPasteboard.general.clearContents()

		view.selectUTF8Range(6 ..< 10)
		#expect(view.handleKey(characters: "\u{17}", charactersIgnoringModifiers: "w", keyCode: 0, modifierFlags: .control))
		#expect(view.editor.text == "alpha  gamma")
		#expect(NSPasteboard.general.string(forType: .string) == "beta")
		#expect(view.handleKey(characters: "\u{19}", charactersIgnoringModifiers: "y", keyCode: 0, modifierFlags: .control))
		#expect(view.editor.text == "alpha beta gamma")
		view.selectUTF8Range(0 ..< 5)
		#expect(view.handleKey(characters: "w", charactersIgnoringModifiers: "w", keyCode: 0, modifierFlags: .option))
		#expect(view.editor.text == "alpha beta gamma")
		#expect(NSPasteboard.general.string(forType: .string) == "alpha")
	}
}

@Test func emacsYankPopRotatesLastYank() {
	withPasteboardLock {
		let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
		view.editor = Editor(text: "alpha beta gamma")
		view.keymapEngine = KeymapEngine(modeStack: [.emacs], bindings: [
			KeyBinding(mode: .emacs, chord: [Key("w", modifiers: .option)], commandID: "emacs.copyRegion"),
			KeyBinding(mode: .emacs, chord: [Key("y", modifiers: .control)], commandID: "emacs.yank"),
			KeyBinding(mode: .emacs, chord: [Key("y", modifiers: .option)], commandID: "emacs.yankPop"),
		])

		view.selectUTF8Range(0 ..< 5)
		#expect(view.handleKey(characters: "w", charactersIgnoringModifiers: "w", keyCode: 0, modifierFlags: .option))
		view.selectUTF8Range(11 ..< 16)
		#expect(view.handleKey(characters: "w", charactersIgnoringModifiers: "w", keyCode: 0, modifierFlags: .option))
		view.selectUTF8Range(16 ..< 16)
		#expect(view.handleKey(characters: "\u{19}", charactersIgnoringModifiers: "y", keyCode: 0, modifierFlags: .control))
		#expect(view.editor.text == "alpha beta gammagamma")
		#expect(view.handleKey(characters: "y", charactersIgnoringModifiers: "y", keyCode: 0, modifierFlags: .option))
		#expect(view.editor.text == "alpha beta gammaalpha")
	}
}

@Test func emacsPrefixCommandsRouteToHost() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	var commands: [String] = []
	view.commandRequested = { command in
		commands.append(command)
		return true
	}
	view.keymapEngine = KeymapEngine(modeStack: [.emacs], bindings: [
		KeyBinding(mode: .emacs, chord: [Key("x", modifiers: .control), Key("b")], commandID: "file.nextBuffer"),
		KeyBinding(mode: .emacs, chord: [Key("x", modifiers: .control), Key("2")], commandID: "pane.splitHorizontal"),
		KeyBinding(mode: .emacs, chord: [Key("x", modifiers: .control), Key("3")], commandID: "pane.splitVertical"),
	])

	#expect(view.handleKey(characters: "\u{18}", charactersIgnoringModifiers: "x", keyCode: 0, modifierFlags: .control))
	#expect(view.handleKey(characters: "b", charactersIgnoringModifiers: "b", keyCode: 0))
	#expect(view.handleKey(characters: "\u{18}", charactersIgnoringModifiers: "x", keyCode: 0, modifierFlags: .control))
	#expect(view.handleKey(characters: "2", charactersIgnoringModifiers: "2", keyCode: 0))
	#expect(view.handleKey(characters: "\u{18}", charactersIgnoringModifiers: "x", keyCode: 0, modifierFlags: .control))
	#expect(view.handleKey(characters: "3", charactersIgnoringModifiers: "3", keyCode: 0))
	#expect(commands == ["file.nextBuffer", "pane.splitHorizontal", "pane.splitVertical"])
}

@Test func emacsIncrementalSearchCommandsRouteToHost() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	var commands: [String] = []
	view.commandRequested = { command in
		commands.append(command)
		return true
	}
	view.keymapEngine = KeymapEngine(modeStack: [.emacs], bindings: [
		KeyBinding(mode: .emacs, chord: [Key("s", modifiers: .control)], commandID: "emacs.isearchForward"),
		KeyBinding(mode: .emacs, chord: [Key("r", modifiers: .control)], commandID: "emacs.isearchBackward"),
	])

	#expect(view.handleKey(characters: "\u{13}", charactersIgnoringModifiers: "s", keyCode: 0, modifierFlags: .control))
	#expect(view.handleKey(characters: "\u{12}", charactersIgnoringModifiers: "r", keyCode: 0, modifierFlags: .control))
	#expect(commands == ["emacs.isearchForward", "emacs.isearchBackward"])
}

@Test func vimSearchCommandsRouteToHost() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	var commands: [String] = []
	view.commandRequested = { command in
		commands.append(command)
		return true
	}
	view.keymapEngine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("/")], commandID: "vim.searchForward"),
		KeyBinding(mode: .normal, chord: [Key("/", modifiers: .shift)], commandID: "vim.searchBackward"),
		KeyBinding(mode: .normal, chord: [Key("n")], commandID: "edit.findNext"),
		KeyBinding(mode: .normal, chord: [Key("n", modifiers: .shift)], commandID: "edit.findPrevious"),
	])

	#expect(view.handleKey(characters: "/", charactersIgnoringModifiers: "/", keyCode: 0))
	#expect(view.handleKey(characters: "?", charactersIgnoringModifiers: "/", keyCode: 0, modifierFlags: .shift))
	#expect(view.handleKey(characters: "n", charactersIgnoringModifiers: "n", keyCode: 0))
	#expect(view.handleKey(characters: "N", charactersIgnoringModifiers: "n", keyCode: 0, modifierFlags: .shift))
	#expect(commands == ["vim.searchForward", "vim.searchBackward", "edit.findNext", "edit.findPrevious"])
}

@Test func emacsUniversalArgumentRepeatsNextCommand() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	view.editor = Editor(text: "abcdef")
	view.keymapEngine = KeymapEngine(modeStack: [.emacs], bindings: [
		KeyBinding(mode: .emacs, chord: [Key("f", modifiers: .control)], commandID: "editor.moveRight"),
	])

	#expect(view.handleKey(characters: "\u{15}", charactersIgnoringModifiers: "u", keyCode: 0, modifierFlags: .control))
	#expect(view.handleKey(characters: "3", charactersIgnoringModifiers: "3", keyCode: 0))
	#expect(view.handleKey(characters: "\u{06}", charactersIgnoringModifiers: "f", keyCode: 0, modifierFlags: .control))
	#expect(view.editor.selections.primary.head == 3)
}

@Test func vimUndoRedoTreatsInsertSessionAsOneUndoUnit() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	view.keymapEngine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("i")], commandID: "mode.insert"),
		KeyBinding(mode: .normal, chord: [Key("u")], commandID: "edit.undo"),
		KeyBinding(mode: .normal, chord: [Key("r", modifiers: .control)], commandID: "edit.redo"),
		KeyBinding(mode: .insert, chord: [Key("escape")], commandID: "mode.normal"),
	])

	#expect(view.handleKey(characters: "i", charactersIgnoringModifiers: "i", keyCode: 0))
	for character in "abc" {
		#expect(view.handleKey(characters: String(character), charactersIgnoringModifiers: String(character), keyCode: 0))
	}
	#expect(view.handleKey(characters: "\u{1b}", charactersIgnoringModifiers: "\u{1b}", keyCode: 53))
	#expect(view.editor.text == "abc")
	#expect(view.handleKey(characters: "u", charactersIgnoringModifiers: "u", keyCode: 0))
	#expect(view.editor.text == "")
	#expect(view.handleKey(characters: "\u{12}", charactersIgnoringModifiers: "r", keyCode: 0, modifierFlags: .control))
	#expect(view.editor.text == "abc")
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
		KeyBinding(mode: .normal, chord: [Key("p", modifiers: .shift)], commandID: "vim.pasteBefore"),
		KeyBinding(mode: .operatorPending, chord: [Key("c")], commandID: "vim.operator.line.change"),
		KeyBinding(mode: .operatorPending, chord: [Key("y")], commandID: "vim.operator.line.yank"),
	])

	#expect(view.handleKey(characters: "y", charactersIgnoringModifiers: "y", keyCode: 0))
	#expect(view.handleKey(characters: "y", charactersIgnoringModifiers: "y", keyCode: 0))
	#expect(view.handleKey(characters: "P", charactersIgnoringModifiers: "p", keyCode: 0, modifierFlags: .shift))
	#expect(view.editor.text == "alpha\nalpha\nbeta\n")
	view.editor = Editor(text: "alpha\nbeta\n")
	#expect(view.handleKey(characters: "c", charactersIgnoringModifiers: "c", keyCode: 0))
	#expect(view.handleKey(characters: "c", charactersIgnoringModifiers: "c", keyCode: 0))
	#expect(view.editor.text == "beta\n")
	#expect(view.keymapEngine.mode == .insert)
}

@Test func vimTextObjectsApplyToPendingOperator() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	view.editor = Editor(text: "alpha beta")
	view.keymapEngine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("d")], commandID: "vim.operator.delete"),
		KeyBinding(mode: .operatorPending, chord: [Key("i"), Key("w")], commandID: "vim.textObject.innerWord"),
	])

	#expect(view.handleKey(characters: "d", charactersIgnoringModifiers: "d", keyCode: 0))
	#expect(view.handleKey(characters: "i", charactersIgnoringModifiers: "i", keyCode: 0))
	#expect(view.handleKey(characters: "w", charactersIgnoringModifiers: "w", keyCode: 0))
	#expect(view.editor.text == " beta")
}

@Test func vimQuoteTextObjectUsesShiftedQuoteBinding() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	view.editor = Editor(text: "\"value\" tail")
	view.keymapEngine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("d")], commandID: "vim.operator.delete"),
		KeyBinding(mode: .operatorPending, chord: [Key("a"), Key("'", modifiers: .shift)], commandID: "vim.textObject.aroundDoubleQuote"),
	])

	#expect(view.handleKey(characters: "d", charactersIgnoringModifiers: "d", keyCode: 0))
	#expect(view.handleKey(characters: "a", charactersIgnoringModifiers: "a", keyCode: 0))
	#expect(view.handleKey(characters: "\"", charactersIgnoringModifiers: "'", keyCode: 0, modifierFlags: .shift))
	#expect(view.editor.text == " tail")
}

@Test func vimVisualCharModeAppliesDeleteOperator() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	view.editor = Editor(text: "abc")
	view.keymapEngine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("v")], commandID: "vim.visual.char"),
		KeyBinding(mode: .visual, chord: [Key("l")], commandID: "editor.moveRight"),
		KeyBinding(mode: .visual, chord: [Key("d")], commandID: "vim.operator.delete"),
	])

	#expect(view.handleKey(characters: "v", charactersIgnoringModifiers: "v", keyCode: 0))
	#expect(view.handleKey(characters: "l", charactersIgnoringModifiers: "l", keyCode: 0))
	#expect(view.handleKey(characters: "d", charactersIgnoringModifiers: "d", keyCode: 0))
	#expect(view.editor.text == "bc")
	#expect(view.keymapEngine.mode == .normal)
}

@Test func vimVisualLineModeYanksWholeLine() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	view.editor = Editor(text: "alpha\nbeta\n")
	view.keymapEngine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("v", modifiers: .shift)], commandID: "vim.visual.line"),
		KeyBinding(mode: .normal, chord: [Key("p", modifiers: .shift)], commandID: "vim.pasteBefore"),
		KeyBinding(mode: .visual, chord: [Key("y")], commandID: "vim.operator.yank"),
	])

	#expect(view.handleKey(characters: "V", charactersIgnoringModifiers: "v", keyCode: 0, modifierFlags: .shift))
	#expect(view.handleKey(characters: "y", charactersIgnoringModifiers: "y", keyCode: 0))
	#expect(view.handleKey(characters: "P", charactersIgnoringModifiers: "p", keyCode: 0, modifierFlags: .shift))
	#expect(view.editor.text == "alpha\nalpha\nbeta\n")
	#expect(view.keymapEngine.mode == .normal)
}

@Test func vimVisualBlockModePopulatesSelectionSet() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	view.editor = Editor(text: "ab\ncd\n")
	view.keymapEngine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("v", modifiers: .control)], commandID: "vim.visual.block"),
		KeyBinding(mode: .visual, chord: [Key("j")], commandID: "editor.moveDown"),
	])

	#expect(view.handleKey(characters: "\u{16}", charactersIgnoringModifiers: "v", keyCode: 0, modifierFlags: .control))
	#expect(view.handleKey(characters: "j", charactersIgnoringModifiers: "j", keyCode: 0))
	#expect(view.editor.selections.primary.range == 0 ..< 1)
	#expect(view.editor.selections.secondaries == [Selection(anchor: 3, head: 4)])
}

@Test func vimRegistersYankAndPasteNamedRegister() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	view.editor = Editor(text: "alpha\n")
	view.keymapEngine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("'", modifiers: .shift)], commandID: "vim.registerPrefix"),
		KeyBinding(mode: .normal, chord: [Key("y")], commandID: "vim.operator.yank"),
		KeyBinding(mode: .normal, chord: [Key("p", modifiers: .shift)], commandID: "vim.pasteBefore"),
		KeyBinding(mode: .operatorPending, chord: [Key("y")], commandID: "vim.operator.line.yank"),
	])

	#expect(view.handleKey(characters: "\"", charactersIgnoringModifiers: "'", keyCode: 0, modifierFlags: .shift))
	#expect(view.handleKey(characters: "a", charactersIgnoringModifiers: "a", keyCode: 0))
	#expect(view.handleKey(characters: "y", charactersIgnoringModifiers: "y", keyCode: 0))
	#expect(view.handleKey(characters: "y", charactersIgnoringModifiers: "y", keyCode: 0))
	view.editor = Editor(text: "beta\n")
	#expect(view.handleKey(characters: "\"", charactersIgnoringModifiers: "'", keyCode: 0, modifierFlags: .shift))
	#expect(view.handleKey(characters: "a", charactersIgnoringModifiers: "a", keyCode: 0))
	#expect(view.handleKey(characters: "P", charactersIgnoringModifiers: "p", keyCode: 0, modifierFlags: .shift))
	#expect(view.editor.text == "alpha\nbeta\n")
}

@Test func vimDeleteWritesNumberedRegister() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	view.editor = Editor(text: "alpha\nbeta\n")
	view.keymapEngine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("'", modifiers: .shift)], commandID: "vim.registerPrefix"),
		KeyBinding(mode: .normal, chord: [Key("d")], commandID: "vim.operator.delete"),
		KeyBinding(mode: .normal, chord: [Key("p", modifiers: .shift)], commandID: "vim.pasteBefore"),
		KeyBinding(mode: .operatorPending, chord: [Key("d")], commandID: "vim.operator.line.delete"),
	])

	#expect(view.handleKey(characters: "d", charactersIgnoringModifiers: "d", keyCode: 0))
	#expect(view.handleKey(characters: "d", charactersIgnoringModifiers: "d", keyCode: 0))
	#expect(view.editor.text == "beta\n")
	#expect(view.handleKey(characters: "\"", charactersIgnoringModifiers: "'", keyCode: 0, modifierFlags: .shift))
	#expect(view.handleKey(characters: "1", charactersIgnoringModifiers: "1", keyCode: 0))
	#expect(view.handleKey(characters: "P", charactersIgnoringModifiers: "p", keyCode: 0, modifierFlags: .shift))
	#expect(view.editor.text == "alpha\nbeta\n")
}

@Test func vimPlusRegisterSyncsSystemClipboard() {
	withPasteboardLock {
		let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
		view.editor = Editor(text: "clip\n")
		view.keymapEngine = KeymapEngine(modeStack: [.normal], bindings: [
			KeyBinding(mode: .normal, chord: [Key("'", modifiers: .shift)], commandID: "vim.registerPrefix"),
			KeyBinding(mode: .normal, chord: [Key("y")], commandID: "vim.operator.yank"),
			KeyBinding(mode: .operatorPending, chord: [Key("y")], commandID: "vim.operator.line.yank"),
		])

		#expect(view.handleKey(characters: "\"", charactersIgnoringModifiers: "'", keyCode: 0, modifierFlags: .shift))
		#expect(view.handleKey(characters: "+", charactersIgnoringModifiers: "=", keyCode: 0, modifierFlags: .shift))
		#expect(view.handleKey(characters: "y", charactersIgnoringModifiers: "y", keyCode: 0))
		#expect(view.handleKey(characters: "y", charactersIgnoringModifiers: "y", keyCode: 0))
		#expect(NSPasteboard.general.string(forType: .string) == "clip\n")
	}
}

@Test func vimExSubstitutionEditsBuffer() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	view.editor = Editor(text: "foo foo\n")
	view.keymapEngine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key(";", modifiers: .shift)], commandID: "vim.ex.start"),
	])

	#expect(view.handleKey(characters: ":", charactersIgnoringModifiers: ";", keyCode: 0, modifierFlags: .shift))
	for character in "%s/foo/bar/g" {
		#expect(view.handleKey(characters: String(character), charactersIgnoringModifiers: String(character), keyCode: 0))
	}
	#expect(view.handleKey(characters: "\n", charactersIgnoringModifiers: "\n", keyCode: 36))
	#expect(view.editor.text == "bar bar\n")
}

@Test func vimExCommandRoutesToHost() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	var commands: [String] = []
	view.exCommandRequested = { command in
		commands.append(command)
		return true
	}
	view.keymapEngine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key(";", modifiers: .shift)], commandID: "vim.ex.start"),
	])

	#expect(view.handleKey(characters: ":", charactersIgnoringModifiers: ";", keyCode: 0, modifierFlags: .shift))
	#expect(view.handleKey(characters: "w", charactersIgnoringModifiers: "w", keyCode: 0))
	#expect(view.handleKey(characters: "q", charactersIgnoringModifiers: "q", keyCode: 0))
	#expect(view.handleKey(characters: "\n", charactersIgnoringModifiers: "\n", keyCode: 36))
	#expect(commands == ["wq"])
}

@Test func vimExCommandUsesCommandLineRequestWhenAvailable() {
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	var completion: ((String?) -> Void)?
	var commands: [String] = []
	view.exCommandLineRequested = { finish in
		completion = finish
		return true
	}
	view.exCommandRequested = { command in
		commands.append(command)
		return true
	}
	view.keymapEngine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key(";", modifiers: .shift)], commandID: "vim.ex.start"),
	])

	#expect(view.handleKey(characters: ":", charactersIgnoringModifiers: ";", keyCode: 0, modifierFlags: .shift))
	#expect(view.keymapEngine.mode == .command)
	#expect(completion != nil)
	completion?(":wq")
	#expect(view.keymapEngine.mode == .normal)
	#expect(commands == ["wq"])
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

private let pasteboardTestLock = NSLock()

private func withPasteboardLock(_ body: () -> Void) {
	pasteboardTestLock.lock()
	defer { pasteboardTestLock.unlock() }
	body()
}
