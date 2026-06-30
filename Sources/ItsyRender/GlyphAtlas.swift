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
	public let padding: Int

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

public struct GlyphAtlas {
	public static let defaultSize = 1024

	public enum RenderingMode: Sendable, Hashable {
		case grayscale
		case subpixel

		var pixelFormat: MTLPixelFormat {
			switch self {
			case .grayscale:
				return .r8Unorm
			case .subpixel:
				return .rgba8Unorm
			}
		}

		var bytesPerPixel: Int {
			switch self {
			case .grayscale:
				return 1
			case .subpixel:
				return 4
			}
		}
	}

	public let texture: MTLTexture
	public let renderingMode: RenderingMode

	private var entries: [GlyphKey: GlyphAtlasEntry] = [:]
	private var penX = 0
	private var penY = 0
	private var rowHeight = 0
	private let width: Int
	private let height: Int

	public init(device: MTLDevice, size: Int = defaultSize, renderingMode: RenderingMode = .grayscale) throws {
		let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: renderingMode.pixelFormat, width: size, height: size, mipmapped: false)
		descriptor.usage = [.shaderRead]
		guard let texture = device.makeTexture(descriptor: descriptor) else {
			throw GlyphAtlasError.noCommandDevice
		}
		self.texture = texture
		self.renderingMode = renderingMode
		width = size
		height = size
	}

	public mutating func entry(for glyph: CGGlyph, font: CTFont) throws -> GlyphAtlasEntry {
		let key = GlyphKey(font: font, glyph: glyph)
		if let entry = entries[key] {
			return entry
		}
		let bitmap = try GlyphRasterizer.rasterize(glyph: glyph, font: font, renderingMode: renderingMode)
		let origin = try allocate(width: bitmap.width, height: bitmap.height)
		let region = MTLRegionMake2D(origin.x, origin.y, bitmap.width, bitmap.height)
		try bitmap.pixels.withUnsafeBytes { bytes in
			guard let base = bytes.baseAddress else {
				throw GlyphAtlasError.contextCreationFailed
			}
			texture.replace(region: region, mipmapLevel: 0, withBytes: base, bytesPerRow: bitmap.bytesPerRow)
		}
		let entry = GlyphAtlasEntry(
			glyph: glyph,
			textureX: origin.x,
			textureY: origin.y,
			width: bitmap.width,
			height: bitmap.height,
			advance: bitmap.advance,
			bounds: bitmap.bounds,
			padding: bitmap.padding
		)
		entries[key] = entry
		return entry
	}

	private mutating func allocate(width glyphWidth: Int, height glyphHeight: Int) throws -> (x: Int, y: Int) {
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
	let bytesPerRow: Int
	let advance: CGSize
	let bounds: CGRect
	let padding: Int
}

enum GlyphRasterizer {
	static func rasterize(glyph: CGGlyph, font: CTFont, padding: Int = 1, renderingMode: GlyphAtlas.RenderingMode = .grayscale) throws -> GlyphBitmap {
		var glyph = glyph
		let rawBounds = CTFontGetBoundingRectsForGlyphs(font, .default, &glyph, nil, 1)
		let bounds: CGRect
		if rawBounds.isNull || rawBounds.isEmpty {
			bounds = CGRect(x: 0, y: 0, width: 1, height: 1)
		} else {
			let minX = floor(rawBounds.minX)
			let minY = floor(rawBounds.minY)
			let maxX = ceil(rawBounds.maxX)
			let maxY = ceil(rawBounds.maxY)
			bounds = CGRect(x: minX, y: minY, width: maxX - minX, height: maxY - minY)
		}
		let width = max(1, Int(bounds.width) + padding * 2)
		let height = max(1, Int(bounds.height) + padding * 2)
		let bytesPerRow = width * renderingMode.bytesPerPixel
		var pixels = [UInt8](repeating: 0, count: bytesPerRow * height)
		let colorSpace = renderingMode == .subpixel ? CGColorSpaceCreateDeviceRGB() : CGColorSpaceCreateDeviceGray()
		let bitmapInfo = renderingMode == .subpixel
			? CGImageAlphaInfo.premultipliedLast.rawValue
			: CGImageAlphaInfo.none.rawValue
		try pixels.withUnsafeMutableBytes { buffer in
			guard
				let base = buffer.baseAddress,
				let context = CGContext(
					data: base,
					width: width,
					height: height,
					bitsPerComponent: 8,
					bytesPerRow: bytesPerRow,
					space: colorSpace,
					bitmapInfo: bitmapInfo
				)
			else {
				throw GlyphAtlasError.contextCreationFailed
			}
			context.setShouldAntialias(true)
			context.setAllowsAntialiasing(true)
			context.setShouldSmoothFonts(true)
			context.setAllowsFontSmoothing(true)
			context.setShouldSubpixelPositionFonts(true)
			context.setAllowsFontSubpixelPositioning(true)
			context.setShouldSubpixelQuantizeFonts(true)
			context.setAllowsFontSubpixelQuantization(true)
			context.setFillColor(gray: 1, alpha: 1)
			if renderingMode == .subpixel {
				context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
			}
			context.translateBy(x: CGFloat(padding) - bounds.origin.x, y: CGFloat(padding) - bounds.origin.y)
			var position = CGPoint.zero
			CTFontDrawGlyphs(font, &glyph, &position, 1, context)
		}
		var advance = CGSize.zero
		CTFontGetAdvancesForGlyphs(font, .default, &glyph, &advance, 1)
		return GlyphBitmap(width: width, height: height, pixels: pixels, bytesPerRow: bytesPerRow, advance: advance, bounds: bounds, padding: padding)
	}
}
