import Foundation
import ItsyEditor
import ItsyRender
import ItsySyntax

final class DocumentSyntaxController {
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

	func reloadTheme(editor: Editor) {
		syntaxTheme = nil
		refresh(editor: editor)
	}

	func refresh(editor: Editor, edits: [Edit] = [], oldRope: Rope? = nil) {
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
			   isSingleLineEdit(edit),
			   let oldRope,
			   let tree = syntaxTree {
				let inputEdit = InputEdit(edit: edit, oldRope: oldRope, newRope: editor.rope)
				tree.edit(inputEdit)
				let newTree = try syntaxPipeline.parse(editor.rope, oldTree: tree)
				syntaxTree = newTree
				let dirtyRange = dirtyLineRange(containing: inputEdit.newEndByte, editor: editor)
				let dirtySpans = try syntaxPipeline.highlights(in: newTree, byteRange: dirtyRange)
				syntaxHighlightSpans = syntaxHighlightSpans.compactMap { $0.mapped(through: edit) }
				syntaxHighlightSpans.removeAll { $0.range.overlaps(dirtyRange) }
				syntaxHighlightSpans += dirtySpans
				spans = syntaxHighlightSpans
			} else {
				let tree = try parse(editor: editor, using: &syntaxPipeline)
				syntaxTree = tree
				spans = try syntaxPipeline.highlights(in: tree)
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

	private func dirtyLineRange(containing offset: Int, editor: Editor) -> Range<Int> {
		let storage = editor.textStorage
		let line = storage.line(forOffset: min(offset, storage.length))
		return storage.lineRange(line)
	}

	private func isSingleLineEdit(_ edit: Edit) -> Bool {
		!edit.oldText.utf8.contains(10) && !edit.newText.utf8.contains(10)
	}

	private func parse(editor: Editor, using syntaxPipeline: inout SyntaxPipeline) throws -> Tree {
		switch editor.textStorage {
		case let .rope(rope):
			return try syntaxPipeline.parse(rope)
		case let .pieceTree(pieceTree):
			return try syntaxPipeline.parse(pieceTree)
		}
	}
}
