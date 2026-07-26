import ItsyEditor
import Testing

@Test func textEditBehaviorPairsWrapsSkipsAndDeletes() {
	let configuration = TextEditBehaviorConfiguration(autoPairs: true, smartIndent: true, indentationUnit: "  ")
	#expect(TextEditBehavior.insertion(text: "(", content: "", range: 0 ..< 0, configuration: configuration) == .replace(range: 0 ..< 0, text: "()", selection: 1 ..< 1))
	#expect(TextEditBehavior.insertion(text: "[", content: "value", range: 0 ..< 5, configuration: configuration) == .replace(range: 0 ..< 5, text: "[value]", selection: 1 ..< 6))
	#expect(TextEditBehavior.insertion(text: ")", content: "()", range: 1 ..< 1, configuration: configuration) == .select(2 ..< 2))
	#expect(TextEditBehavior.insertion(text: "\"", content: "\"\"", range: 1 ..< 1, configuration: configuration) == .select(2 ..< 2))
	#expect(TextEditBehavior.deleteBackward(content: "()", range: 1 ..< 1, configuration: configuration) == .replace(range: 0 ..< 2, text: "", selection: 0 ..< 0))
}

@Test func textEditBehaviorLeavesQuotesInsideWordsAndHonorsDisabledModes() {
	let enabled = TextEditBehaviorConfiguration(autoPairs: true, smartIndent: true)
	let disabled = TextEditBehaviorConfiguration(autoPairs: false, smartIndent: false)
	#expect(TextEditBehavior.insertion(text: "\"", content: "name", range: 4 ..< 4, configuration: enabled) == nil)
	#expect(TextEditBehavior.insertion(text: "(", content: "", range: 0 ..< 0, configuration: disabled) == nil)
	#expect(TextEditBehavior.deleteBackward(content: "()", range: 1 ..< 1, configuration: disabled) == nil)
	#expect(TextEditBehavior.newline(content: "  value", range: 7 ..< 7, providedText: nil, configuration: disabled) == nil)
}

@Test func textEditBehaviorIndentsNewlinesAndExpandsEmptyPairs() {
	let configuration = TextEditBehaviorConfiguration(autoPairs: true, smartIndent: true, indentationUnit: "  ")
	#expect(TextEditBehavior.newline(content: "  value", range: 7 ..< 7, providedText: nil, configuration: configuration) == .replace(range: 7 ..< 7, text: "\n  ", selection: nil))
	#expect(TextEditBehavior.newline(content: "  {}", range: 3 ..< 3, providedText: "\n    ", configuration: configuration) == .replace(range: 3 ..< 3, text: "\n    \n  ", selection: 8 ..< 8))
}

@Test func textEditBehaviorSurroundsSelectionsAndRejectsInvalidPairs() {
	let enabled = TextEditBehaviorConfiguration(autoPairs: true)
	let disabled = TextEditBehaviorConfiguration(autoPairs: false)
	#expect(TextEditBehavior.surround(content: "π", range: 0 ..< 2, opening: "[", configuration: enabled) == .replace(range: 0 ..< 2, text: "[π]", selection: 1 ..< 3))
	#expect(TextEditBehavior.surround(content: "value", range: 0 ..< 5, opening: "\"", configuration: enabled) == .replace(range: 0 ..< 5, text: "\"value\"", selection: 1 ..< 6))
	#expect(TextEditBehavior.surround(content: "value", range: 0 ..< 5, opening: ")", configuration: enabled) == nil)
	#expect(TextEditBehavior.surround(content: "value", range: 0 ..< 5, opening: "(", configuration: disabled) == nil)
	#expect(TextEditBehavior.insertion(text: ")", content: ")", range: 0 ..< 0, configuration: enabled) == nil)
}
