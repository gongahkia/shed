import AppKit
import Foundation
@testable import ItsyApp
import Testing

@Test func terminalPastePolicyRequiresConfirmationForMultilineAndControlContent() {
	#expect(!TerminalPastePolicy.risk(for: "git status").requiresConfirmation)
	#expect(TerminalPastePolicy.risk(for: "git add .\ngit commit").lineCount == 2)
	#expect(TerminalPastePolicy.risk(for: "git add .\ngit commit").requiresConfirmation)
	#expect(TerminalPastePolicy.risk(for: "\u{1B}[2Jclear").containsControlCharacters)
}

@Test func terminalLinkDetectorHandlesURLsAndPathsWithSpacesAndLocations() {
	let root = URL(fileURLWithPath: "/tmp/itsy terminal project", isDirectory: true)
	let text = "error /tmp/Itsy Project/Sources/App.swift:12:8 https://example.test/docs ./Tests/My Test.swift:4"
	let locations = TerminalLinkDetector.locations(in: text, relativeTo: root)

	#expect(locations.contains(TerminalOpenLocation(
		url: URL(fileURLWithPath: "/tmp/Itsy Project/Sources/App.swift"),
		line: 12,
		column: 8
	)))
	#expect(locations.contains(TerminalOpenLocation(
		url: root.appendingPathComponent("./Tests/My Test.swift").standardizedFileURL,
		line: 4,
		column: nil
	)))
	#expect(locations.contains(TerminalOpenLocation(url: URL(string: "https://example.test/docs")!, line: nil, column: nil)))
}

@Test @MainActor func terminalViewCopiesTextConfirmsRiskyPastesAndOpensOnlyRequestedLinks() {
	let view = ItsyTerminalView(frame: NSRect(x: 0, y: 0, width: 640, height: 160))
	var input = Data()
	var opened: [TerminalOpenLocation] = []
	view.onInput = { input.append($0) }
	view.onOpenLocation = { opened.append($0) }
	view.ingest(Data("copy source\r\n/tmp/Project With Spaces/App.swift:9:2".utf8))

	view.copy(nil)
	#expect(NSPasteboard.general.string(forType: .string)?.contains("copy source") == true)

	view.confirmPaste = { _ in false }
	view.paste("first\nsecond")
	#expect(input.isEmpty)
	view.confirmPaste = { _ in true }
	view.ingest(Data("\u{1B}[?2004h".utf8))
	view.paste("first\nsecond")
	#expect(String(decoding: input, as: UTF8.self) == "\u{1B}[200~first\nsecond\u{1B}[201~")

	#expect(view.openLocation(at: (row: 1, column: 8)))
	#expect(opened == [TerminalOpenLocation(
		url: URL(fileURLWithPath: "/tmp/Project With Spaces/App.swift"),
		line: 9,
		column: 2
	)])
}
