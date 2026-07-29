import Foundation

public enum DiffPatchOperation: Equatable, Sendable {
	case stage
	case unstage
}

public enum DiffPatchBuilderError: Error, Equatable, Sendable {
	case emptySelection
	case nonContiguousSelection
	case selectionOutOfBounds
	case selectionIncludesContext
}

public enum DiffPatchBuilder {
	public static func patch(file: DiffFile, hunk: DiffHunk) -> String {
		var lines: [String] = []
		let oldPath = file.oldPath ?? file.newPath ?? "dev/null"
		let newPath = file.newPath ?? file.oldPath ?? "dev/null"
		lines.append("diff --git a/\(oldPath) b/\(newPath)")
		if file.isNewFile, let mode = file.newMode {
			lines.append("new file mode \(mode)")
		}
		if file.isDeletedFile, let mode = file.oldMode {
			lines.append("deleted file mode \(mode)")
		}
		if !file.isNewFile, !file.isDeletedFile, file.oldPath != file.newPath {
			if let oldPath = file.oldPath {
				lines.append("rename from \(oldPath)")
			}
			if let newPath = file.newPath {
				lines.append("rename to \(newPath)")
			}
		}
		if let indexLine = file.indexLine {
			lines.append(indexLine)
		}
		lines.append("--- \(file.oldPath.map { "a/\($0)" } ?? "/dev/null")")
		lines.append("+++ \(file.newPath.map { "b/\($0)" } ?? "/dev/null")")
		lines.append("@@ -\(rangeText(start: hunk.oldStart, count: hunk.oldCount)) +\(rangeText(start: hunk.newStart, count: hunk.newCount)) @@")
		for (lineIndex, line) in hunk.lines.enumerated() {
			switch line {
			case .context(let content):
				lines.append(" \(content)")
			case .add(let content):
				lines.append("+\(content)")
			case .remove(let content):
				lines.append("-\(content)")
			}
			if hunk.noNewlineLineIndexes.contains(lineIndex) {
				lines.append("\\ No newline at end of file")
			}
		}
		return lines.joined(separator: "\n") + "\n"
	}

	public static func patch(file: DiffFile, hunk: DiffHunk, selectedLineIndexes: IndexSet, operation: DiffPatchOperation) throws -> String {
		let selectedRange = try contiguousRange(from: selectedLineIndexes)
		let partial = try partialHunk(from: hunk, selectedRange: selectedRange, operation: operation)
		return patch(file: file, hunk: partial)
	}

	private static func partialHunk(from hunk: DiffHunk, selectedRange: Range<Int>, operation: DiffPatchOperation) throws -> DiffHunk {
		guard selectedRange.lowerBound >= 0, selectedRange.upperBound <= hunk.lines.count else {
			throw DiffPatchBuilderError.selectionOutOfBounds
		}
		for index in selectedRange {
			if case .context = hunk.lines[index] {
				throw DiffPatchBuilderError.selectionIncludesContext
			}
		}
		let window = contextWindow(in: hunk.lines, around: selectedRange, operation: operation)
		var oldLine = hunk.oldCount == 0 ? hunk.oldStart + 1 : hunk.oldStart
		var newLine = hunk.newCount == 0 ? hunk.newStart + 1 : hunk.newStart
		for line in hunk.lines[..<window.lowerBound] {
			if let transformed = transformedLine(line, selected: false, operation: operation) {
				advance(transformed, oldLine: &oldLine, newLine: &newLine)
			}
		}
		let oldStart = oldLine
		let newStart = newLine
		var lines: [DiffLine] = []
		var noNewlineLineIndexes: Set<Int> = []
		for index in window {
			guard let transformed = transformedLine(hunk.lines[index], selected: selectedRange.contains(index), operation: operation) else {
				continue
			}
			if hunk.noNewlineLineIndexes.contains(index) {
				noNewlineLineIndexes.insert(lines.count)
			}
			lines.append(transformed)
		}
		let oldCount = lines.reduce(0) { count, line in
			count + (line.consumesOldLine ? 1 : 0)
		}
		let newCount = lines.reduce(0) { count, line in
			count + (line.consumesNewLine ? 1 : 0)
		}
		return DiffHunk(
			oldStart: oldCount == 0 ? max(oldStart - 1, 0) : oldStart,
			oldCount: oldCount,
			newStart: newCount == 0 ? max(newStart - 1, 0) : newStart,
			newCount: newCount,
			lines: lines,
			noNewlineLineIndexes: noNewlineLineIndexes
		)
	}

	private static func contiguousRange(from indexes: IndexSet) throws -> Range<Int> {
		guard let first = indexes.first, let last = indexes.last else {
			throw DiffPatchBuilderError.emptySelection
		}
		let range = first ..< (last + 1)
		guard indexes == IndexSet(integersIn: range) else {
			throw DiffPatchBuilderError.nonContiguousSelection
		}
		return range
	}

	private static func contextWindow(in lines: [DiffLine], around selectedRange: Range<Int>, operation: DiffPatchOperation) -> Range<Int> {
		let maxContext = 3
		var lower = selectedRange.lowerBound
		var upper = selectedRange.upperBound
		var count = 0
		var before = selectedRange.lowerBound - 1
		while before >= 0, count < maxContext {
			if transformedLine(lines[before], selected: false, operation: operation) != nil {
				lower = before
				count += 1
			}
			before -= 1
		}
		count = 0
		var after = selectedRange.upperBound
		while after < lines.count, count < maxContext {
			if transformedLine(lines[after], selected: false, operation: operation) != nil {
				upper = after + 1
				count += 1
			}
			after += 1
		}
		return lower ..< upper
	}

	private static func transformedLine(_ line: DiffLine, selected: Bool, operation: DiffPatchOperation) -> DiffLine? {
		if selected {
			return line
		}
		switch (line, operation) {
		case (.context, _):
			return line
		case (.remove(let content), .stage), (.add(let content), .unstage):
			return .context(content)
		case (.remove, .unstage), (.add, .stage):
			return nil
		}
	}

	private static func advance(_ line: DiffLine, oldLine: inout Int, newLine: inout Int) {
		if line.consumesOldLine {
			oldLine += 1
		}
		if line.consumesNewLine {
			newLine += 1
		}
	}

	private static func rangeText(start: Int, count: Int) -> String {
		count == 1 ? "\(start)" : "\(start),\(count)"
	}
}

private extension DiffLine {
	var consumesOldLine: Bool {
		switch self {
		case .context, .remove:
			return true
		case .add:
			return false
		}
	}

	var consumesNewLine: Bool {
		switch self {
		case .context, .add:
			return true
		case .remove:
			return false
		}
	}
}
