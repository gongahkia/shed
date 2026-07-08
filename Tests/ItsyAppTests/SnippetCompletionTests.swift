@testable import ItsyApp
import ItsyEditor
import ItsyLSP
import Testing

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
	#expect(session.move(direction: 1, currentSelectionRanges: [6 ..< 9]) == [13 ..< 18])
	#expect(session.move(direction: 1, currentSelectionRanges: [13 ..< 16]) == [20 ..< 20])
	#expect(session.move(direction: -1, currentSelectionRanges: [20 ..< 20]) == [13 ..< 16])
}
