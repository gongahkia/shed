import AppKit
import Metal

extension MetalTextView {
	public func setFindMatchRanges(_ ranges: [Range<Int>]) {
		findMatchRanges = ranges
		refreshFindMatchRects()
	}

	public func setDocumentHighlightRanges(_ ranges: [Range<Int>]) {
		documentHighlightRanges = ranges
		refreshDocumentHighlightRects()
	}

	func renderFindMatchInstances(scale: CGFloat, encoder: MTLRenderCommandEncoder, drawableSize: CGSize) {
		solidInstanceScratch.removeAll(keepingCapacity: true)
		appendFindMatchOverlayInstances(scale: scale, into: &solidInstanceScratch)
		appendDocumentHighlightOverlayInstances(scale: scale, into: &solidInstanceScratch)
		renderSolidInstances(solidInstanceScratch, encoder: encoder, drawableSize: drawableSize)
	}

	func refreshFindMatchRects() {
		findMatchRects = findMatchRanges.flatMap { rects(forUTF8Range: $0) }
		markDirty()
	}

	func refreshDocumentHighlightRects() {
		documentHighlightRects = documentHighlightRanges.flatMap { rects(forUTF8Range: $0) }
		markDirty()
	}

	func rects(forUTF8Range range: Range<Int>) -> [CGRect] {
		guard !range.isEmpty else {
			return []
		}
		let startLine = editor.rope.line(forOffset: range.lowerBound)
		let endLine = editor.rope.line(forOffset: range.upperBound)
		guard startLine < visibleLineRange.upperBound, endLine >= visibleLineRange.lowerBound else {
			return []
		}
		return visibleLineSlots().compactMap { slot -> CGRect? in
			guard slot.line >= startLine, slot.line <= endLine else {
				return nil
			}
			let lower = max(range.lowerBound, slot.range.lowerBound)
			let upper = min(range.upperBound, slot.range.upperBound)
			guard lower < upper else {
				return nil
			}
			let before = editor.rope.slice(slot.range.lowerBound ..< lower)
			let selected = editor.rope.slice(lower ..< upper)
			return CGRect(
				x: textInset.x + typographicWidth(before) - xOffset,
				y: topContentInset + textInset.y + CGFloat(slot.row) * lineHeight,
				width: max(2, typographicWidth(selected)),
				height: lineHeight
			)
		}
	}

	private func appendFindMatchOverlayInstances(scale: CGFloat, into instances: inout [MetalGlyphInstance]) {
		for rect in findMatchRects {
			instances.append(solidInstance(rect: rect, scale: scale, color: editorColorPalette.findMatch))
		}
	}

	private func appendDocumentHighlightOverlayInstances(scale: CGFloat, into instances: inout [MetalGlyphInstance]) {
		for rect in documentHighlightRects {
			let underline = CGRect(x: rect.minX, y: rect.maxY - 3, width: rect.width, height: 2)
			instances.append(solidInstance(rect: underline, scale: scale, color: editorColorPalette.documentHighlightUnderline))
		}
	}
}
