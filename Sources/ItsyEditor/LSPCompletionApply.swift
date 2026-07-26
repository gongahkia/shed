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
		var output = ""
		var tabStops: [Int: [Range<Int>]] = [:]
		var index = snippet.startIndex
		while index < snippet.endIndex {
			if snippet[index] == "$" {
				let next = snippet.index(after: index)
				if next < snippet.endIndex, snippet[next].isNumber {
					let parsed = parseNumber(in: snippet, from: next)
					tabStops[parsed.value, default: []].append(output.utf8.count ..< output.utf8.count)
					index = parsed.index
					continue
				}
				if next < snippet.endIndex, snippet[next] == "{" {
					if let parsed = parsePlaceholder(in: snippet, from: snippet.index(after: next)) {
						let start = output.utf8.count
						output.append(parsed.placeholder)
						tabStops[parsed.value, default: []].append(start ..< output.utf8.count)
						index = parsed.index
						continue
					}
				}
			}
			output.append(snippet[index])
			index = snippet.index(after: index)
		}
		return LSPSnippetExpansion(text: output, tabStops: tabStops)
	}

	private static func parseNumber(in snippet: String, from start: String.Index) -> (value: Int, index: String.Index) {
		var index = start
		var value = 0
		while index < snippet.endIndex, let digit = snippet[index].wholeNumberValue {
			value = value * 10 + digit
			index = snippet.index(after: index)
		}
		return (value, index)
	}

	private static func parsePlaceholder(in snippet: String, from start: String.Index) -> (value: Int, placeholder: String, index: String.Index)? {
		let parsed = parseNumber(in: snippet, from: start)
		guard parsed.index < snippet.endIndex else {
			return nil
		}
		var index = parsed.index
		var placeholder = ""
		if snippet[index] == ":" {
			index = snippet.index(after: index)
			while index < snippet.endIndex, snippet[index] != "}" {
				placeholder.append(snippet[index])
				index = snippet.index(after: index)
			}
		}
		guard index < snippet.endIndex, snippet[index] == "}" else {
			return nil
		}
		return (parsed.value, placeholder, snippet.index(after: index))
	}
}

public struct LSPCompletionApplication: Equatable, Sendable {
	public var replacementRange: Range<Int>
	public var replacementText: String
	public var selectionRanges: [Range<Int>]
	public var tabStopRanges: [Int: [Range<Int>]]

	public init(
		replacementRange: Range<Int>,
		replacementText: String,
		selectionRanges: [Range<Int>],
		tabStopRanges: [Int: [Range<Int>]] = [:]
	) {
		self.replacementRange = replacementRange
		self.replacementText = replacementText
		self.selectionRanges = selectionRanges
		self.tabStopRanges = tabStopRanges
	}
}

public enum LSPCompletionApply {
	public static func application(for item: LSPCompletionItem, in text: String, cursorOffset: Int) -> LSPCompletionApplication? {
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
		let selections = expansion.firstTabStopRanges.map {
			(replacementRange.lowerBound + $0.lowerBound) ..< (replacementRange.lowerBound + $0.upperBound)
		}
		let tabStopRanges = expansion.tabStops.mapValues { ranges in
			ranges.map {
				(replacementRange.lowerBound + $0.lowerBound) ..< (replacementRange.lowerBound + $0.upperBound)
			}
		}
		let fallback = replacementRange.lowerBound + expansion.text.utf8.count
		return LSPCompletionApplication(
			replacementRange: replacementRange,
			replacementText: expansion.text,
			selectionRanges: selections.isEmpty ? [fallback ..< fallback] : selections,
			tabStopRanges: tabStopRanges
		)
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
