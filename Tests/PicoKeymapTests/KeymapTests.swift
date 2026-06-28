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
