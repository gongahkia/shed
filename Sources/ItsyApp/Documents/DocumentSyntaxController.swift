import Foundation
import ItsyEditor
import ItsyRender
import ItsySyntax

final class DocumentSyntaxController {
	private static let highlightOverscanLineCount = 20
	private var syntaxPipeline: SyntaxPipeline?
	private var syntaxTheme: SyntaxTheme?
	private var syntaxTree: Tree?
	private var syntaxHighlightSpans: [HighlightSpan] = []
	var setHighlightSpans: ([TextHighlightSpan]) -> Void = { _ in }

	func configure(fileURL: URL?) {
		guard let fileURL, let language = SyntaxPipeline.language(forFileURL: fileURL) else {
			syntaxPipeline = nil
			syntaxTree = nil
			setHighlightSpans([])
			return
		}
		if syntaxPipeline?.language != language {
			syntaxPipeline = SyntaxPipeline(language: language)
			syntaxTree = nil
		}
	}

	func reloadTheme(editor: Editor, viewportLineRange: Range<Int>? = nil) {
		syntaxTheme = nil
		refresh(editor: editor, viewportLineRange: viewportLineRange)
	}

	func refresh(editor: Editor, edits: [Edit] = [], oldRope: Rope? = nil, viewportLineRange: Range<Int>? = nil) {
		guard var syntaxPipeline else {
			setHighlightSpans([])
			return
		}
		defer {
			self.syntaxPipeline = syntaxPipeline
		}
		do {
			if syntaxTheme == nil {
				syntaxTheme = try SyntaxTheme.loadUserOrDefault()
			}
			let spans: [HighlightSpan]
			if case .rope = editor.textStorage,
			   edits.count == 1,
			   let edit = edits.first,
			   let oldRope,
			   let tree = syntaxTree {
				let inputEdit = InputEdit(edit: edit, oldRope: oldRope, newRope: editor.rope)
				tree.edit(inputEdit)
				let newTree = try syntaxPipeline.parse(editor.rope, oldTree: tree)
				syntaxTree = newTree
				let queryRanges = mergedByteRanges(
					[
						changedLineByteRange(for: inputEdit, editor: editor),
						highlightByteRange(for: viewportLineRange, editor: editor),
					].compactMap { $0 }
				)
				let dirtySpans = try queryRanges.flatMap { range in
					try syntaxPipeline.highlights(in: newTree, byteRange: range)
				}
				syntaxHighlightSpans = syntaxHighlightSpans.compactMap { $0.mapped(through: edit) }
				syntaxHighlightSpans.removeAll { span in
					queryRanges.contains { $0.overlaps(span.range) }
				}
				syntaxHighlightSpans += dirtySpans
				spans = syntaxHighlightSpans
			} else {
				let tree = try parse(editor: editor, using: &syntaxPipeline)
				syntaxTree = tree
				spans = try syntaxPipeline.highlights(in: tree, byteRange: highlightByteRange(for: viewportLineRange, editor: editor))
				syntaxHighlightSpans = spans
			}
			let renderedSpans = spans.compactMap { span -> TextHighlightSpan? in
				guard let color = syntaxTheme?.color(for: span.capture) else {
					return nil
				}
				return TextHighlightSpan(range: span.range, color: SIMD4<Float>(color.red, color.green, color.blue, color.alpha))
			}
			setHighlightSpans(renderedSpans)
		} catch {
			setHighlightSpans([])
		}
	}

	private func changedLineByteRange(for inputEdit: InputEdit, editor: Editor) -> Range<Int>? {
		let storage = editor.textStorage
		guard storage.length > 0 else {
			return nil
		}
		let lowerLine = storage.line(forOffset: min(inputEdit.startByte, storage.length))
		let upperOffset = min(max(inputEdit.newEndByte, inputEdit.startByte), storage.length)
		let upperLine = storage.line(forOffset: upperOffset)
		let lower = storage.lineRange(lowerLine).lowerBound
		let nextLine = min(storage.lineCount, upperLine + 1)
		let upper = nextLine < storage.lineCount ? storage.offset(forLine: nextLine) : storage.length
		return lower ..< upper
	}

	private func parse(editor: Editor, using syntaxPipeline: inout SyntaxPipeline) throws -> Tree {
		switch editor.textStorage {
		case let .rope(rope):
			return try syntaxPipeline.parse(rope)
		case let .pieceTree(pieceTree):
			return try syntaxPipeline.parse(pieceTree)
		}
	}

	private func highlightByteRange(for viewportLineRange: Range<Int>?, editor: Editor) -> Range<Int>? {
		guard let viewportLineRange, !viewportLineRange.isEmpty else {
			return nil
		}
		let storage = editor.textStorage
		let lowerLine = max(0, viewportLineRange.lowerBound - Self.highlightOverscanLineCount)
		let upperLine = min(storage.lineCount, viewportLineRange.upperBound + Self.highlightOverscanLineCount)
		guard lowerLine < upperLine else {
			return nil
		}
		let lower = storage.offset(forLine: lowerLine)
		let upper = upperLine < storage.lineCount ? storage.offset(forLine: upperLine) : storage.length
		return lower ..< upper
	}

	private func mergedByteRanges(_ ranges: [Range<Int>]) -> [Range<Int>] {
		let sorted = ranges.filter { !$0.isEmpty }.sorted { $0.lowerBound < $1.lowerBound }
		guard var current = sorted.first else {
			return []
		}
		var result: [Range<Int>] = []
		for range in sorted.dropFirst() {
			if range.lowerBound <= current.upperBound {
				current = current.lowerBound ..< max(current.upperBound, range.upperBound)
			} else {
				result.append(current)
				current = range
			}
		}
		result.append(current)
		return result
	}
}
