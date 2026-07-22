import AppKit
import Foundation
@testable import ItsyApp
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

@Test func terminalEmulatorRetainsSGRAttributesOnCells() {
	let emulator = ItsyTerminalEmulator(columns: 20, rows: 3)

	emulator.feed(Data("a\u{1B}[31;1mb\u{1B}[0mc\u{1B}[38;5;196md\u{1B}[48;2;1;2;3me".utf8))
	let snapshot = emulator.snapshot(scrollbackOffset: 0)

	#expect(snapshot.lines[0] == "abcde")
	#expect(snapshot.cells[0][1].attributes.foreground == .ansi(1))
	#expect(snapshot.cells[0][1].attributes.bold)
	#expect(snapshot.cells[0][2].attributes == TerminalTextAttributes())
	#expect(snapshot.cells[0][3].attributes.foreground == .indexed(196))
	#expect(snapshot.cells[0][4].attributes.background == .rgb(TerminalRGB(red: 1, green: 2, blue: 3)))
}

@Test func terminalEmulatorParsesOSCMetadataPaletteAndHyperlinks() {
	let emulator = ItsyTerminalEmulator(columns: 20, rows: 3)

	emulator.feed(Data("\u{1B}]7;file://host/tmp/project\u{07}".utf8))
	emulator.feed(Data("\u{1B}]4;1;rgb:ff/00/11;2;#010203\u{07}".utf8))
	emulator.feed(Data("\u{1B}]10;#aabbcc\u{07}\u{1B}]11;rgb:0000/ffff/8000\u{07}\u{1B}]133;A\u{07}".utf8))
	emulator.feed(Data("\u{1B}]8;;https://example.com\u{07}x\u{1B}[31my\u{1B}]8;;\u{07}z".utf8))
	let snapshot = emulator.snapshot(scrollbackOffset: 0)

	#expect(snapshot.currentDirectory == "file://host/tmp/project")
	#expect(snapshot.palette[1] == TerminalRGB(red: 255, green: 0, blue: 17))
	#expect(snapshot.palette[2] == TerminalRGB(red: 1, green: 2, blue: 3))
	#expect(snapshot.paletteOverrideIndexes == [1, 2])
	#expect(snapshot.defaultForeground == TerminalRGB(red: 170, green: 187, blue: 204))
	#expect(snapshot.defaultBackground == TerminalRGB(red: 0, green: 255, blue: 127))
	#expect(snapshot.promptMark == "A")
	#expect(snapshot.cells[0][0].attributes.hyperlink == "https://example.com")
	#expect(snapshot.cells[0][1].attributes.hyperlink == "https://example.com")
	#expect(snapshot.cells[0][1].attributes.foreground == .ansi(1))
	#expect(snapshot.cells[0][2].attributes.hyperlink == nil)
}

@Test func terminalEmulatorTracksMousePrivateModes() {
	let emulator = ItsyTerminalEmulator(columns: 20, rows: 3)

	emulator.feed(Data("\u{1B}[?1000;1006h".utf8))
	var snapshot = emulator.snapshot(scrollbackOffset: 0)

	#expect(snapshot.mouseTrackingMode == .normal)
	#expect(snapshot.sgrMouseMode)

	emulator.feed(Data("\u{1B}[?1002h".utf8))
	snapshot = emulator.snapshot(scrollbackOffset: 0)
	#expect(snapshot.mouseTrackingMode == .button)

	emulator.feed(Data("\u{1B}[?1003h".utf8))
	snapshot = emulator.snapshot(scrollbackOffset: 0)
	#expect(snapshot.mouseTrackingMode == .any)

	emulator.feed(Data("\u{1B}[?1003;1006l".utf8))
	snapshot = emulator.snapshot(scrollbackOffset: 0)
	#expect(snapshot.mouseTrackingMode == .none)
	#expect(!snapshot.sgrMouseMode)
}

@Test @MainActor func terminalViewEncodesSGRMouseInput() {
	let view = ItsyTerminalView(frame: NSRect(x: 0, y: 0, width: 240, height: 120))

	view.ingest(Data("\u{1B}[?1000;1006h".utf8))
	let data = view.encodedMouseInput(button: 0, row: 3, column: 2, pressed: true)

	#expect(String(decoding: data ?? Data(), as: UTF8.self) == "\u{1B}[<0;3;4M")
}

@Test @MainActor func terminalViewWrapsBracketedPastePayloads() {
	let view = ItsyTerminalView(frame: NSRect(x: 0, y: 0, width: 240, height: 120))
	var input = Data()

	view.onInput = { input = $0 }
	view.confirmPaste = { _ in true }
	view.ingest(Data("\u{1B}[?2004h".utf8))
	NSPasteboard.general.clearContents()
	NSPasteboard.general.setString("one\ntwo", forType: .string)
	view.paste(nil)

	#expect(String(decoding: input, as: UTF8.self) == "\u{1B}[200~one\ntwo\u{1B}[201~")
}

@Test @MainActor func terminalViewAppliesThemeBackground() {
	let view = ItsyTerminalView(frame: NSRect(x: 0, y: 0, width: 240, height: 120))
	let theme = TerminalThemePalette(
		background: NSColor(srgbRed: 0.10, green: 0.11, blue: 0.12, alpha: 1),
		foreground: NSColor(srgbRed: 0.80, green: 0.81, blue: 0.82, alpha: 1),
		cursor: NSColor(srgbRed: 0.90, green: 0.20, blue: 0.10, alpha: 1),
		ansi: [:]
	)

	view.applyTerminalTheme(theme)

	#expect(view.layer?.backgroundColor == theme.background.cgColor)
}

@Test func terminalPaneLayoutRoundTrips() {
	let layout = TerminalPaneLayout.split(vertical: true, children: [
		.leaf,
		.split(vertical: false, children: [.leaf, .leaf]),
	])

	#expect(layout.encoded == "V[L,H[L,L]]")
	#expect(TerminalPaneLayout.decode(layout.encoded) == layout)
	#expect(TerminalPaneLayout.decode("V[L") == nil)
}

@Test func terminalWorkspaceStateCodableRoundTrips() throws {
	let state = TerminalWorkspaceState(
		selectedTabIndex: 1,
		tabs: [
			TerminalTabState(currentDirectoryPath: "/tmp/one", layout: "L"),
			TerminalTabState(currentDirectoryPath: "/tmp/two", layout: "H[L,L]"),
		]
	)
	let data = try JSONEncoder().encode(state)

	#expect(try JSONDecoder().decode(TerminalWorkspaceState.self, from: data) == state)
}

@Test @MainActor func terminalViewParsesOSC7CurrentDirectory() {
	let view = ItsyTerminalView(frame: NSRect(x: 0, y: 0, width: 240, height: 120))
	let cwd = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
		.appendingPathComponent("Itsy Terminal CWD", isDirectory: true)

	view.ingest(Data("\u{1B}]7;\(cwd.absoluteString)\u{07}".utf8))

	#expect(view.currentDirectoryURL?.path == cwd.standardizedFileURL.path)
}

@Test @MainActor func terminalViewSearchPreservesSelectionForSameQuery() {
	let view = ItsyTerminalView(frame: NSRect(x: 0, y: 0, width: 240, height: 120))

	view.ingest(Data("alpha beta\r\nAlpha gamma\r\n".utf8))

	#expect(view.setSearch(query: "alpha", regex: false) == 2)
	#expect(view.findNextSearchMatch() == 1)
	#expect(view.setSearch(query: "alpha", regex: false) == 2)
	#expect(view.findNextSearchMatch() == 0)
	#expect(view.setSearch(query: "a[a-z]+a", regex: true) == 3)
}
