import CoreGraphics
import CoreText
import Foundation

public struct AtlasUV: Sendable, Equatable {
	public let u0: CGFloat
	public let v0: CGFloat
	public let u1: CGFloat
	public let v1: CGFloat
}

public struct ShapedGlyph: Sendable, Equatable {
	public let glyphID: CGGlyph
	public let x: CGFloat
	public let y: CGFloat
	public let atlasUV: AtlasUV
	public let sourceUTF8Range: Range<Int>
	public let color: SIMD4<Float>
}

public struct LineShaper {
	public init() {}

	public func shape(
		_ line: String,
		font: CTFont,
		rasterFont: CTFont? = nil,
		atlas: inout GlyphAtlas,
		colorForRange: (Range<Int>) -> SIMD4<Float> = { _ in SIMD4<Float>(1, 1, 1, 1) }
	) throws -> [ShapedGlyph] {
		guard !line.isEmpty else {
			return []
		}
		let utf8Offsets = utf8OffsetsByUTF16Offset(line)
		let attributed = NSAttributedString(string: line, attributes: [kCTFontAttributeName as NSAttributedString.Key: font])
		let ctLine = CTLineCreateWithAttributedString(attributed)
		let runs = CTLineGetGlyphRuns(ctLine) as NSArray
		let atlasFont = rasterFont ?? font
		var shaped: [ShapedGlyph] = []
		for case let run as CTRun in runs {
			let glyphCount = CTRunGetGlyphCount(run)
			guard glyphCount > 0 else {
				continue
			}
			let glyphs = copyGlyphs(run, count: glyphCount)
			let positions = copyPositions(run, count: glyphCount)
			let stringIndices = copyStringIndices(run, count: glyphCount)
			shaped.reserveCapacity(shaped.count + glyphCount)
			for index in 0 ..< glyphCount {
				let entry = try atlas.entry(for: glyphs[index], font: atlasFont)
				let uv = atlasUV(for: entry, atlas: atlas)
				let utf16Start = max(0, min(stringIndices[index], utf8Offsets.count - 1))
				let utf16End = index + 1 < stringIndices.count ? max(utf16Start, min(stringIndices[index + 1], utf8Offsets.count - 1)) : utf8Offsets.count - 1
				let range = utf8Offsets[utf16Start] ..< utf8Offsets[utf16End]
				shaped.append(ShapedGlyph(glyphID: glyphs[index], x: positions[index].x, y: positions[index].y, atlasUV: uv, sourceUTF8Range: range, color: colorForRange(range)))
			}
		}
		return shaped
	}

	private func atlasUV(for entry: GlyphAtlasEntry, atlas: GlyphAtlas) -> AtlasUV {
		let textureWidth = CGFloat(atlas.texture.width)
		let textureHeight = CGFloat(atlas.texture.height)
		let u0 = CGFloat(entry.textureX) / textureWidth
		let v0 = CGFloat(entry.textureY) / textureHeight
		return AtlasUV(
			u0: u0,
			v0: v0,
			u1: u0 + CGFloat(entry.width) / textureWidth,
			v1: v0 + CGFloat(entry.height) / textureHeight
		)
	}
}

private func copyGlyphs(_ run: CTRun, count: Int) -> [CGGlyph] {
	if let pointer = CTRunGetGlyphsPtr(run) {
		return Array(UnsafeBufferPointer(start: pointer, count: count))
	}
	var glyphs = [CGGlyph](repeating: 0, count: count)
	CTRunGetGlyphs(run, CFRange(location: 0, length: count), &glyphs)
	return glyphs
}

private func copyPositions(_ run: CTRun, count: Int) -> [CGPoint] {
	if let pointer = CTRunGetPositionsPtr(run) {
		return Array(UnsafeBufferPointer(start: pointer, count: count))
	}
	var positions = [CGPoint](repeating: .zero, count: count)
	CTRunGetPositions(run, CFRange(location: 0, length: count), &positions)
	return positions
}

private func copyStringIndices(_ run: CTRun, count: Int) -> [Int] {
	if let pointer = CTRunGetStringIndicesPtr(run) {
		return Array(UnsafeBufferPointer(start: pointer, count: count)).map { Int($0) }
	}
	var indices = [CFIndex](repeating: 0, count: count)
	CTRunGetStringIndices(run, CFRange(location: 0, length: count), &indices)
	return indices.map { Int($0) }
}

private func utf8OffsetsByUTF16Offset(_ text: String) -> [Int] {
	var offsets = [Int](repeating: 0, count: text.utf16.count + 1)
	var utf16Offset = 0
	var utf8Offset = 0
	for character in text {
		let string = String(character)
		let nextUTF16 = utf16Offset + string.utf16.count
		let nextUTF8 = utf8Offset + string.utf8.count
		if utf16Offset < offsets.count {
			offsets[utf16Offset] = utf8Offset
		}
		if utf16Offset + 1 <= nextUTF16, utf16Offset + 1 < offsets.count {
			for index in (utf16Offset + 1) ... nextUTF16 where index < offsets.count {
				offsets[index] = nextUTF8
			}
		}
		utf16Offset = nextUTF16
		utf8Offset = nextUTF8
	}
	return offsets
}
