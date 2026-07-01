import AppKit
import Dispatch
import Foundation
import ItsyEditor

public struct RenderHighlightCacheBenchmarkResult: Encodable, Sendable {
	public var cache_entries: Int
	public var cache_hit_rate: Double
	public var cache_hits: Int
	public var cache_lookups: Int
	public var cache_misses: Int
	public var elapsed_ms: Double
	public var frames: Int
	public var glyph_instances: Int
	public var line_count: Int
	public var visible_lines_per_frame: Int
}

public func runRenderHighlightCacheBenchmark(lineCount: Int = 100_000, frames: Int = 60) -> RenderHighlightCacheBenchmarkResult {
	precondition(lineCount > 0)
	precondition(frames > 0)
	let line = "let value = 1234567890 // render cache bench\n"
	let lineByteCount = line.utf8.count
	let text = String(repeating: line, count: lineCount)
	let view = MetalTextView(frame: NSRect(x: 0, y: 0, width: 900, height: 620))
	view.editor = Editor(text: text)
	view.lineHeight = 20
	view.highlightSpans = makeRenderHighlightCacheSpans(lineCount: lineCount, lineByteCount: lineByteCount, frame: 0)
	_ = view.textGlyphInstances(scale: 1)
	let visibleLines = view.visibleLineRange.count
	view.resetLineShapeCacheStats()
	var glyphInstances = 0
	let start = DispatchTime.now().uptimeNanoseconds
	for frame in 0 ..< frames {
		view.highlightSpans = makeRenderHighlightCacheSpans(lineCount: lineCount, lineByteCount: lineByteCount, frame: frame)
		glyphInstances += view.textGlyphInstances(scale: 1).count
		view.scroll(deltaX: 0, deltaY: -view.lineHeight)
	}
	let elapsed = Double(DispatchTime.now().uptimeNanoseconds - start) / 1_000_000
	return RenderHighlightCacheBenchmarkResult(
		cache_entries: view.lineShapeCacheEntryCount,
		cache_hit_rate: view.lineShapeCacheHitRate,
		cache_hits: view.lineShapeCacheHits,
		cache_lookups: view.lineShapeCacheLookupCount,
		cache_misses: view.lineShapeCacheMisses,
		elapsed_ms: elapsed,
		frames: frames,
		glyph_instances: glyphInstances,
		line_count: lineCount,
		visible_lines_per_frame: visibleLines
	)
}

private func makeRenderHighlightCacheSpans(lineCount: Int, lineByteCount: Int, frame: Int) -> [TextHighlightSpan] {
	let blue = 0.68 + Float(frame % 5) * 0.04
	let color = SIMD4<Float>(0.08, 0.24, blue, 1)
	return (0 ..< lineCount).map { lineIndex in
		let lowerBound = lineIndex * lineByteCount
		return TextHighlightSpan(range: lowerBound ..< lowerBound + 3, color: color)
	}
}
