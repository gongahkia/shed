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
				syntaxTheme = try ItsyTheme.loadUserOrDefault().syntax
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
				let queryRange = highlightByteRange(for: viewportLineRange, editor: editor)
				let source = editor.textStorage.substring(0 ..< editor.textStorage.length)
				syntaxHighlightSpans = try syntaxPipeline.highlights(in: newTree, source: source, byteRange: queryRange, includeInjections: true)
				spans = syntaxHighlightSpans
			} else {
				let tree = try parse(editor: editor, using: &syntaxPipeline)
				syntaxTree = tree
				let byteRange = highlightByteRange(for: viewportLineRange, editor: editor)
				if case .pieceTree = editor.textStorage {
					spans = try syntaxPipeline.highlights(in: tree, byteRange: byteRange)
				} else {
					let source = editor.textStorage.substring(0 ..< editor.textStorage.length)
					spans = try syntaxPipeline.highlights(in: tree, source: source, byteRange: byteRange, includeInjections: true)
				}
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

	func newlineText(editor: Editor, tabWidth: Int) -> String {
		guard editor.selections.primary.isCaret, var syntaxPipeline else {
			return "\n"
		}
		defer {
			self.syntaxPipeline = syntaxPipeline
		}
		do {
			let tree: Tree
			if let syntaxTree {
				tree = syntaxTree
			} else {
				tree = try parse(editor: editor, using: &syntaxPipeline)
				syntaxTree = tree
			}
			if case .pieceTree = editor.textStorage {
				return fallbackNewlineText(editor: editor)
			}
			let source = editor.textStorage.substring(0 ..< editor.textStorage.length)
			return try syntaxPipeline.indentationAfterNewline(in: tree, source: source, offset: editor.selections.primary.head, tabWidth: tabWidth)
		} catch {
			return "\n"
		}
	}

	private func parse(editor: Editor, using syntaxPipeline: inout SyntaxPipeline) throws -> Tree {
		switch editor.textStorage {
		case let .rope(rope):
			return try syntaxPipeline.parse(rope)
		case let .pieceTree(pieceTree):
			return try syntaxPipeline.parse(pieceTree)
		}
	}

	private func fallbackNewlineText(editor: Editor) -> String {
		let storage = editor.textStorage
		let offset = editor.selections.primary.head
		let line = storage.line(forOffset: offset)
		let range = storage.lineRange(line)
		let prefix = storage.substring(range.lowerBound ..< min(offset, range.upperBound))
		let indentation = prefix.prefix { $0 == " " || $0 == "\t" }
		return "\n" + indentation
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

}
