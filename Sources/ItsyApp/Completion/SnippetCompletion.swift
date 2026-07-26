import Foundation
import ItsyEditor
import ItsyLSP

enum SnippetCompletionMarker {
	static let dataKey = "itsy.localCompletion"
	static let snippetValue = "snippet"

	static func data(for snippet: SnippetDefinition, prefix: String) -> LSPAny {
		.object([
			dataKey: .string(snippetValue),
			"name": .string(snippet.name),
			"prefix": .string(prefix),
		])
	}

	static func isSnippet(_ item: LSPCompletionItem) -> Bool {
		guard case let .object(data) = item.data else {
			return false
		}
		return data[dataKey] == .string(snippetValue)
	}
}

enum SnippetCompletionMapper {
	static func completionItems(from snippets: [SnippetDefinition], matching prefix: String) -> [LSPCompletionItem] {
		let trimmed = prefix.trimmingCharacters(in: .whitespacesAndNewlines)
		return snippets.flatMap { snippet in
			snippet.prefixes.compactMap { snippetPrefix in
				guard matches(snippetPrefix: snippetPrefix, prefix: trimmed) else {
					return nil
				}
				return LSPCompletionItem(
					label: snippetPrefix,
					detail: snippet.description ?? "snippet",
					sortText: snippetPrefix,
					filterText: snippetPrefix,
					insertText: snippet.body,
					insertTextFormat: .snippet,
					data: SnippetCompletionMarker.data(for: snippet, prefix: snippetPrefix)
				)
			}
		}
	}

	private static func matches(snippetPrefix: String, prefix: String) -> Bool {
		guard !prefix.isEmpty else {
			return true
		}
		return snippetPrefix.range(of: prefix, options: [.caseInsensitive, .diacriticInsensitive, .anchored]) != nil
	}
}

enum SnippetTabStopMove: Equatable {
	case ranges([Range<Int>])
	case finished
	case abandoned
}

struct SnippetTabStopSession: Equatable {
	struct Group: Equatable {
		var index: Int
		var ranges: [Range<Int>]
	}

	private(set) var groups: [Group]
	private(set) var currentGroupOffset = 0

	init(tabStopRanges: [Int: [Range<Int>]]) {
		let positiveKeys = tabStopRanges.keys.filter { $0 > 0 }.sorted()
		let keys = positiveKeys + (tabStopRanges.keys.contains(0) ? [0] : [])
		groups = keys.compactMap { key in
			let ranges = tabStopRanges[key] ?? []
			return ranges.isEmpty ? nil : Group(index: key, ranges: ranges)
		}
	}

	var isEmpty: Bool {
		groups.isEmpty
	}

	func currentRanges() -> [Range<Int>] {
		guard groups.indices.contains(currentGroupOffset) else {
			return []
		}
		return groups[currentGroupOffset].ranges
	}

	mutating func move(direction: Int, currentSelectionRanges: [Range<Int>]) -> SnippetTabStopMove {
		guard adjustForCurrentSelection(currentSelectionRanges) else {
			return .abandoned
		}
		let delta = direction < 0 ? -1 : 1
		let next = currentGroupOffset + delta
		guard groups.indices.contains(next) else {
			return .finished
		}
		currentGroupOffset = next
		return .ranges(groups[next].ranges)
	}

	private mutating func adjustForCurrentSelection(_ ranges: [Range<Int>]) -> Bool {
		guard groups.indices.contains(currentGroupOffset),
		      !ranges.isEmpty
		else {
			return false
		}
		let originalRanges = groups[currentGroupOffset].ranges
		guard ranges.count <= originalRanges.count else {
			return false
		}
		groups[currentGroupOffset].ranges = ranges
		for offset in groups.indices where offset > currentGroupOffset {
			groups[offset].ranges = groups[offset].ranges.map { target in
				let delta = zip(originalRanges, ranges).reduce(into: 0) { result, pair in
					guard pair.0.upperBound <= target.lowerBound else {
						return
					}
					result += pair.1.count - pair.0.count
				}
				return (target.lowerBound + delta) ..< (target.upperBound + delta)
			}
		}
		return true
	}
}
