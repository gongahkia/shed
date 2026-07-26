import ItsyEditor
import Testing

@Test(arguments: insertionCases)
private func textEditInsertionMatrix(testCase: TextEditInsertionCase) {
	#expect(TextEditBehavior.insertion(
		text: testCase.text,
		content: testCase.content,
		range: testCase.range,
		configuration: testCase.configuration
	) == testCase.expected)
}

@Test(arguments: deleteBackwardCases)
private func textEditDeleteBackwardMatrix(testCase: TextEditDeleteBackwardCase) {
	#expect(TextEditBehavior.deleteBackward(
		content: testCase.content,
		range: testCase.range,
		configuration: testCase.configuration
	) == testCase.expected)
}

@Test(arguments: newlineCases)
private func textEditNewlineMatrix(testCase: TextEditNewlineCase) {
	#expect(TextEditBehavior.newline(
		content: testCase.content,
		range: testCase.range,
		providedText: testCase.providedText,
		configuration: testCase.configuration
	) == testCase.expected)
}

private struct TextEditInsertionCase: Sendable {
	let text: String
	let content: String
	let range: Range<Int>
	let configuration: TextEditBehaviorConfiguration
	let expected: TextEditOperation?
}

private struct TextEditDeleteBackwardCase: Sendable {
	let content: String
	let range: Range<Int>
	let configuration: TextEditBehaviorConfiguration
	let expected: TextEditOperation?
}

private struct TextEditNewlineCase: Sendable {
	let content: String
	let range: Range<Int>
	let providedText: String?
	let configuration: TextEditBehaviorConfiguration
	let expected: TextEditOperation?
}

private let enabled = TextEditBehaviorConfiguration(autoPairs: true, smartIndent: true, indentationUnit: "  ")
private let pairsDisabled = TextEditBehaviorConfiguration(autoPairs: false, smartIndent: true, indentationUnit: "  ")
private let indentDisabled = TextEditBehaviorConfiguration(autoPairs: true, smartIndent: false, indentationUnit: "  ")

private let insertionCases: [TextEditInsertionCase] = [
	.init(text: "(", content: "", range: 0 ..< 0, configuration: enabled, expected: .replace(range: 0 ..< 0, text: "()", selection: 1 ..< 1)),
	.init(text: "{", content: "ab", range: 0 ..< 2, configuration: enabled, expected: .replace(range: 0 ..< 2, text: "{ab}", selection: 1 ..< 3)),
	.init(text: ")", content: "()", range: 1 ..< 1, configuration: enabled, expected: .select(2 ..< 2)),
	.init(text: "'", content: "''", range: 1 ..< 1, configuration: enabled, expected: .select(2 ..< 2)),
	.init(text: "\"", content: "word", range: 4 ..< 4, configuration: enabled, expected: nil),
	.init(text: "\"", content: "\\", range: 1 ..< 1, configuration: enabled, expected: nil),
	.init(text: "\"", content: "\\\\", range: 2 ..< 2, configuration: enabled, expected: .replace(range: 2 ..< 2, text: "\"\"", selection: 3 ..< 3)),
	.init(text: "(", content: "", range: 0 ..< 0, configuration: pairsDisabled, expected: nil),
	.init(text: "ab", content: "", range: 0 ..< 0, configuration: enabled, expected: nil),
	.init(text: "(", content: "x", range: 2 ..< 2, configuration: enabled, expected: nil),
]

private let deleteBackwardCases: [TextEditDeleteBackwardCase] = [
	.init(content: "[]", range: 1 ..< 1, configuration: enabled, expected: .replace(range: 0 ..< 2, text: "", selection: 0 ..< 0)),
	.init(content: "\"\"", range: 1 ..< 1, configuration: enabled, expected: .replace(range: 0 ..< 2, text: "", selection: 0 ..< 0)),
	.init(content: "(x)", range: 1 ..< 1, configuration: enabled, expected: nil),
	.init(content: "()", range: 0 ..< 0, configuration: enabled, expected: nil),
	.init(content: "()", range: 0 ..< 1, configuration: enabled, expected: nil),
	.init(content: "()", range: 1 ..< 1, configuration: pairsDisabled, expected: nil),
	.init(content: "()", range: 3 ..< 3, configuration: enabled, expected: nil),
]

private let newlineCases: [TextEditNewlineCase] = [
	.init(content: "  value", range: 7 ..< 7, providedText: nil, configuration: enabled, expected: .replace(range: 7 ..< 7, text: "\n  ", selection: nil)),
	.init(content: "\tvalue", range: 6 ..< 6, providedText: nil, configuration: enabled, expected: .replace(range: 6 ..< 6, text: "\n\t", selection: nil)),
	.init(content: "  {}", range: 3 ..< 3, providedText: "\nignored", configuration: enabled, expected: .replace(range: 3 ..< 3, text: "\n    \n  ", selection: 8 ..< 8)),
	.init(content: "value", range: 5 ..< 5, providedText: "\n// ", configuration: enabled, expected: .replace(range: 5 ..< 5, text: "\n// ", selection: nil)),
	.init(content: "value", range: 5 ..< 5, providedText: nil, configuration: enabled, expected: nil),
	.init(content: "  value", range: 7 ..< 7, providedText: "\n  ", configuration: indentDisabled, expected: nil),
	.init(content: "value", range: 6 ..< 6, providedText: nil, configuration: enabled, expected: nil),
]
