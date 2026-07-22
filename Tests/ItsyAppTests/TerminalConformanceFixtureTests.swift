import Foundation
@testable import ItsyApp
import Testing

@Test func terminalCompilerFixtureRetainsStylesAndScrollback() throws {
	let emulator = ItsyTerminalEmulator(columns: 120, rows: 2, maxScrollbackLines: 8)
	let output = try terminalFixture("compiler")
	emulator.feed(output + Data("\r\n".utf8) + output)
	let snapshot = emulator.snapshot(scrollbackOffset: 2)

	#expect(snapshot.lines.joined(separator: "\n").contains("error:"))
	#expect(snapshot.cells.flatMap(\.self).contains { $0.attributes.foreground == .ansi(1) })
	#expect(snapshot.cells.flatMap(\.self).contains { $0.attributes.bold })
}

@Test func terminalGitFixturePreservesHyperlinksAndColors() throws {
	let emulator = ItsyTerminalEmulator(columns: 120, rows: 4)
	emulator.feed(try terminalFixture("git"))
	let snapshot = emulator.snapshot(scrollbackOffset: 0)

	#expect(snapshot.lines[0].contains("feature/login"))
	#expect(snapshot.cells.flatMap(\.self).contains { $0.attributes.hyperlink == "https://example.test/compare/3f4c2a1" })
	#expect(snapshot.cells.flatMap(\.self).contains { $0.attributes.foreground == .ansi(2) })
}

@Test func terminalTestRunnerFixtureHandlesCarriageReturnAndErase() throws {
	let emulator = ItsyTerminalEmulator(columns: 80, rows: 4)
	emulator.feed(try terminalFixture("test-runner"))
	let snapshot = emulator.snapshot(scrollbackOffset: 0)

	#expect(snapshot.lines[0] == "✓ 4 tests passed")
	#expect(snapshot.lines[1] == "Executed in 0.18s")
}

@Test func terminalFullscreenFixtureRestoresNormalScreenAndCursor() throws {
	let fullOutput = try terminalFixtureText("fullscreen")
	let leaveAlternateScreen = "\u{1B}[?25h\u{1B}[?1049l\n"
	let emulator = ItsyTerminalEmulator(columns: 80, rows: 6)
	emulator.feed(Data(fullOutput.replacingOccurrences(of: leaveAlternateScreen, with: "").utf8))
	var snapshot = emulator.snapshot(scrollbackOffset: 0)

	#expect(snapshot.alternateScreen)
	#expect(!snapshot.cursorVisible)
	#expect(snapshot.lines[0] == "TEST DASHBOARD")

	emulator.feed(Data(leaveAlternateScreen.utf8))
	snapshot = emulator.snapshot(scrollbackOffset: 0)
	#expect(!snapshot.alternateScreen)
	#expect(snapshot.cursorVisible)
	#expect(snapshot.lines[0] == "shell ready")
}

@Test func terminalEmulatorAccountsForWideAndCombiningCharacters() {
	let emulator = ItsyTerminalEmulator(columns: 6, rows: 2)
	emulator.feed(Data("A界e\u{301}🙂".utf8))
	let snapshot = emulator.snapshot(scrollbackOffset: 0)

	#expect(snapshot.lines[0] == "A界é🙂")
	#expect(snapshot.cells[0][2].isContinuation)
	#expect(snapshot.cells[0][5].isContinuation)
	#expect(snapshot.cursorColumn == 6)
}

@Test func terminalUnsupportedSequencesStayVisibleWithoutDamagingText() {
	let emulator = ItsyTerminalEmulator(columns: 40, rows: 2)
	emulator.feed(Data("before\u{1B}[999qafter".utf8))
	let snapshot = emulator.snapshot(scrollbackOffset: 0)

	#expect(snapshot.lines[0] == "before�after")
}

private func terminalFixture(_ name: String) throws -> Data {
	Data(try terminalFixtureText(name).utf8)
}

private func terminalFixtureText(_ name: String) throws -> String {
	let url = try #require(Bundle.module.url(forResource: name, withExtension: "ansi", subdirectory: "Fixtures/terminal"))
	return try String(contentsOf: url, encoding: .utf8)
		.replacingOccurrences(of: "<ESC>", with: "\u{1B}")
		.replacingOccurrences(of: "<CR>", with: "\r")
}
