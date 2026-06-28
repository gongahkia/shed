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
}

public final class LineShaper {
	private let atlas: GlyphAtlas

	public init(atlas: GlyphAtlas) {
		self.atlas = atlas
	}

	public func shape(_ line: String, font: CTFont) throws -> [ShapedGlyph] {
		guard !line.isEmpty else {
			return []
		}
		let attributed = NSAttributedString(string: line, attributes: [kCTFontAttributeName as NSAttributedString.Key: font])
		let ctLine = CTLineCreateWithAttributedString(attributed)
		let runs = CTLineGetGlyphRuns(ctLine) as NSArray
		var shaped: [ShapedGlyph] = []
		for case let run as CTRun in runs {
			let glyphCount = CTRunGetGlyphCount(run)
			guard glyphCount > 0 else {
				continue
			}
			let glyphs = copyGlyphs(run, count: glyphCount)
			let positions = copyPositions(run, count: glyphCount)
			shaped.reserveCapacity(shaped.count + glyphCount)
			for index in 0 ..< glyphCount {
				let entry = try atlas.entry(for: glyphs[index], font: font)
				let uv = atlasUV(for: entry)
				shaped.append(ShapedGlyph(glyphID: glyphs[index], x: positions[index].x, y: positions[index].y, atlasUV: uv))
			}
		}
		return shaped
	}

	private func atlasUV(for entry: GlyphAtlasEntry) -> AtlasUV {
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
