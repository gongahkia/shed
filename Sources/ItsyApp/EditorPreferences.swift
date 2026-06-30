import AppKit
import Foundation

struct EditorPreferences: Equatable {
	static let fontNameKey = "dev.itsy.editor.fontName"
	static let fontSizeKey = "dev.itsy.editor.fontSize"
	static let showLineNumbersKey = "dev.itsy.editor.showLineNumbers"
	static let defaultFontName = "Menlo"
	static let defaultFontSize: CGFloat = 14.95
	static let minFontSize: CGFloat = 9
	static let maxFontSize: CGFloat = 36
	static let fontNames = ["Menlo", "Monaco", "Courier"]

	var fontName: String
	var fontSize: CGFloat
	var showLineNumbers: Bool

	static func load(defaults: UserDefaults = .standard) -> EditorPreferences {
		let storedName = defaults.string(forKey: fontNameKey) ?? defaultFontName
		let fontName = fontNames.contains(storedName) ? storedName : defaultFontName
		let storedSize = defaults.double(forKey: fontSizeKey)
		let size = storedSize > 0 ? CGFloat(storedSize) : defaultFontSize
		return EditorPreferences(
			fontName: fontName,
			fontSize: clampedFontSize(size),
			showLineNumbers: defaults.bool(forKey: showLineNumbersKey)
		)
	}

	func save(defaults: UserDefaults = .standard) {
		defaults.set(fontName, forKey: Self.fontNameKey)
		defaults.set(Double(Self.clampedFontSize(fontSize)), forKey: Self.fontSizeKey)
		defaults.set(showLineNumbers, forKey: Self.showLineNumbersKey)
	}

	func zoomed(by delta: CGFloat) -> EditorPreferences {
		EditorPreferences(fontName: fontName, fontSize: Self.clampedFontSize(fontSize + delta), showLineNumbers: showLineNumbers)
	}

	func resetZoom() -> EditorPreferences {
		EditorPreferences(fontName: fontName, fontSize: Self.defaultFontSize, showLineNumbers: showLineNumbers)
	}

	static func clampedFontSize(_ value: CGFloat) -> CGFloat {
		min(max(value, minFontSize), maxFontSize)
	}
}
