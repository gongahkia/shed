import CoreText
import Dispatch
import Metal
import os
@testable import ItsyRender
import Testing

@Test func lineShaperShapesGlyphsWithAtlasUVs() throws {
	let device = try #require(MTLCreateSystemDefaultDevice())
	var atlas = try GlyphAtlas(device: device)
	let shaper = LineShaper()
	let font = CTFontCreateWithName("Menlo" as CFString, 14, nil)
	let glyphs = try shaper.shape("Hello", font: font, atlas: &atlas)
	#expect(glyphs.count == 5)
	#expect(glyphs.allSatisfy { $0.glyphID != 0 })
	#expect(glyphs.allSatisfy { $0.atlasUV.u0 >= 0 && $0.atlasUV.u1 <= 1 && $0.atlasUV.v0 >= 0 && $0.atlasUV.v1 <= 1 })
	let firstEntry = try atlas.entry(for: glyphs[0].glyphID, font: font)
	#expect(glyphs[0].atlasUV.u0 == CGFloat(firstEntry.textureX) / CGFloat(atlas.texture.width))
	#expect(glyphs[0].atlasUV.v0 == CGFloat(firstEntry.textureY) / CGFloat(atlas.texture.height))
	#expect(glyphs[0].atlasUV.u1 == CGFloat(firstEntry.textureX + firstEntry.width) / CGFloat(atlas.texture.width))
	#expect(glyphs[0].atlasUV.v1 == CGFloat(firstEntry.textureY + firstEntry.height) / CGFloat(atlas.texture.height))
}

@Test func lineShaperShapes100LineBufferWithinBudget() throws {
	let device = try #require(MTLCreateSystemDefaultDevice())
	var atlas = try GlyphAtlas(device: device)
	let shaper = LineShaper()
	let font = CTFontCreateWithName("Menlo" as CFString, 14, nil)
	let lines = (0 ..< 100).map { "let value\($0) = object.methodCall(argument: \(($0 + 7) * 13))" }
	for line in lines {
		_ = try shaper.shape(line, font: font, atlas: &atlas)
	}
	let log = OSLog(subsystem: "dev.itsy.editor.tests", category: "LineShaper")
	let signpostID = OSSignpostID(log: log)
	os_signpost(.begin, log: log, name: "shape-100-lines", signpostID: signpostID)
	let start = DispatchTime.now().uptimeNanoseconds
	for line in lines {
		_ = try shaper.shape(line, font: font, atlas: &atlas)
	}
	let end = DispatchTime.now().uptimeNanoseconds
	os_signpost(.end, log: log, name: "shape-100-lines", signpostID: signpostID)
	#if !DEBUG
	#expect(Double(end - start) / 1_000_000 < 2.0)
	#endif
}
