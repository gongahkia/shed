@testable import ItsyApp
import ItsyEditor
import ItsyLSP
import Testing
import AppKit

@Test func snippetCompletionMapperFiltersByPrefixAndMarksLocalItems() {
	let snippets = [
		SnippetDefinition(name: "For Loop", prefixes: ["for", "foreach"], body: "for ${1:item} in ${2:items} {\n\t$0\n}", description: "Loop"),
		SnippetDefinition(name: "Print", prefixes: ["print"], body: "print($1)"),
	]

	let items = SnippetCompletionMapper.completionItems(from: snippets, matching: "fore")

	#expect(items.map(\.label) == ["foreach"])
	#expect(items[0].detail == "Loop")
	#expect(items[0].filterText == "foreach")
	#expect(items[0].insertTextFormat == .snippet)
	#expect(items[0].insertText == "for ${1:item} in ${2:items} {\n\t$0\n}")
	#expect(SnippetCompletionMarker.isSnippet(items[0]))
}

@Test func snippetTabStopSessionNavigatesStopsAndAdjustsLaterRanges() {
	var session = SnippetTabStopSession(tabStopRanges: [
		1: [6 ..< 11],
		2: [15 ..< 20],
		0: [24 ..< 24],
	])

	#expect(session.currentRanges() == [6 ..< 11])
	#expect(session.move(direction: 1, currentSelectionRanges: [6 ..< 9]) == .ranges([13 ..< 18]))
	#expect(session.move(direction: 1, currentSelectionRanges: [13 ..< 16]) == .ranges([20 ..< 20]))
	#expect(session.move(direction: -1, currentSelectionRanges: [20 ..< 20]) == .ranges([13 ..< 16]))
}

@Test func snippetTabStopSessionTracksEveryLinkedPlaceholderEdit() {
	var session = SnippetTabStopSession(tabStopRanges: [
		1: [0 ..< 1, 2 ..< 3],
		2: [4 ..< 5],
		0: [6 ..< 6],
	])

	#expect(session.move(direction: 1, currentSelectionRanges: [0 ..< 2, 3 ..< 5]) == .ranges([6 ..< 7]))
	#expect(session.move(direction: 1, currentSelectionRanges: [6 ..< 7]) == .ranges([8 ..< 8]))
	#expect(session.move(direction: 1, currentSelectionRanges: [8 ..< 8]) == .finished)
}

@Test func snippetTabStopSessionAbandonsUnexpectedAdditionalCursors() {
	var session = SnippetTabStopSession(tabStopRanges: [1: [0 ..< 1], 0: [2 ..< 2]])
	#expect(session.move(direction: 1, currentSelectionRanges: [0 ..< 1, 2 ..< 3]) == .abandoned)
}

@MainActor
@Test func completionPopupPlacementStaysInsideTheVisibleFrame() {
	let frame = CompletionPopupController.panelFrame(
		caret: NSRect(x: 190, y: 5, width: 2, height: 14),
		preferredSize: NSSize(width: 340, height: 220),
		visibleFrame: NSRect(x: 0, y: 0, width: 200, height: 100)
	)
	#expect(frame == NSRect(x: 0, y: 0, width: 200, height: 100))
}
