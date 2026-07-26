import Foundation
import ItsyLSP

public struct LSPSnippetExpansion: Equatable, Sendable {
	public var text: String
	public var tabStops: [Int: [Range<Int>]]

	public init(text: String, tabStops: [Int: [Range<Int>]]) {
		self.text = text
		self.tabStops = tabStops
	}

	public var firstTabStopRanges: [Range<Int>] {
		let key = tabStops.keys.filter { $0 > 0 }.min() ?? 0
		return tabStops[key] ?? []
	}
}

public enum LSPSnippetExpander {
	public static func expand(_ snippet: String) -> LSPSnippetExpansion {
		var parser = Parser(snippet: snippet)
		_ = parser.parse()
		return LSPSnippetExpansion(text: parser.output, tabStops: parser.tabStops)
	}

	private struct Parser {
		let snippet: String
		var index: String.Index
		var output = ""
		var tabStops: [Int: [Range<Int>]] = [:]

		init(snippet: String) {
			self.snippet = snippet
			index = snippet.startIndex
		}

		mutating func parse(until terminator: Character? = nil) -> Bool {
			while index < snippet.endIndex {
				let character = snippet[index]
				if character == terminator {
					index = snippet.index(after: index)
					return true
				}
				if character == "\\" {
					appendEscapedCharacter()
					continue
				}
				if character == "$", parseDollar() {
					continue
				}
				output.append(character)
				index = snippet.index(after: index)
			}
			return terminator == nil
		}

		private mutating func parseDollar() -> Bool {
			let dollar = index
			let next = snippet.index(after: index)
			guard next < snippet.endIndex else {
				return false
			}
			if let value = number(from: next) {
				tabStops[value.value, default: []].append(output.utf8.count ..< output.utf8.count)
				index = value.index
				return true
			}
			guard snippet[next] == "{" else {
				return false
			}
			let savedOutput = output
			let savedTabStops = tabStops
			index = snippet.index(after: next)
			guard parseBraced() else {
				output = savedOutput
				tabStops = savedTabStops
				index = dollar
				return false
			}
			return true
		}

		private mutating func parseBraced() -> Bool {
			guard let parsed = number(from: index) else {
				return false
			}
			index = parsed.index
			guard index < snippet.endIndex else {
				return false
			}
			switch snippet[index] {
			case "}":
				index = snippet.index(after: index)
				tabStops[parsed.value, default: []].append(output.utf8.count ..< output.utf8.count)
				return true
			case ":":
				index = snippet.index(after: index)
				let start = output.utf8.count
				guard parse(until: "}") else {
					return false
				}
				tabStops[parsed.value, default: []].append(start ..< output.utf8.count)
				return true
			case "|":
				index = snippet.index(after: index)
				return parseChoice(tabStop: parsed.value)
			default:
				return false
			}
		}

		private mutating func parseChoice(tabStop: Int) -> Bool {
			let start = output.utf8.count
			var selectedFirst = true
			while index < snippet.endIndex {
				let character = snippet[index]
				if character == "\\" {
					if selectedFirst {
						appendEscapedCharacter()
					} else {
						skipEscapedCharacter()
					}
					continue
				}
				if character == "," {
					selectedFirst = false
					index = snippet.index(after: index)
					continue
				}
				if character == "|" {
					let next = snippet.index(after: index)
					guard next < snippet.endIndex, snippet[next] == "}" else {
						return false
					}
					index = snippet.index(after: next)
					tabStops[tabStop, default: []].append(start ..< output.utf8.count)
					return true
				}
				guard character != "}" else {
					return false
				}
				if selectedFirst {
					output.append(character)
				}
				index = snippet.index(after: index)
			}
			return false
		}

		private mutating func appendEscapedCharacter() {
			let next = snippet.index(after: index)
			guard next < snippet.endIndex else {
				output.append("\\")
				index = next
				return
			}
			let character = snippet[next]
			if "\\$},|".contains(character) {
				output.append(character)
				index = snippet.index(after: next)
				return
			}
			output.append("\\")
			index = next
		}

		private mutating func skipEscapedCharacter() {
			let next = snippet.index(after: index)
			index = next < snippet.endIndex ? snippet.index(after: next) : next
		}

		private func number(from start: String.Index) -> (value: Int, index: String.Index)? {
			guard start < snippet.endIndex, snippet[start].isNumber else {
				return nil
			}
			var index = start
			var value = 0
			while index < snippet.endIndex, let digit = snippet[index].wholeNumberValue {
				value = value * 10 + digit
				index = snippet.index(after: index)
			}
			return (value, index)
		}
	}
}

public struct LSPCompletionApplication: Equatable, Sendable {
	public var replacementRange: Range<Int>
	public var replacementText: String
	public var transactionRange: Range<Int>
	public var transactionText: String
	public var selectionRanges: [Range<Int>]
	public var tabStopRanges: [Int: [Range<Int>]]

	public init(
		replacementRange: Range<Int>,
		replacementText: String,
		transactionRange: Range<Int>? = nil,
		transactionText: String? = nil,
		selectionRanges: [Range<Int>],
		tabStopRanges: [Int: [Range<Int>]] = [:]
	) {
		self.replacementRange = replacementRange
		self.replacementText = replacementText
		self.transactionRange = transactionRange ?? replacementRange
		self.transactionText = transactionText ?? replacementText
		self.selectionRanges = selectionRanges
		self.tabStopRanges = tabStopRanges
	}
}

public enum LSPCompletionApply {
	public static func application(for item: LSPCompletionItem, in text: String,
	                               cursorOffset: Int) -> LSPCompletionApplication?
	{
		let source = item.textEdit?.newText ?? item.insertText ?? item.label
		let replacementRange: Range<Int>
		if let edit = item.textEdit {
			guard let range = LSPTextEditApply.utf8Range(for: edit.range, in: text) else {
				return nil
			}
			replacementRange = range
		} else {
			let clamped = min(max(cursorOffset, 0), text.utf8.count)
			replacementRange = completionPrefixRange(in: text, cursorOffset: clamped)
		}
		let expansion = item.insertTextFormat == .snippet
			? LSPSnippetExpander.expand(source)
			: LSPSnippetExpansion(text: source, tabStops: [:])
		let primaryEdit = CompletionTextEdit(range: replacementRange, text: expansion.text)
		let additionalEdits = item.additionalTextEdits?.compactMap { edit -> CompletionTextEdit? in
			guard let range = LSPTextEditApply.utf8Range(for: edit.range, in: text) else {
				return nil
			}
			return CompletionTextEdit(range: range, text: edit.newText)
		} ?? []
		guard (item.additionalTextEdits?.count ?? 0) == additionalEdits.count,
		      let transaction = transaction(for: [primaryEdit] + additionalEdits, in: text)
		else {
			return nil
		}
		let selectionOffset = additionalEdits
			.filter { $0.range.upperBound <= replacementRange.lowerBound }
			.reduce(0) { $0 + $1.text.utf8.count - $1.range.count }
		let selections = expansion.firstTabStopRanges.map {
			(replacementRange.lowerBound + selectionOffset + $0.lowerBound) ..<
				(replacementRange.lowerBound + selectionOffset + $0.upperBound)
		}
		let tabStopRanges = expansion.tabStops.mapValues { ranges in
			ranges.map {
				(replacementRange.lowerBound + selectionOffset + $0.lowerBound) ..<
					(replacementRange.lowerBound + selectionOffset + $0.upperBound)
			}
		}
		let fallback = replacementRange.lowerBound + selectionOffset + expansion.text.utf8.count
		return LSPCompletionApplication(
			replacementRange: replacementRange,
			replacementText: expansion.text,
			transactionRange: transaction.range,
			transactionText: transaction.text,
			selectionRanges: selections.isEmpty ? [fallback ..< fallback] : selections,
			tabStopRanges: tabStopRanges
		)
	}

	private struct CompletionTextEdit {
		var range: Range<Int>
		var text: String
	}

	private static func transaction(for edits: [CompletionTextEdit],
	                                in source: String) -> (range: Range<Int>, text: String)?
	{
		guard let first = edits.min(by: { $0.range.lowerBound < $1.range.lowerBound }),
		      let last = edits.max(by: { $0.range.upperBound < $1.range.upperBound })
		else {
			return nil
		}
		let ordered = edits.sorted {
			if $0.range.lowerBound != $1.range.lowerBound {
				return $0.range.lowerBound < $1.range.lowerBound
			}
			return $0.range.upperBound < $1.range.upperBound
		}
		for index in 1 ..< ordered.count {
			let previous = ordered[index - 1].range
			let next = ordered[index].range
			guard previous.upperBound <= next.lowerBound,
			      previous != next,
			      !(previous.lowerBound == next.lowerBound && (previous.isEmpty || next.isEmpty))
			else {
				return nil
			}
		}
		let range = first.range.lowerBound ..< last.range.upperBound
		let sourceBytes = Array(source.utf8)
		var bytes = Array(sourceBytes[range])
		for edit in ordered.reversed() {
			let lower = edit.range.lowerBound - range.lowerBound
			let upper = edit.range.upperBound - range.lowerBound
			bytes.replaceSubrange(lower ..< upper, with: edit.text.utf8)
		}
		return (range, String(decoding: bytes, as: UTF8.self))
	}

	private static func completionPrefixRange(in text: String, cursorOffset: Int) -> Range<Int> {
		let cursor = stringIndex(in: text, utf8Offset: cursorOffset)
		var start = cursor
		while start > text.startIndex {
			let previous = text.index(before: start)
			guard isIdentifierCharacter(text[previous]) else {
				break
			}
			start = previous
		}
		return utf8Offset(in: text, for: start) ..< cursorOffset
	}

	private static func isIdentifierCharacter(_ character: Character) -> Bool {
		character == "_" || character.unicodeScalars.allSatisfy { CharacterSet.alphanumerics.contains($0) }
	}

	private static func stringIndex(in text: String, utf8Offset target: Int) -> String.Index {
		let clamped = min(max(target, 0), text.utf8.count)
		var index = text.startIndex
		var offset = 0
		while index < text.endIndex, offset < clamped {
			let next = text.index(after: index)
			let nextOffset = offset + String(text[index]).utf8.count
			guard nextOffset <= clamped else {
				break
			}
			offset = nextOffset
			index = next
		}
		return index
	}

	private static func utf8Offset(in text: String, for target: String.Index) -> Int {
		text.utf8.distance(from: text.utf8.startIndex, to: target.samePosition(in: text.utf8) ?? text.utf8.endIndex)
	}
}
