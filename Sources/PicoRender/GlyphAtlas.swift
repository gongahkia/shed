import CoreGraphics
import CoreText
import Foundation
import Metal

public struct GlyphAtlasEntry: Sendable {
	public let glyph: CGGlyph
	public let textureX: Int
	public let textureY: Int
	public let width: Int
	public let height: Int
	public let advance: CGSize
	public let bounds: CGRect

	public var textureRegion: MTLRegion {
		MTLRegionMake2D(textureX, textureY, width, height)
	}
}

public enum GlyphAtlasError: Error, CustomStringConvertible {
	case noCommandDevice
	case glyphTooLarge(Int, Int)
	case atlasFull
	case contextCreationFailed

	public var description: String {
		switch self {
		case .noCommandDevice:
			"Metal device is unavailable"
		case let .glyphTooLarge(width, height):
			"glyph is larger than atlas: \(width)x\(height)"
		case .atlasFull:
			"glyph atlas is full"
		case .contextCreationFailed:
			"failed to create glyph bitmap context"
		}
	}
}

public final class GlyphAtlas {
	public let texture: MTLTexture

	private var entries: [GlyphKey: GlyphAtlasEntry] = [:]
	private var penX = 0
	private var penY = 0
	private var rowHeight = 0
	private let width: Int
	private let height: Int

	public init(device: MTLDevice, size: Int = 2048) throws {
		let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .r8Unorm, width: size, height: size, mipmapped: false)
		descriptor.usage = [.shaderRead]
		guard let texture = device.makeTexture(descriptor: descriptor) else {
			throw GlyphAtlasError.noCommandDevice
		}
		self.texture = texture
		width = size
		height = size
	}

	public func entry(for glyph: CGGlyph, font: CTFont) throws -> GlyphAtlasEntry {
		let key = GlyphKey(font: font, glyph: glyph)
		if let entry = entries[key] {
			return entry
		}
		let bitmap = try GlyphRasterizer.rasterize(glyph: glyph, font: font)
		let origin = try allocate(width: bitmap.width, height: bitmap.height)
		let region = MTLRegionMake2D(origin.x, origin.y, bitmap.width, bitmap.height)
		try bitmap.pixels.withUnsafeBytes { bytes in
			guard let base = bytes.baseAddress else {
				throw GlyphAtlasError.contextCreationFailed
			}
			texture.replace(region: region, mipmapLevel: 0, withBytes: base, bytesPerRow: bitmap.width)
		}
		let entry = GlyphAtlasEntry(
			glyph: glyph,
			textureX: origin.x,
			textureY: origin.y,
			width: bitmap.width,
			height: bitmap.height,
			advance: bitmap.advance,
			bounds: bitmap.bounds
		)
		entries[key] = entry
		return entry
	}

	private func allocate(width glyphWidth: Int, height glyphHeight: Int) throws -> (x: Int, y: Int) {
		guard glyphWidth <= width, glyphHeight <= height else {
			throw GlyphAtlasError.glyphTooLarge(glyphWidth, glyphHeight)
		}
		if penX + glyphWidth > width {
			penX = 0
			penY += rowHeight
			rowHeight = 0
		}
		guard penY + glyphHeight <= height else {
			throw GlyphAtlasError.atlasFull
		}
		let origin = (x: penX, y: penY)
		penX += glyphWidth
		rowHeight = max(rowHeight, glyphHeight)
		return origin
	}
}

private struct GlyphKey: Hashable {
	let postScriptName: String
	let size: Double
	let glyph: CGGlyph

	init(font: CTFont, glyph: CGGlyph) {
		postScriptName = CTFontCopyPostScriptName(font) as String
		size = CTFontGetSize(font)
		self.glyph = glyph
	}
}

struct GlyphBitmap: Equatable {
	let width: Int
	let height: Int
	let pixels: [UInt8]
	let advance: CGSize
	let bounds: CGRect
}

enum GlyphRasterizer {
	static func rasterize(glyph: CGGlyph, font: CTFont, padding: Int = 1) throws -> GlyphBitmap {
		var glyph = glyph
		var bounds = CTFontGetBoundingRectsForGlyphs(font, .default, &glyph, nil, 1)
		if bounds.isNull || bounds.isEmpty {
			bounds = CGRect(x: 0, y: 0, width: 1, height: 1)
		}
		let width = max(1, Int(ceil(bounds.width)) + padding * 2)
		let height = max(1, Int(ceil(bounds.height)) + padding * 2)
		var pixels = [UInt8](repeating: 0, count: width * height)
		let colorSpace = CGColorSpaceCreateDeviceGray()
		try pixels.withUnsafeMutableBytes { buffer in
			guard
				let base = buffer.baseAddress,
				let context = CGContext(
					data: base,
					width: width,
					height: height,
					bitsPerComponent: 8,
					bytesPerRow: width,
					space: colorSpace,
					bitmapInfo: CGImageAlphaInfo.none.rawValue
				)
			else {
				throw GlyphAtlasError.contextCreationFailed
			}
			context.setShouldAntialias(true)
			context.setAllowsAntialiasing(true)
			context.setFillColor(gray: 1, alpha: 1)
			context.translateBy(x: CGFloat(padding) - bounds.origin.x, y: CGFloat(padding) - bounds.origin.y)
			var position = CGPoint.zero
			CTFontDrawGlyphs(font, &glyph, &position, 1, context)
		}
		var advance = CGSize.zero
		CTFontGetAdvancesForGlyphs(font, .default, &glyph, &advance, 1)
		return GlyphBitmap(width: width, height: height, pixels: pixels, advance: advance, bounds: bounds)
	}
}
