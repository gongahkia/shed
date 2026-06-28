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

@Test func keymapEngineNormalizesModifierKeys() throws {
	let engine = KeymapEngine(modeStack: [.emacs], bindings: [
		KeyBinding(mode: .emacs, chord: [Key("f", modifiers: .control)], commandID: "forwardChar"),
	])

	let result = engine.handle(try keyEvent("f", modifiers: [.control]))
	#expect(result == .command("forwardChar"))
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
