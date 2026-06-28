import AppKit
import PicoKeymap
import Testing

@Test func keymapEngineResolvesSingleKeyCommand() throws {
	let engine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("j")], commandID: "moveDown"),
	])

	let result = engine.handle(try keyEvent("j"))
	#expect(result == .command("moveDown"))
	#expect(engine.pendingChord.isEmpty)
}

@Test func keymapEngineTracksPendingChord() throws {
	let engine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("g"), Key("g")], commandID: "bufferStart"),
	])

	#expect(engine.handle(try keyEvent("g")) == .partial)
	#expect(engine.pendingChord == [Key("g")])
	#expect(engine.handle(try keyEvent("g")) == .command("bufferStart"))
	#expect(engine.pendingChord.isEmpty)
}

@Test func keymapEngineFallsThroughUnknownKeys() throws {
	let engine = KeymapEngine(modeStack: [.insert], bindings: [
		KeyBinding(mode: .normal, chord: [Key("j")], commandID: "moveDown"),
	])

	#expect(engine.handle(try keyEvent("j")) == .passthrough)
}

@Test func keymapEngineClearsPendingChordOnEscape() throws {
	let engine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("g"), Key("g")], commandID: "bufferStart"),
	])

	#expect(engine.handle(try keyEvent("g")) == .partial)
	#expect(engine.handle(try keyEvent("\u{1b}", keyCode: 53)) == .consumed)
	#expect(engine.pendingChord.isEmpty)
}

@Test func keymapEngineTracksNormalModeCountPrefix() throws {
	let engine = KeymapEngine(modeStack: [.normal], bindings: [
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
	let engine = KeymapEngine(modeStack: [.normal], bindings: [
		KeyBinding(mode: .normal, chord: [Key("j")], commandID: "down"),
	])

	#expect(engine.handle(try keyEvent("1")) == .partial)
	#expect(engine.handle(try keyEvent("0")) == .partial)
	#expect(engine.handle(try keyEvent("j")) == .command("down"))
	#expect(engine.lastCommandCount == 10)
}

@Test func keymapEngineTracksEmacsUniversalArgument() throws {
	let engine = KeymapEngine(modeStack: [.emacs], bindings: [
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
	let engine = KeymapEngine(modeStack: [.emacs], bindings: [
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
	let engine = KeymapEngine(modeStack: [.emacs], bindings: bindings)

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
	let engine = KeymapEngine(modeStack: [.normal], bindings: bindings)

	#expect(engine.handle(try keyEvent("j")) == .partial)
	#expect(engine.handle(try keyEvent("k")) == .command("exitInsert"))
	#expect(engine.handle(try keyEvent("", keyCode: 123)) == .command("moveLeft"))
	engine.setMode(.emacs)
	#expect(engine.handle(try keyEvent("f", modifiers: [.control])) == .command("forwardChar"))
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
		let engine = KeymapEngine(modeStack: [profile == .vim ? .normal : profile == .emacs ? .emacs : .insert], bindings: bindings)
		#expect(engine.handle(try keyEvent("d", modifiers: [.command])) == .command("editor.addNextSelection"))
		#expect(engine.handle(try keyEvent("g", modifiers: [.command, .control])) == .command("edit.selectAllFindMatches"))
		#expect(engine.handle(try keyEvent("w", modifiers: [.command])) == .command("pane.close"))
		#expect(engine.handle(try keyEvent("\\", modifiers: [.command])) == .command("pane.splitHorizontal"))
		#expect(engine.handle(try keyEvent("\\", modifiers: [.command, .option])) == .command("pane.splitVertical"))
		#expect(engine.handle(try keyEvent("", modifiers: [.command, .option], keyCode: 123)) == .command("pane.focusLeft"))
		#expect(engine.handle(try keyEvent("", modifiers: [.command, .option], keyCode: 124)) == .command("pane.focusRight"))
		#expect(engine.handle(try keyEvent("", modifiers: [.command, .option], keyCode: 126)) == .command("pane.focusUp"))
		#expect(engine.handle(try keyEvent("", modifiers: [.command, .option], keyCode: 125)) == .command("pane.focusDown"))
	}
}

@Test func bundledVimProfileDefinesNormalModeMotions() throws {
	let bindings = try KeymapConfiguration.load(profile: .vim, userConfigURL: nil)
	let engine = KeymapEngine(modeStack: [.normal], bindings: bindings)

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
	let engine = KeymapEngine(modeStack: [.normal], bindings: bindings)

	#expect(engine.handle(try keyEvent("u")) == .command("edit.undo"))
	#expect(engine.handle(try keyEvent("r", modifiers: [.control])) == .command("edit.redo"))
}

@Test func bundledVimProfileDefinesTextObjects() throws {
	let bindings = try KeymapConfiguration.load(profile: .vim, userConfigURL: nil)
	let engine = KeymapEngine(modeStack: [.operatorPending], bindings: bindings)

	#expect(engine.handle(try keyEvent("i")) == .partial)
	#expect(engine.handle(try keyEvent("w")) == .command("vim.textObject.innerWord"))
	#expect(engine.handle(try keyEvent("a")) == .partial)
	#expect(engine.handle(try keyEvent("'", modifiers: [.shift])) == .command("vim.textObject.aroundDoubleQuote"))
}

@Test func userKeymapConfigOverlaysSelectedProfile() throws {
	let fixture = try TemporaryKeymapFixture()
	defer { fixture.cleanUp() }
	try fixture.write("""
	[mode.insert]
	"Left" = "custom.moveLeft"
	""")

	let bindings = try KeymapConfiguration.load(profile: .plain, userConfigURL: fixture.url)
	let engine = KeymapEngine(modeStack: [.insert], bindings: bindings)
	#expect(engine.handle(try keyEvent("", keyCode: 123)) == .command("custom.moveLeft"))
}

@Test func keymapProfileParsesCommandLineFlag() throws {
	#expect(try KeymapProfile.selected(from: ["PicoApp", "--profile=vim"]) == .vim)
	#expect(try KeymapProfile.selected(from: ["PicoApp"]) == .plain)
}

@Test func bundledEmacsProfileDefinesStandardMotions() throws {
	let bindings = try KeymapConfiguration.load(profile: .emacs, userConfigURL: nil)
	let engine = KeymapEngine(modeStack: [.emacs], bindings: bindings)

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
	#expect(engine.handle(try keyEvent("x", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("s", modifiers: [.control])) == .command("file.save"))
	#expect(engine.handle(try keyEvent("x", modifiers: [.control])) == .partial)
	#expect(engine.handle(try keyEvent("f", modifiers: [.control])) == .command("file.open"))
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
			.appendingPathComponent("pico-keymap-\(UUID().uuidString)", isDirectory: true)
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
