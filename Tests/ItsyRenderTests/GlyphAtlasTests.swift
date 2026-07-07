import CoreText
import Metal
@testable import ItsyRender
import Testing

@Test func glyphAtlasUploadsCoreTextRaster() throws {
	let device = try #require(MTLCreateSystemDefaultDevice())
	var atlas = try GlyphAtlas(device: device)
	#expect(atlas.texture.width == GlyphAtlas.defaultSize)
	#expect(atlas.texture.height == GlyphAtlas.defaultSize)
	#expect(GlyphAtlas.maxSize >= GlyphAtlas.defaultSize * 2)
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

@Test func glyphAtlasUploadsSubpixelRasterAsRGBTexture() throws {
	let device = try #require(MTLCreateSystemDefaultDevice())
	var atlas = try GlyphAtlas(device: device, renderingMode: .subpixel)
	let font = CTFontCreateWithName("Menlo" as CFString, 14, nil)
	var chars: [UniChar] = Array("A".utf16)
	var glyphs = [CGGlyph](repeating: 0, count: chars.count)
	#expect(CTFontGetGlyphsForCharacters(font, &chars, &glyphs, chars.count))
	let entry = try atlas.entry(for: glyphs[0], font: font)
	let reference = try GlyphRasterizer.rasterize(glyph: glyphs[0], font: font, renderingMode: .subpixel)
	var readback = [UInt8](repeating: 0, count: entry.width * entry.height * 4)
	atlas.texture.getBytes(
		&readback,
		bytesPerRow: entry.width * 4,
		from: entry.textureRegion,
		mipmapLevel: 0
	)
	#expect(atlas.texture.pixelFormat == .rgba8Unorm)
	#expect(reference.bytesPerRow == entry.width * 4)
	#expect(readback == reference.pixels)
}
