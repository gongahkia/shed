import AppKit
import Metal

extension MetalTextView {
	public func setFindMatchRanges(_ ranges: [Range<Int>]) {
		findMatchRanges = ranges
		refreshFindMatchRects()
	}

	func renderFindMatchInstances(scale: CGFloat, encoder: MTLRenderCommandEncoder, drawableSize: CGSize) {
		solidInstanceScratch.removeAll(keepingCapacity: true)
		appendFindMatchOverlayInstances(scale: scale, into: &solidInstanceScratch)
		renderSolidInstances(solidInstanceScratch, encoder: encoder, drawableSize: drawableSize)
	}

	func refreshFindMatchRects() {
		findMatchRects = findMatchRanges.flatMap { rects(forUTF8Range: $0) }
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
		return (startLine ... endLine).compactMap { line -> CGRect? in
			guard visibleLineRange.contains(line) else {
				return nil
			}
			let lineRange = editor.rope.lineRange(line)
			let lower = max(range.lowerBound, lineRange.lowerBound)
			let upper = min(range.upperBound, lineRange.upperBound)
			guard lower < upper else {
				return nil
			}
			let before = editor.rope.slice(lineRange.lowerBound ..< lower)
			let selected = editor.rope.slice(lower ..< upper)
			return CGRect(
				x: textInset.x + typographicWidth(before) - xOffset,
				y: topContentInset + textInset.y + CGFloat(line - topLineIndex) * lineHeight,
				width: max(2, typographicWidth(selected)),
				height: lineHeight
			)
		}
	}

	private func appendFindMatchOverlayInstances(scale: CGFloat, into instances: inout [MetalGlyphInstance]) {
		for rect in findMatchRects {
			instances.append(solidInstance(rect: rect, scale: scale, color: SIMD4<Float>(0.80, 0.62, 0.12, 0.34)))
		}
	}
}
