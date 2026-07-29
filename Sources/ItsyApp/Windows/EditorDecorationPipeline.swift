import AppKit
import Foundation
import ItsyEditor
import ItsyLSP
import ItsyRender

struct LSPSemanticTokenState: Equatable {
	var resultId: String?
	var data: [Int]
}

struct LSPSemanticTokenCache {
	private var states: [String: LSPSemanticTokenState] = [:]

	func state(for uri: String) -> LSPSemanticTokenState? {
		states[uri]
	}

	mutating func invalidate(_ uri: String) {
		states[uri] = nil
	}

	@discardableResult mutating func replace(
		_ state: LSPSemanticTokenState?,
		for uri: String,
		generation: Int,
		currentGeneration: Int
	) -> Bool {
		guard generation == currentGeneration else {
			return false
		}
		states[uri] = state
		return true
	}
}

struct LSPSemanticHighlightResult {
	var spans: [TextHighlightSpan]
	var tokenState: LSPSemanticTokenState?
}

private final class LSPFoldGutterDecorator: GutterDecorator {
	var ranges: [LSPFoldingRange] = []
	var collapsedStartLines: Set<Int> = []
	var toggleFold: ((Int) -> Void)?

	func gutterMarkers(in lineRange: Range<Int>, for _: MetalTextView) -> [GutterMarker] {
		ranges.compactMap { range in
			guard range.endLine > range.startLine, lineRange.contains(range.startLine) else {
				return nil
			}
			let collapsed = collapsedStartLines.contains(range.startLine)
			return GutterMarker(
				id: "fold-\(range.startLine)-\(range.endLine)",
				line: range.startLine,
				severity: .hint,
				message: collapsed ? "folded" : "fold",
				color: SIMD4<Float>(0.54, 0.57, 0.62, 1.0),
				shape: collapsed ? .foldClosed : .foldOpen
			)
		}
	}

	func gutterMarkerClicked(_ marker: GutterMarker, in _: MetalTextView) {
		guard marker.id.hasPrefix("fold-") else {
			return
		}
		toggleFold?(marker.line)
	}

	func gutterPopoverViewController(for _: GutterMarker, in _: MetalTextView) -> NSViewController? {
		nil
	}
}

@MainActor final class EditorDecorationPipeline {
	private var semanticTokenCache = LSPSemanticTokenCache()
	private var foldingRangesByURI: [String: [LSPFoldingRange]] = [:]
	private var collapsedFoldStartsByURI: [String: Set<Int>] = [:]
	private let foldGutterDecorator = LSPFoldGutterDecorator()

	var toggleFoldRequested: ((Int) -> Void)? {
		didSet { foldGutterDecorator.toggleFold = toggleFoldRequested }
	}

	func install(on document: ItsyDocument) {
		document.setLSPGutterDecorator(foldGutterDecorator)
	}

	func invalidate(for document: ItsyDocument) {
		guard let uri = document.fileURL?.standardizedFileURL.absoluteString else {
			return
		}
		invalidate(uri: uri, document: document)
	}

	func invalidate(uri: String, document: ItsyDocument) {
		semanticTokenCache.invalidate(uri)
		foldingRangesByURI[uri] = nil
		collapsedFoldStartsByURI[uri] = nil
		applyFoldState(uri: uri, document: document)
		document.setLSPSemanticHighlightSpans([])
		document.setLSPSemanticSurface(inlayHints: [], highlights: [])
	}

	func invalidateDocumentHighlights(for document: ItsyDocument) {
		document.setLSPDocumentHighlightRanges([])
	}

	func semanticTokenState(for uri: String) -> LSPSemanticTokenState? {
		semanticTokenCache.state(for: uri)
	}

	@discardableResult func replaceSemanticTokenState(
		_ state: LSPSemanticTokenState?,
		for uri: String,
		generation: Int,
		currentGeneration: Int
	) -> Bool {
		semanticTokenCache.replace(state, for: uri, generation: generation, currentGeneration: currentGeneration)
	}

	func apply(
		uri: String,
		content: String,
		document: ItsyDocument,
		semanticSpans: [TextHighlightSpan],
		inlayHints: [LSPInlayHint],
		foldingRanges: [LSPFoldingRange],
		documentHighlights: [LSPDocumentHighlight]
	) {
		document.setLSPSemanticHighlightSpans(semanticSpans)
		let annotations = inlayHints.compactMap { hint -> TextInlineAnnotation? in
			let range = LSPRange(start: hint.position, end: hint.position)
			guard let offset = LSPTextEditApply.utf8Range(for: range, in: content)?.lowerBound else {
				return nil
			}
			let label = hint.label.text.trimmingCharacters(in: .whitespacesAndNewlines)
			guard !label.isEmpty else {
				return nil
			}
			return TextInlineAnnotation(offset: offset, label: label)
		}
		let highlightRanges = documentHighlights.compactMap {
			LSPTextEditApply.utf8Range(for: $0.range, in: content)
		}
		foldingRangesByURI[uri] = foldingRanges
		let validStarts = Set(foldingRanges.map(\.startLine))
		collapsedFoldStartsByURI[uri] = collapsedFoldStartsByURI[uri, default: []].intersection(validStarts)
		applyFoldState(uri: uri, document: document)
		document.setLSPSemanticSurface(inlayHints: annotations, highlights: highlightRanges)
	}

	func toggleFold(startLine: Int, uri: String, document: ItsyDocument) -> Bool {
		var starts = collapsedFoldStartsByURI[uri, default: []]
		if starts.contains(startLine) {
			starts.remove(startLine)
		} else {
			starts.insert(startLine)
		}
		collapsedFoldStartsByURI[uri] = starts
		applyFoldState(uri: uri, document: document)
		return true
	}

	func setFoldAtCursor(collapsed: Bool, uri: String, document: ItsyDocument, editor: MetalTextView) -> Bool {
		guard let range = foldRangeAtCursor(uri: uri, editor: editor) else {
			return false
		}
		var starts = collapsedFoldStartsByURI[uri, default: []]
		if collapsed {
			starts.insert(range.startLine)
		} else {
			starts.remove(range.startLine)
		}
		collapsedFoldStartsByURI[uri] = starts
		applyFoldState(uri: uri, document: document)
		return true
	}

	func toggleFoldAtCursor(uri: String, document: ItsyDocument, editor: MetalTextView) -> Bool {
		guard let range = foldRangeAtCursor(uri: uri, editor: editor) else {
			return false
		}
		return toggleFold(startLine: range.startLine, uri: uri, document: document)
	}

	func setFoldSubtreeAtCursor(collapsed: Bool, uri: String, document: ItsyDocument, editor: MetalTextView) -> Bool {
		guard let range = foldRangeAtCursor(uri: uri, editor: editor) else {
			return false
		}
		let starts = foldingRangesByURI[uri, default: []]
			.filter { $0.startLine >= range.startLine && $0.endLine <= range.endLine }
			.map(\.startLine)
		guard !starts.isEmpty else {
			return false
		}
		var collapsedStarts = collapsedFoldStartsByURI[uri, default: []]
		if collapsed {
			collapsedStarts.formUnion(starts)
		} else {
			collapsedStarts.subtract(starts)
		}
		collapsedFoldStartsByURI[uri] = collapsedStarts
		applyFoldState(uri: uri, document: document)
		return true
	}

	func toggleFoldSubtreeAtCursor(uri: String, document: ItsyDocument, editor: MetalTextView) -> Bool {
		guard let range = foldRangeAtCursor(uri: uri, editor: editor) else {
			return false
		}
		let collapsed = collapsedFoldStartsByURI[uri, default: []].contains(range.startLine)
		return setFoldSubtreeAtCursor(collapsed: !collapsed, uri: uri, document: document, editor: editor)
	}

	func setAllFolds(collapsed: Bool, uri: String, document: ItsyDocument) -> Bool {
		let starts = Set(foldingRangesByURI[uri, default: []].map(\.startLine))
		guard !starts.isEmpty else {
			return false
		}
		collapsedFoldStartsByURI[uri] = collapsed ? starts : []
		applyFoldState(uri: uri, document: document)
		return true
	}

	func collapsedStarts(for uri: String) -> Set<Int> {
		collapsedFoldStartsByURI[uri, default: []]
	}

	static func applySemanticTokenDelta(_ delta: LSPSemanticTokensDelta, to previous: [Int]) -> [Int] {
		var data = previous
		for edit in delta.edits.sorted(by: { $0.start > $1.start }) {
			let start = min(max(edit.start, 0), data.count)
			let end = min(max(start, start + edit.deleteCount), data.count)
			data.replaceSubrange(start ..< end, with: edit.data ?? [])
		}
		return data
	}

	static func semanticHighlightSpans(from tokens: LSPSemanticTokens, legend: LSPSemanticTokensLegend, content: String) -> [TextHighlightSpan] {
		var spans: [TextHighlightSpan] = []
		var line = 0
		var character = 0
		var index = 0
		while index + 4 < tokens.data.count {
			let deltaLine = tokens.data[index]
			let deltaStart = tokens.data[index + 1]
			let length = tokens.data[index + 2]
			let tokenTypeIndex = tokens.data[index + 3]
			index += 5
			line += deltaLine
			character = deltaLine == 0 ? character + deltaStart : deltaStart
			guard tokenTypeIndex >= 0, tokenTypeIndex < legend.tokenTypes.count else {
				continue
			}
			let type = legend.tokenTypes[tokenTypeIndex]
			guard let color = semanticTokenColor(for: type) else {
				continue
			}
			let range = LSPRange(
				start: LSPPosition(line: line, character: character),
				end: LSPPosition(line: line, character: character + length)
			)
			guard let utf8Range = LSPTextEditApply.utf8Range(for: range, in: content), !utf8Range.isEmpty else {
				continue
			}
			spans.append(TextHighlightSpan(range: utf8Range, color: color))
		}
		return spans
	}

	private func foldRangeAtCursor(uri: String, editor: MetalTextView) -> LSPFoldingRange? {
		let line = editor.editor.textStorage.line(forOffset: editor.editor.selections.primary.head)
		return foldingRangesByURI[uri]?
			.filter { $0.startLine <= line && line <= $0.endLine && $0.endLine > $0.startLine }
			.sorted { ($0.endLine - $0.startLine) < ($1.endLine - $1.startLine) }
			.first
	}

	private func applyFoldState(uri: String, document: ItsyDocument) {
		let ranges = foldingRangesByURI[uri] ?? []
		let collapsedStarts = collapsedFoldStartsByURI[uri, default: []]
		let hidden = ranges.compactMap { range -> Range<Int>? in
			guard collapsedStarts.contains(range.startLine), range.endLine > range.startLine else {
				return nil
			}
			return (range.startLine + 1) ..< (range.endLine + 1)
		}
		foldGutterDecorator.ranges = ranges
		foldGutterDecorator.collapsedStartLines = collapsedStarts
		document.setLSPGutterDecorator(foldGutterDecorator)
		document.setLSPFoldedLineRanges(hidden)
	}

	private static func semanticTokenColor(for type: String) -> SIMD4<Float>? {
		switch type {
		case "keyword", "modifier", "operator": SIMD4<Float>(0.12, 0.32, 0.78, 1.0)
		case "string", "regexp": SIMD4<Float>(0.08, 0.45, 0.28, 1.0)
		case "number": SIMD4<Float>(0.76, 0.38, 0.10, 1.0)
		case "comment": SIMD4<Float>(0.45, 0.49, 0.54, 1.0)
		case "class", "enum", "interface", "struct", "type", "typeParameter": SIMD4<Float>(0.43, 0.22, 0.72, 1.0)
		case "function", "method", "macro": SIMD4<Float>(0.48, 0.26, 0.10, 1.0)
		case "parameter", "variable", "property", "enumMember": SIMD4<Float>(0.08, 0.09, 0.11, 1.0)
		default: nil
		}
	}
}
