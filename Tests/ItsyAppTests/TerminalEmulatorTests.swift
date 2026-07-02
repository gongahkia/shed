@testable import ItsyApp
import Foundation
import Testing

@Test func terminalEmulatorParsesOSCTitleWithBellAndStringTerminator() {
	let emulator = ItsyTerminalEmulator(columns: 20, rows: 3)

	emulator.feed(Data("a\u{1B}]2;Build Log\u{07}b".utf8))
	var snapshot = emulator.snapshot(scrollbackOffset: 0)

	#expect(snapshot.lines[0] == "ab")
	#expect(snapshot.windowTitle == "Build Log")

	emulator.feed(Data("\u{1B}]0;Run Tests\u{1B}\\".utf8))
	snapshot = emulator.snapshot(scrollbackOffset: 0)

	#expect(snapshot.windowTitle == "Run Tests")
}

@Test func terminalEmulatorIgnoresOSC52ClipboardPayload() {
	let emulator = ItsyTerminalEmulator(columns: 20, rows: 3)

	emulator.feed(Data("\u{1B}]2;Safe Title\u{07}".utf8))
	emulator.feed(Data("\u{1B}]52;c;Zm9v\u{07}".utf8))
	let snapshot = emulator.snapshot(scrollbackOffset: 0)

	#expect(snapshot.windowTitle == "Safe Title")
	#expect(snapshot.lines[0].isEmpty)
}

@Test func terminalEmulatorTracksBracketedPastePrivateMode() {
	let emulator = ItsyTerminalEmulator(columns: 20, rows: 3)

	emulator.feed(Data("\u{1B}[?2004h".utf8))
	#expect(emulator.snapshot(scrollbackOffset: 0).bracketedPaste)

	emulator.feed(Data("\u{1B}[?2004l".utf8))
	#expect(!emulator.snapshot(scrollbackOffset: 0).bracketedPaste)
}
