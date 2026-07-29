import AppKit
import Metal

public struct GutterColorPalette: Sendable, Equatable {
	public var background: SIMD4<Float>
	public var lineNumber: SIMD4<Float>
	public var activeLineNumber: SIMD4<Float>
	public var error: SIMD4<Float>
	public var warning: SIMD4<Float>
	public var info: SIMD4<Float>
	public var hint: SIMD4<Float>

	public init(
		background: SIMD4<Float>,
		lineNumber: SIMD4<Float>,
		activeLineNumber: SIMD4<Float>,
		error: SIMD4<Float>,
		warning: SIMD4<Float>,
		info: SIMD4<Float>,
		hint: SIMD4<Float>
	) {
		self.background = background
		self.lineNumber = lineNumber
		self.activeLineNumber = activeLineNumber
		self.error = error
		self.warning = warning
		self.info = info
		self.hint = hint
	}
}

public struct EditorColorPalette: Sendable, Equatable {
	public static let defaultLight = EditorColorPalette(
		background: SIMD4<Float>(1, 1, 1, 1),
		foreground: SIMD4<Float>(0.08, 0.09, 0.11, 1),
		cursor: SIMD4<Float>(0.08, 0.09, 0.11, 1),
		selection: SIMD4<Float>(0.25, 0.45, 0.95, 0.35),
		findMatch: SIMD4<Float>(0.80, 0.62, 0.12, 0.34),
		findMatchHighlight: SIMD4<Float>(0.80, 0.62, 0.12, 0.20),
		documentHighlightUnderline: SIMD4<Float>(0.22, 0.42, 0.90, 0.42),
		inlayHintForeground: SIMD4<Float>(0.45, 0.48, 0.54, 1),
		gutter: GutterColorPalette(
			background: SIMD4<Float>(1, 1, 1, 1),
			lineNumber: SIMD4<Float>(0.68, 0.70, 0.74, 1),
			activeLineNumber: SIMD4<Float>(0.32, 0.34, 0.38, 1),
			error: SIMD4<Float>(0.95, 0.25, 0.22, 1),
			warning: SIMD4<Float>(0.95, 0.68, 0.18, 1),
			info: SIMD4<Float>(0.24, 0.56, 0.96, 1),
			hint: SIMD4<Float>(0.58, 0.62, 0.68, 1)
		)
	)

	public var background: SIMD4<Float>
	public var foreground: SIMD4<Float>
	public var cursor: SIMD4<Float>
	public var selection: SIMD4<Float>
	public var findMatch: SIMD4<Float>
	public var findMatchHighlight: SIMD4<Float>
	public var documentHighlightUnderline: SIMD4<Float>
	public var inlayHintForeground: SIMD4<Float>
	public var gutter: GutterColorPalette

	public init(
		background: SIMD4<Float>,
		foreground: SIMD4<Float>,
		cursor: SIMD4<Float>,
		selection: SIMD4<Float>,
		findMatch: SIMD4<Float>,
		findMatchHighlight: SIMD4<Float>,
		documentHighlightUnderline: SIMD4<Float>,
		inlayHintForeground: SIMD4<Float>,
		gutter: GutterColorPalette
	) {
		self.background = background
		self.foreground = foreground
		self.cursor = cursor
		self.selection = selection
		self.findMatch = findMatch
		self.findMatchHighlight = findMatchHighlight
		self.documentHighlightUnderline = documentHighlightUnderline
		self.inlayHintForeground = inlayHintForeground
		self.gutter = gutter
	}

	public var metalClearColor: MTLClearColor {
		MTLClearColor(
			red: Double(background.x),
			green: Double(background.y),
			blue: Double(background.z),
			alpha: Double(background.w)
		)
	}

	public var nsBackgroundColor: NSColor {
		NSColor(
			srgbRed: CGFloat(background.x),
			green: CGFloat(background.y),
			blue: CGFloat(background.z),
			alpha: CGFloat(background.w)
		)
	}
}
