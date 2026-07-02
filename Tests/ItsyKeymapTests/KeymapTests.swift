import AppKit
import ItsyKeymap
import Testing

@Test func keymapEngineResolvesSingleKeyCommand() throws {
	var engine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("j")], commandID: "moveDown"),
	])

	let result = engine.handle(try keyEvent("j"))
	#expect(result == .command("moveDown"))
	#expect(engine.pendingChord.isEmpty)
}

@Test func keymapEngineTracksPendingChord() throws {
	var engine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("g"), Key("g")], commandID: "bufferStart"),
	])

	#expect(engine.handle(try keyEvent("g")) == .partial)
	#expect(engine.pendingChord == [Key("g")])
	#expect(engine.handle(try keyEvent("g")) == .command("bufferStart"))
	#expect(engine.pendingChord.isEmpty)
}

@Test func keymapEngineFallsThroughUnknownKeys() throws {
	var engine = KeymapEngine(modeStack: [.insert], bindings: [
		KeyBinding(mode: .normal, chord: [Key("j")], commandID: "moveDown"),
	])

	#expect(engine.handle(try keyEvent("j")) == .passthrough)
}

@Test func keymapEngineClearsPendingChordOnEscape() throws {
	var engine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("g"), Key("g")], commandID: "bufferStart"),
	])

	#expect(engine.handle(try keyEvent("g")) == .partial)
	#expect(engine.handle(try keyEvent("\u{1b}", keyCode: 53)) == .consumed)
	#expect(engine.pendingChord.isEmpty)
}

@Test func keymapEngineTracksNormalModeCountPrefix() throws {
	var engine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("w")], commandID: "wordForward"),
		KeyBinding(mode: .normal, chord: [Key("0")], commandID: "lineStart"),
	])

	#expect(engine.handle(try keyEvent("3")) == .partial)
	#expect(engine.handle(try keyEvent("w")) == .command("wordForward"))
	#expect(engine.lastCommandCount == 3)
	#expect(engine.handle(try keyEvent("0")) == .command("lineStart"))
	#expect(engine.lastCommandCount == 1)
}

@Test func keymapEngineKeepsZeroInsideCountPrefix() throws {
	var engine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("j")], commandID: "down"),
	])

	#expect(engine.handle(try keyEvent("1")) == .partial)
	#expect(engine.handle(try keyEvent("0")) == .partial)
	#expect(engine.handle(try keyEvent("j")) == .command("down"))
	#expect(engine.lastCommandCount == 10)
}

@Test func keymapEngineTracksEmacsUniversalArgument() throws {
	var engine = KeymapEngine(modeStack: [.emacs], bindings: [
		KeyBinding(mode: .emacs, chord: [Key("f", modifiers: .control)], commandID: "right"),
	])

	#expect(engine.handle(try keyEvent("u", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("1")) == .partial)
	#expect(engine.handle(try keyEvent("2")) == .partial)
	#expect(engine.handle(try keyEvent("f", modifiers: [.control])) == .command("right"))
	#expect(engine.lastCommandCount == 12)
	#expect(engine.handle(try keyEvent("u", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("f", modifiers: [.control])) == .command("right"))
	#expect(engine.lastCommandCount == 4)
}

@Test func keymapEngineNormalizesModifierKeys() throws {
	var engine = KeymapEngine(modeStack: [.emacs], bindings: [
		KeyBinding(mode: .emacs, chord: [Key("f", modifiers: .control)], commandID: "forwardChar"),
	])

	let result = engine.handle(try keyEvent("f", modifiers: [.control]))
	#expect(result == .command("forwardChar"))
}

@Test func keymapLoaderTreatsMetaAsOption() throws {
	let bindings = try KeymapLoader.load("""
	[mode.emacs]
	"M-f" = "forwardWord"
	"M-<" = "bufferStart"
	"M->" = "bufferEnd"
	""")
	var engine = KeymapEngine(modeStack: [.emacs], bindings: bindings)

	#expect(engine.handle(try keyEvent("f", modifiers: [.option])) == .command("forwardWord"))
	#expect(engine.handle(try keyEvent(",", modifiers: [.option, .shift])) == .command("bufferStart"))
	#expect(engine.handle(try keyEvent(".", modifiers: [.option, .shift])) == .command("bufferEnd"))
}

@Test func keymapLoaderLoadsTomlBindingsAndResolvesChord() throws {
	let bindings = try KeymapLoader.load("""
	[mode.normal]
	"jk" = "exitInsert"
	"Left" = "moveLeft"

	[mode.emacs]
	"C-f" = "forwardChar"
	""")
	var engine = KeymapEngine(modeStack: [.normal], bindings: bindings)

	#expect(engine.handle(try keyEvent("j")) == .partial)
	#expect(engine.handle(try keyEvent("k")) == .command("exitInsert"))
	#expect(engine.handle(try keyEvent("", keyCode: 123)) == .command("moveLeft"))
	engine.setMode(.emacs)
	#expect(engine.handle(try keyEvent("f", modifiers: [.control])) == .command("forwardChar"))
}

@Test func keymapLoaderBuildsSingleBindingFromKeyString() throws {
	let binding = try KeymapLoader.binding(mode: .insert, key: "cmd+shift+i", commandID: "extension:dev.example:open")

	#expect(binding.mode == .insert)
	#expect(binding.chord == [Key("i", modifiers: [.command, .shift])])
	#expect(binding.commandID == "extension:dev.example:open")
}

@Test func bundledKeymapProfilesLoad() throws {
	for profile in KeymapProfile.allCases {
		let bindings = try KeymapConfiguration.load(profile: profile, userConfigURL: nil)
		#expect(!bindings.isEmpty)
	}
}

@Test func bundledProfilesDefineAddNextSelection() throws {
	for profile in KeymapProfile.allCases {
		let bindings = try KeymapConfiguration.load(profile: profile, userConfigURL: nil)
		var engine = KeymapEngine(modeStack: [profile == .vim ? .normal : profile == .emacs ? .emacs : .insert], bindings: bindings)
		#expect(engine.handle(try keyEvent("d", modifiers: [.command])) == .command("editor.addNextSelection"))
		#expect(engine.handle(try keyEvent("g", modifiers: [.command, .control])) == .command("edit.selectAllFindMatches"))
		#expect(engine.handle(try keyEvent("w", modifiers: [.command])) == .command("pane.close"))
		#expect(engine.handle(try keyEvent("\\", modifiers: [.command])) == .command("pane.splitHorizontal"))
		#expect(engine.handle(try keyEvent("\\", modifiers: [.command, .option])) == .command("pane.splitVertical"))
		#expect(engine.handle(try keyEvent("", modifiers: [.command, .option], keyCode: 123)) == .command("pane.focusLeft"))
		#expect(engine.handle(try keyEvent("", modifiers: [.command, .option], keyCode: 124)) == .command("pane.focusRight"))
		#expect(engine.handle(try keyEvent("", modifiers: [.command, .option], keyCode: 126)) == .command("pane.focusUp"))
		#expect(engine.handle(try keyEvent("", modifiers: [.command, .option], keyCode: 125)) == .command("pane.focusDown"))
		if profile == .emacs {
			#expect(engine.handle(try keyEvent(" ", modifiers: [.control], keyCode: 49)) == .command("emacs.setMark"))
		} else {
			#expect(engine.handle(try keyEvent(" ", modifiers: [.control], keyCode: 49)) == .command("lsp.completion"))
		}
		if profile == .vim {
			#expect(engine.handle(try keyEvent("k", modifiers: [.shift])) == .command("lsp.hover"))
		}
	}
}

@Test func bundledPlainProfileDefinesMacStandardAppBindings() throws {
	let bindings = try KeymapConfiguration.load(profile: .plain, userConfigURL: nil)
	var engine = KeymapEngine(modeStack: [.insert], bindings: bindings)

	#expect(engine.handle(try keyEvent("n", modifiers: [.command])) == .command("file.new"))
	#expect(engine.handle(try keyEvent("o", modifiers: [.command])) == .command("file.open"))
	#expect(engine.handle(try keyEvent("n", modifiers: [.command, .shift])) == .command("file.newWindow"))
	#expect(engine.handle(try keyEvent(",", modifiers: [.command])) == .command("app.settings"))
	#expect(engine.handle(try keyEvent("p", modifiers: [.command])) == .command("view.commandPalette"))
	#expect(engine.handle(try keyEvent("p", modifiers: [.command, .shift])) == .command("view.commandPalette"))
	#expect(engine.handle(try keyEvent("k", modifiers: [.command])) == .partial)
	#expect(engine.handle(try keyEvent("s", modifiers: [.command])) == .command("app.keyboardShortcuts"))
	#expect(engine.handle(try keyEvent("b", modifiers: [.command])) == .command("view.sidebar.toggle"))
	#expect(engine.handle(try keyEvent("j", modifiers: [.command])) == .command("terminal.toggle"))
	#expect(engine.handle(try keyEvent(".", modifiers: [.command, .shift])) == .command("view.hiddenFiles.toggle"))
	#expect(engine.handle(try keyEvent("\t", modifiers: [.control], keyCode: 48)) == .command("file.nextBuffer"))
	#expect(engine.handle(try keyEvent("\t", modifiers: [.control, .shift], keyCode: 48)) == .command("file.previousBuffer"))
	for index in 1 ... 9 {
		#expect(engine.handle(try keyEvent("\(index)", modifiers: [.command])) == .command("file.selectTab.\(index)"))
	}
}

@Test func bundledVimProfileDefinesNormalModeMotions() throws {
	let bindings = try KeymapConfiguration.load(profile: .vim, userConfigURL: nil)
	var engine = KeymapEngine(modeStack: [.normal], bindings: bindings)

	#expect(engine.handle(try keyEvent("3")) == .partial)
	#expect(engine.handle(try keyEvent("w")) == .command("editor.moveWordForward"))
	#expect(engine.lastCommandCount == 3)
	#expect(engine.handle(try keyEvent("w", modifiers: [.shift])) == .command("editor.moveBigWordForward"))
	#expect(engine.handle(try keyEvent("4", modifiers: [.shift])) == .command("editor.moveLineEnd"))
	#expect(engine.handle(try keyEvent("f")) == .command("editor.findCharForward"))
	#expect(engine.handle(try keyEvent("f", modifiers: [.shift])) == .command("editor.findCharBackward"))
	#expect(engine.handle(try keyEvent(";")) == .command("editor.repeatCharFind"))
}

@Test func bundledVimProfileDefinesUndoRedo() throws {
	let bindings = try KeymapConfiguration.load(profile: .vim, userConfigURL: nil)
	var engine = KeymapEngine(modeStack: [.normal], bindings: bindings)

	#expect(engine.handle(try keyEvent("u")) == .command("edit.undo"))
	#expect(engine.handle(try keyEvent("r", modifiers: [.control])) == .command("edit.redo"))
}

@Test func bundledVimProfileDefinesSearchCommands() throws {
	let bindings = try KeymapConfiguration.load(profile: .vim, userConfigURL: nil)
	var engine = KeymapEngine(modeStack: [.normal], bindings: bindings)

	#expect(engine.handle(try keyEvent("/")) == .command("vim.searchForward"))
	#expect(engine.handle(try keyEvent("/", modifiers: [.shift])) == .command("vim.searchBackward"))
	#expect(engine.handle(try keyEvent("n")) == .command("edit.findNext"))
	#expect(engine.handle(try keyEvent("n", modifiers: [.shift])) == .command("edit.findPrevious"))
}

@Test func bundledVimProfileDefinesJumpBackMark() throws {
	let bindings = try KeymapConfiguration.load(profile: .vim, userConfigURL: nil)
	var engine = KeymapEngine(modeStack: [.normal], bindings: bindings)

	#expect(engine.handle(try keyEvent("'")) == .partial)
	#expect(engine.handle(try keyEvent("'")) == .command("vim.jumpBack"))
}

@Test func bundledVimProfileDefinesMacroCommands() throws {
	let bindings = try KeymapConfiguration.load(profile: .vim, userConfigURL: nil)
	var engine = KeymapEngine(modeStack: [.normal], bindings: bindings)

	#expect(engine.handle(try keyEvent("q")) == .partial)
	#expect(engine.handle(try keyEvent("a")) == .command("vim.macro.record.a"))
	#expect(engine.handle(try keyEvent("2", modifiers: [.shift])) == .command("vim.macro.replayPrefix"))
}

@Test func bundledVimProfileDefinesTextObjects() throws {
	let bindings = try KeymapConfiguration.load(profile: .vim, userConfigURL: nil)
	var engine = KeymapEngine(modeStack: [.operatorPending], bindings: bindings)

	#expect(engine.handle(try keyEvent("i")) == .partial)
	#expect(engine.handle(try keyEvent("w")) == .command("vim.textObject.innerWord"))
	#expect(engine.handle(try keyEvent("a")) == .partial)
	#expect(engine.handle(try keyEvent("'", modifiers: [.shift])) == .command("vim.textObject.aroundDoubleQuote"))
	#expect(engine.handle(try keyEvent("i")) == .partial)
	#expect(engine.handle(try keyEvent("s")) == .command("vim.textObject.innerSentence"))
	#expect(engine.handle(try keyEvent("a")) == .partial)
	#expect(engine.handle(try keyEvent("t")) == .command("vim.textObject.aroundTag"))
}

@Test func bundledVimProfileDefinesPhase27NormalPrefixes() throws {
	let bindings = try KeymapConfiguration.load(profile: .vim, userConfigURL: nil)
	var engine = KeymapEngine(modeStack: [.normal], bindings: bindings)

	#expect(engine.handle(try keyEvent("o", modifiers: [.control])) == .command("vim.jumpOlder"))
	#expect(engine.handle(try keyEvent("i", modifiers: [.control])) == .command("vim.jumpNewer"))
	#expect(engine.handle(try keyEvent("g")) == .partial)
	#expect(engine.handle(try keyEvent("d")) == .command("lsp.definition"))
	#expect(engine.handle(try keyEvent("g")) == .partial)
	#expect(engine.handle(try keyEvent("t")) == .command("file.nextBuffer"))
	#expect(engine.handle(try keyEvent("g")) == .partial)
	#expect(engine.handle(try keyEvent("t", modifiers: [.shift])) == .command("file.previousBuffer"))
	#expect(engine.handle(try keyEvent("z")) == .partial)
	#expect(engine.handle(try keyEvent("c")) == .command("vim.fold.close"))
	#expect(engine.handle(try keyEvent("q")) == .partial)
	#expect(engine.handle(try keyEvent("/")) == .command("vim.searchHistory.forward"))
	#expect(engine.handle(try keyEvent("q")) == .partial)
	#expect(engine.handle(try keyEvent("/", modifiers: [.shift])) == .command("vim.searchHistory.backward"))
	#expect(engine.handle(try keyEvent("q")) == .partial)
	#expect(engine.handle(try keyEvent(";", modifiers: [.shift])) == .command("vim.commandHistory"))
}

@Test func bundledVimProfileDefinesMarksAndWindowPrefixes() throws {
	let bindings = try KeymapConfiguration.load(profile: .vim, userConfigURL: nil)
	var engine = KeymapEngine(modeStack: [.normal], bindings: bindings)

	#expect(engine.handle(try keyEvent("m")) == .partial)
	#expect(engine.handle(try keyEvent("a")) == .command("vim.mark.set.a"))
	#expect(engine.handle(try keyEvent("`")) == .partial)
	#expect(engine.handle(try keyEvent("z")) == .command("vim.mark.jump.z"))
	#expect(engine.handle(try keyEvent("'")) == .partial)
	#expect(engine.handle(try keyEvent("b")) == .command("vim.mark.jumpLine.b"))
	#expect(engine.handle(try keyEvent("w", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("v")) == .command("pane.splitVertical"))
	#expect(engine.handle(try keyEvent("w", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("o")) == .command("pane.closeOthers"))
}

@Test func bundledVimProfileDefinesReplaceCaseIndentAndFormat() throws {
	let bindings = try KeymapConfiguration.load(profile: .vim, userConfigURL: nil)
	var engine = KeymapEngine(modeStack: [.normal], bindings: bindings)

	#expect(engine.handle(try keyEvent("r")) == .command("vim.replace.char"))
	#expect(engine.handle(try keyEvent("r", modifiers: [.shift])) == .command("vim.replace.mode"))
	#expect(engine.handle(try keyEvent("`", modifiers: [.shift])) == .command("vim.case.toggle"))
	#expect(engine.handle(try keyEvent("g")) == .partial)
	#expect(engine.handle(try keyEvent("u")) == .command("vim.case.lowerOperator"))
	#expect(engine.handle(try keyEvent(".", modifiers: [.shift])) == .partial)
	#expect(engine.handle(try keyEvent(".", modifiers: [.shift])) == .command("vim.indent.right"))
	#expect(engine.handle(try keyEvent("=")) == .partial)
	#expect(engine.handle(try keyEvent("=")) == .command("vim.format.line"))
	#expect(engine.handle(try keyEvent("g")) == .partial)
	#expect(engine.handle(try keyEvent("q")) == .command("vim.format.reflowOperator"))
}

@Test func userKeymapConfigOverlaysSelectedProfile() throws {
	let fixture = try TemporaryKeymapFixture()
	defer { fixture.cleanUp() }
	try fixture.write("""
	[mode.insert]
	"Left" = "custom.moveLeft"
	""")

	let bindings = try KeymapConfiguration.load(profile: .plain, userConfigURL: fixture.url)
	var engine = KeymapEngine(modeStack: [.insert], bindings: bindings)
	#expect(engine.handle(try keyEvent("", keyCode: 123)) == .command("custom.moveLeft"))
}

@Test func keymapProfileParsesCommandLineFlag() throws {
	#expect(try KeymapProfile.selected(from: ["ItsyApp", "--profile=vim"]) == .vim)
	#expect(try KeymapProfile.selected(from: ["ItsyApp"]) == .plain)
}

@Test func bundledEmacsProfileDefinesStandardMotions() throws {
	let bindings = try KeymapConfiguration.load(profile: .emacs, userConfigURL: nil)
	var engine = KeymapEngine(modeStack: [.emacs], bindings: bindings)

	#expect(engine.handle(try keyEvent("f", modifiers: [.control])) == .command("editor.moveRight"))
	#expect(engine.handle(try keyEvent("b", modifiers: [.control])) == .command("editor.moveLeft"))
	#expect(engine.handle(try keyEvent("n", modifiers: [.control])) == .command("editor.moveDown"))
	#expect(engine.handle(try keyEvent("p", modifiers: [.control])) == .command("editor.moveUp"))
	#expect(engine.handle(try keyEvent("a", modifiers: [.control])) == .command("editor.moveLineStart"))
	#expect(engine.handle(try keyEvent("e", modifiers: [.control])) == .command("editor.moveLineEnd"))
	#expect(engine.handle(try keyEvent("f", modifiers: [.option])) == .command("editor.moveWordForward"))
	#expect(engine.handle(try keyEvent("b", modifiers: [.option])) == .command("editor.moveWordBackward"))
	#expect(engine.handle(try keyEvent(",", modifiers: [.option, .shift])) == .command("editor.moveBufferStart"))
	#expect(engine.handle(try keyEvent(".", modifiers: [.option, .shift])) == .command("editor.moveBufferEnd"))
	#expect(engine.handle(try keyEvent("w", modifiers: [.control])) == .command("emacs.killRegion"))
	#expect(engine.handle(try keyEvent("w", modifiers: [.option])) == .command("emacs.copyRegion"))
	#expect(engine.handle(try keyEvent("y", modifiers: [.control])) == .command("emacs.yank"))
	#expect(engine.handle(try keyEvent("y", modifiers: [.option])) == .command("emacs.yankPop"))
	#expect(engine.handle(try keyEvent("s", modifiers: [.control])) == .command("emacs.isearchForward"))
	#expect(engine.handle(try keyEvent("r", modifiers: [.control])) == .command("emacs.isearchBackward"))
	#expect(engine.handle(try keyEvent("t", modifiers: [.control])) == .command("emacs.transposeChars"))
	#expect(engine.handle(try keyEvent("t", modifiers: [.option])) == .command("emacs.transposeWords"))
	#expect(engine.handle(try keyEvent("u", modifiers: [.option])) == .command("emacs.uppercaseWord"))
	#expect(engine.handle(try keyEvent("l", modifiers: [.option])) == .command("emacs.lowercaseWord"))
	#expect(engine.handle(try keyEvent("c", modifiers: [.option])) == .command("emacs.capitalizeWord"))
	#expect(engine.handle(try keyEvent("f", modifiers: [.control, .option])) == .command("emacs.forwardSexp"))
	#expect(engine.handle(try keyEvent("b", modifiers: [.control, .option])) == .command("emacs.backwardSexp"))
	#expect(engine.handle(try keyEvent("k", modifiers: [.control, .option])) == .command("emacs.killSexp"))
	#expect(engine.handle(try keyEvent(" ", modifiers: [.control, .option], keyCode: 49)) == .command("emacs.markSexp"))
	#expect(engine.handle(try keyEvent("/", modifiers: [.control])) == .command("edit.undo"))
	#expect(engine.handle(try keyEvent("-", modifiers: [.control, .shift])) == .command("edit.redo"))
	#expect(engine.handle(try keyEvent("x", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("s", modifiers: [.control])) == .command("file.save"))
	#expect(engine.handle(try keyEvent("x", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("f", modifiers: [.control])) == .command("file.open"))
	#expect(engine.handle(try keyEvent("x", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("c", modifiers: [.control])) == .command("app.quit"))
	#expect(engine.handle(try keyEvent("x", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("x", modifiers: [.control])) == .command("emacs.exchangePointMark"))
	#expect(engine.handle(try keyEvent("x", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("b")) == .command("file.nextBuffer"))
	#expect(engine.handle(try keyEvent("x", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("k")) == .command("file.close"))
	#expect(engine.handle(try keyEvent("x", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("0")) == .command("pane.close"))
	#expect(engine.handle(try keyEvent("x", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("1")) == .command("pane.closeOthers"))
	#expect(engine.handle(try keyEvent("x", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("2")) == .command("pane.splitHorizontal"))
	#expect(engine.handle(try keyEvent("x", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("3")) == .command("pane.splitVertical"))
	#expect(engine.handle(try keyEvent("x", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("o")) == .command("pane.focusNext"))
	#expect(engine.handle(try keyEvent("x", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("9", modifiers: [.shift])) == .command("emacs.macro.start"))
	#expect(engine.handle(try keyEvent("x", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("0", modifiers: [.shift])) == .command("emacs.macro.end"))
	#expect(engine.handle(try keyEvent("x", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("e")) == .command("emacs.macro.run"))
	#expect(engine.handle(try keyEvent("x", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("r")) == .partial)
	#expect(engine.handle(try keyEvent("k")) == .command("emacs.rectangle.kill"))
	#expect(engine.handle(try keyEvent("x", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("r")) == .partial)
	#expect(engine.handle(try keyEvent("y")) == .command("emacs.rectangle.yank"))
	#expect(engine.handle(try keyEvent("x", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("r")) == .partial)
	#expect(engine.handle(try keyEvent("t")) == .command("emacs.rectangle.string"))
	#expect(engine.handle(try keyEvent("x", modifiers: [.option])) == .command("view.commandPalette"))
	#expect(engine.handle(try keyEvent("g", modifiers: [.option])) == .partial)
	#expect(engine.handle(try keyEvent("g")) == .command("nav.gotoLine"))
	#expect(engine.handle(try keyEvent("5", modifiers: [.option, .shift])) == .command("emacs.queryReplace"))
}

private func keyEvent(_ characters: String, modifiers: NSEvent.ModifierFlags = [], keyCode: UInt16 = 0) throws -> NSEvent {
	try #require(NSEvent.keyEvent(
		with: .keyDown,
		location: .zero,
		modifierFlags: modifiers,
		timestamp: 0,
		windowNumber: 0,
		context: nil,
		characters: characters,
		charactersIgnoringModifiers: characters.lowercased(),
		isARepeat: false,
		keyCode: keyCode
	))
}

private struct TemporaryKeymapFixture {
	let url: URL

	init() throws {
		let directory = FileManager.default.temporaryDirectory
			.appendingPathComponent("itsy-keymap-\(UUID().uuidString)", isDirectory: true)
		try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
		url = directory.appendingPathComponent("keys.toml")
	}

	func write(_ contents: String) throws {
		try contents.write(to: url, atomically: true, encoding: .utf8)
	}

	func cleanUp() {
		try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
	}
}
