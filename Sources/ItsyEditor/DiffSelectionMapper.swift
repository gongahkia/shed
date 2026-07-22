import Foundation

public struct DiffSelectionContext: Equatable, Sendable {
	public var fileIndex: Int
	public var hunkIndex: Int
	public var lineIndex: Int
	public var range: Range<Int>

	public init(fileIndex: Int, hunkIndex: Int, lineIndex: Int, range: Range<Int>) {
		self.fileIndex = fileIndex
		self.hunkIndex = hunkIndex
		self.lineIndex = lineIndex
		self.range = range
	}
}

public enum DiffSelectionMapper {
	public static func contexts(files: [DiffFile], document: RenderedDiffDocument) -> [DiffSelectionContext] {
		var contexts: [DiffSelectionContext] = []
		var renderedLine = 0
		for (fileIndex, file) in files.enumerated() {
			if fileIndex > 0 {
				renderedLine += 1
			}
			renderedLine += 1
			if file.isNewFile, file.newMode != nil {
				renderedLine += 1
			} else if file.isDeletedFile, file.oldMode != nil {
				renderedLine += 1
			} else if !file.isNewFile, !file.isDeletedFile, file.oldMode != file.newMode {
				renderedLine += (file.oldMode == nil ? 0 : 1) + (file.newMode == nil ? 0 : 1)
			}
			if file.isBinary {
				renderedLine += 1
			}
			renderedLine += 2
			for (hunkIndex, hunk) in file.hunks.enumerated() {
				renderedLine += 1
				for lineIndex in hunk.lines.indices {
					guard renderedLine < document.lines.count else {
						return contexts
					}
					contexts.append(DiffSelectionContext(
						fileIndex: fileIndex,
						hunkIndex: hunkIndex,
						lineIndex: lineIndex,
						range: document.lines[renderedLine].fullRange
					))
					renderedLine += 1
					if hunk.noNewlineLineIndexes.contains(lineIndex) {
						renderedLine += 1
					}
				}
			}
		}
		return contexts
	}

	public static func lineIndexes(selection: Range<Int>, fileIndex: Int, hunkIndex: Int, contexts: [DiffSelectionContext]) -> IndexSet {
		IndexSet(contexts.compactMap { context in
			guard context.fileIndex == fileIndex, context.hunkIndex == hunkIndex else {
				return nil
			}
			let containsSelection = selection.isEmpty
				? context.range.contains(selection.lowerBound) || context.range.upperBound == selection.lowerBound
				: context.range.overlaps(selection)
			return containsSelection ? context.lineIndex : nil
		})
	}
}
