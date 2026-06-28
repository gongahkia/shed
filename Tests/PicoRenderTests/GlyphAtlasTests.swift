import CoreText
import Metal
@testable import PicoRender
import Testing

@Test func glyphAtlasUploadsCoreTextRaster() throws {
	let device = try #require(MTLCreateSystemDefaultDevice())
	let atlas = try GlyphAtlas(device: device)
	let font = CTFontCreateWithName("Menlo" as CFString, 14, nil)
	var chars: [UniChar] = Array("Hello".utf16)
	var glyphs = [CGGlyph](repeating: 0, count: chars.count)
	#expect(CTFontGetGlyphsForCharacters(font, &chars, &glyphs, chars.count))
	for glyph in Set(glyphs) {
		let entry = try atlas.entry(for: glyph, font: font)
		let reference = try GlyphRasterizer.rasterize(glyph: glyph, font: font)
		var readback = [UInt8](repeating: 0, count: entry.width * entry.height)
		atlas.texture.getBytes(
			&readback,
			bytesPerRow: entry.width,
			from: entry.textureRegion,
			mipmapLevel: 0
		)
		#expect(readback == reference.pixels)
	}
}
