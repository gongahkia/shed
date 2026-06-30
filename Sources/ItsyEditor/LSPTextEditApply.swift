import Foundation
import ItsyLSP

public enum LSPTextEditApplyError: Error, Equatable, Sendable {
	case outOfBoundsRange
	case overlappingEdits
}

public enum LSPTextEditApply {
	public static func apply(_ edits: [LSPTextEdit], to text: String) throws -> String {
		guard !edits.isEmpty else {
			return text
		}
		var resolved: [(start: Int, end: Int, newText: String)] = try edits.map { edit in
			guard let start = utf16Offset(line: edit.range.start.line, character: edit.range.start.character, in: text) else {
				throw LSPTextEditApplyError.outOfBoundsRange
			}
			guard let end = utf16Offset(line: edit.range.end.line, character: edit.range.end.character, in: text) else {
				throw LSPTextEditApplyError.outOfBoundsRange
			}
			guard end >= start else {
				throw LSPTextEditApplyError.outOfBoundsRange
			}
			return (start, end, edit.newText)
		}
		resolved.sort { left, right in
			if left.start != right.start {
				return left.start > right.start
			}
			return left.end > right.end
		}
		for index in 1 ..< resolved.count {
			if resolved[index].end > resolved[index - 1].start {
				throw LSPTextEditApplyError.overlappingEdits
			}
		}
		var utf16 = Array(text.utf16)
		for edit in resolved {
			let replacement = Array(edit.newText.utf16)
			utf16.replaceSubrange(edit.start ..< edit.end, with: replacement)
		}
		return String(utf16CodeUnits: utf16, count: utf16.count)
	}

	public static func utf16Offset(line: Int, character: Int, in text: String) -> Int? {
		guard line >= 0, character >= 0 else {
			return nil
		}
		var remainingLines = line
		var index = 0
		let units = Array(text.utf16)
		while remainingLines > 0, index < units.count {
			let unit = units[index]
			index += 1
			if unit == 0x000A {
				remainingLines -= 1
			} else if unit == 0x000D {
				if index < units.count, units[index] == 0x000A {
					index += 1
				}
				remainingLines -= 1
			}
		}
		if remainingLines > 0 {
			return nil
		}
		let lineStart = index
		var charsRemaining = character
		while charsRemaining > 0, index < units.count {
			let unit = units[index]
			if unit == 0x000A || unit == 0x000D {
				break
			}
			index += 1
			charsRemaining -= 1
		}
		guard charsRemaining == 0 else {
			return nil
		}
		_ = lineStart
		return index
	}

	public static func utf8Range(for range: LSPRange, in text: String) -> Range<Int>? {
		guard
			let lowerUTF16 = utf16Offset(line: range.start.line, character: range.start.character, in: text),
			let upperUTF16 = utf16Offset(line: range.end.line, character: range.end.character, in: text)
		else {
			return nil
		}
		let lower = utf8Offset(forUTF16Offset: lowerUTF16, in: text)
		let upper = utf8Offset(forUTF16Offset: upperUTF16, in: text)
		guard upper >= lower else {
			return nil
		}
		return lower ..< upper
	}

	public static func utf16Position(forUTF8Offset target: Int, in text: String) -> LSPPosition {
		let clamped = min(max(target, 0), text.utf8.count)
		var utf8 = 0
		var line = 0
		var character = 0
		for scalar in text.unicodeScalars {
			guard utf8 < clamped else {
				break
			}
			utf8 += String(scalar).utf8.count
			if scalar.value == 10 {
				line += 1
				character = 0
			} else {
				character += String(scalar).utf16.count
			}
		}
		return LSPPosition(line: line, character: character)
	}

	private static func utf8Offset(forUTF16Offset target: Int, in text: String) -> Int {
		var utf8 = 0
		var utf16 = 0
		for character in text {
			if utf16 >= target {
				break
			}
			utf8 += String(character).utf8.count
			utf16 += String(character).utf16.count
		}
		return utf8
	}
}
