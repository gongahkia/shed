import ItsyEditor
import Testing

@Test(arguments: indentationCases)
private func indentationDetectionMatrix(testCase: IndentationCase) {
	#expect(IndentationDetector.indentationUnit(in: testCase.text, fallback: testCase.fallback) == testCase.expected)
}

@Test func indentationDetectionControlsEmptyPairIndentation() {
	let content = "  if true {\n    ()"
	let caret = content.utf8.count - 1
	let detected = TextEditBehaviorConfiguration(indentationUnit: "\t", detectIndentation: true)
	let configured = TextEditBehaviorConfiguration(indentationUnit: "\t", detectIndentation: false)

	#expect(TextEditBehavior.newline(content: content, range: caret ..< caret, providedText: nil, configuration: detected) == .replace(range: caret ..< caret, text: "\n      \n    ", selection: 24 ..< 24))
	#expect(TextEditBehavior.newline(content: content, range: caret ..< caret, providedText: nil, configuration: configured) == .replace(range: caret ..< caret, text: "\n    \t\n    ", selection: 23 ..< 23))
}

private struct IndentationCase: Sendable {
	let text: String
	let fallback: String
	let expected: String
}

private let indentationCases: [IndentationCase] = [
	.init(text: "if true:\n\twork()\n\tmore()", fallback: "    ", expected: "\t"),
	.init(text: "if true:\n  work()\n    more()", fallback: "\t", expected: "  "),
	.init(text: "if true:\n        work()\n                more()", fallback: "\t", expected: "        "),
	.init(text: "root\n  child\n\tchild", fallback: "    ", expected: "    "),
	.init(text: "root\n  \n", fallback: "\t", expected: "\t"),
]
