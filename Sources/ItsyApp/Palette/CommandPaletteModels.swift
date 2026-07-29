import Foundation
import ItsyEditor

struct CommandPaletteLineTarget: Equatable {
	let line: Int
	let column: Int

	static func parse(_ value: String) -> CommandPaletteLineTarget? {
		let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
		let body = trimmed.hasPrefix(":") ? String(trimmed.dropFirst()) : trimmed
		guard !body.isEmpty else {
			return nil
		}
		let parts = body.split(separator: ":", omittingEmptySubsequences: false)
		guard parts.count == 1 || parts.count == 2,
		      let line = Int(parts[0]),
		      line > 0
		else {
			return nil
		}
		let column: Int
		if parts.count == 2 {
			if parts[1].isEmpty {
				column = 1
			} else {
				guard let parsedColumn = Int(parts[1]), parsedColumn > 0 else {
					return nil
				}
				column = parsedColumn
			}
		} else {
			column = 1
		}
		return CommandPaletteLineTarget(line: line, column: column)
	}
}

enum CommandPaletteFileFilter {
	static func ranked(paths: [String], query: String, limit: Int = 100) -> [String] {
		guard limit > 0 else {
			return []
		}
		let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
		return Array(FuzzyMatcher.ranked(paths, query: trimmed, includeUnmatched: trimmed.isEmpty) { $0 }.prefix(limit))
	}
}
