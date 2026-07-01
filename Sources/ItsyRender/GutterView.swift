import AppKit
import ItsyEditor

struct GutterMarkerLayout: Equatable {
	var marker: GutterMarker
	var rect: CGRect
}

final class GutterView: NSView {
	private static let lineNumberTextColor = NSColor(calibratedRed: 0.68, green: 0.70, blue: 0.74, alpha: 1)
	private static let defaultFont = NSFont.monospacedSystemFont(ofSize: 14.95, weight: .regular)

	var showsLineNumbers = false {
		didSet { needsDisplay = true }
	}
	var lineCount = 0 {
		didSet { needsDisplay = true }
	}
	var visibleLineRange: Range<Int> = 0 ..< 0 {
		didSet { needsDisplay = true }
	}
	var topLineIndex = 0 {
		didSet { needsDisplay = true }
	}
	var topContentInset: CGFloat = 0 {
		didSet { needsDisplay = true }
	}
	var textInsetY: CGFloat = 6 {
		didSet { needsDisplay = true }
	}
	var lineHeight: CGFloat = 20 {
		didSet { needsDisplay = true }
	}
	var lineNumberRightEdge: CGFloat = 0 {
		didSet { needsDisplay = true }
	}
	var fontName = "Menlo" {
		didSet { cachedFont = nil; needsDisplay = true }
	}
	var fontSize: CGFloat = 14.95 {
		didSet { cachedFont = nil; needsDisplay = true }
	}
	var markerLayouts: [GutterMarkerLayout] = [] {
		didSet { needsDisplay = true }
	}

	private var cachedFont: NSFont?

	override var isFlipped: Bool { true }
	override var isOpaque: Bool { false }

	override func hitTest(_ point: NSPoint) -> NSView? {
		nil
	}

	func marker(atLocalPoint point: NSPoint) -> GutterMarker? {
		markerLayouts.first { $0.rect.insetBy(dx: -3, dy: -2).contains(point) }?.marker
	}

	func rect(forMarkerID id: String) -> CGRect? {
		markerLayouts.first { $0.marker.id == id }?.rect
	}

	var visibleLineNumberLabels: [String] {
		guard showsLineNumbers else {
			return []
		}
		return visibleLineRange.map { "\($0 + 1)" }
	}

	override func draw(_ dirtyRect: NSRect) {
		super.draw(dirtyRect)
		drawLineNumbers(in: dirtyRect)
		drawMarkers(in: dirtyRect)
	}

	private func drawLineNumbers(in dirtyRect: NSRect) {
		guard showsLineNumbers else {
			return
		}
		let attributes = lineNumberAttributes
		for lineIndex in visibleLineRange {
			let label = "\(lineIndex + 1)"
			let size = label.size(withAttributes: attributes)
			let origin = CGPoint(
				x: lineNumberRightEdge - size.width,
				y: topContentInset + textInsetY + CGFloat(lineIndex - topLineIndex) * lineHeight + max(0, (lineHeight - size.height) / 2)
			)
			let rect = CGRect(origin: origin, size: size)
			guard rect.intersects(dirtyRect) else {
				continue
			}
			label.draw(at: origin, withAttributes: attributes)
		}
	}

	private func drawMarkers(in dirtyRect: NSRect) {
		guard let context = NSGraphicsContext.current?.cgContext else {
			return
		}
		for layout in markerLayouts where layout.rect.intersects(dirtyRect) {
			let color = layout.marker.color ?? Self.markerColor(for: layout.marker.severity)
			context.setFillColor(Self.cgColor(from: color))
			if layout.marker.placement == .betweenLines {
				for slice in caretSlices(for: layout.rect) where slice.width > 0 {
					context.fill(slice)
				}
			} else {
				context.fill(layout.rect)
			}
		}
	}

	private var lineNumberAttributes: [NSAttributedString.Key: Any] {
		[
			.font: font,
			.foregroundColor: Self.lineNumberTextColor,
		]
	}

	private var font: NSFont {
		if let cachedFont {
			return cachedFont
		}
		let font = NSFont(name: fontName, size: fontSize) ?? Self.defaultFont
		cachedFont = font
		return font
	}

	private func caretSlices(for rect: CGRect) -> [CGRect] {
		[
			CGRect(x: rect.minX, y: rect.minY, width: rect.width, height: 2),
			CGRect(x: rect.minX + 1, y: rect.minY + 2, width: rect.width - 2, height: 2),
			CGRect(x: rect.minX + 3, y: rect.minY + 4, width: rect.width - 6, height: 2),
		]
	}

	private static func markerColor(for severity: WorkspaceProblemSeverity) -> SIMD4<Float> {
		switch severity {
		case .error:
			return SIMD4<Float>(0.95, 0.25, 0.22, 1.0)
		case .warning:
			return SIMD4<Float>(0.95, 0.68, 0.18, 1.0)
		case .info:
			return SIMD4<Float>(0.24, 0.56, 0.96, 1.0)
		case .hint:
			return SIMD4<Float>(0.58, 0.62, 0.68, 1.0)
		}
	}

	private static func cgColor(from color: SIMD4<Float>) -> CGColor {
		CGColor(
			red: CGFloat(color.x),
			green: CGFloat(color.y),
			blue: CGFloat(color.z),
			alpha: CGFloat(color.w)
		)
	}
}
