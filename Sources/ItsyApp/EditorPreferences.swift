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
	private static let preferredFontNames = ["Menlo", "Monaco", "Courier"]

	var fontName: String
	var fontSize: CGFloat
	var showLineNumbers: Bool

	static func load(defaults: UserDefaults = .standard) -> EditorPreferences {
		let storedName = defaults.string(forKey: fontNameKey) ?? defaultFontName
		let fontNames = availableFontNames()
		let fontName = fontNames.contains(storedName) ? storedName : resolvedDefaultFontName()
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

	static func availableFontNames() -> [String] {
		var result: [String] = []
		var seenNames = Set<String>()
		var seenDisplayNames = Set<String>()
		func append(_ name: String) {
			guard isUsableEditorFontName(name) else {
				return
			}
			let displayKey = fontDisplayName(for: name).lowercased()
			guard seenNames.insert(name).inserted, seenDisplayNames.insert(displayKey).inserted else {
				return
			}
			result.append(name)
		}
		preferredFontNames.forEach(append)
		NSFontManager.shared.availableFonts
			.sorted { fontDisplayName(for: $0).localizedCaseInsensitiveCompare(fontDisplayName(for: $1)) == .orderedAscending }
			.forEach(append)
		if result.isEmpty {
			result.append(resolvedDefaultFontName())
		}
		return result
	}

	static func fontDisplayName(for fontName: String) -> String {
		guard let font = NSFont(name: fontName, size: defaultFontSize) else {
			return fontName
		}
		return font.displayName ?? font.familyName ?? fontName
	}

	private static func resolvedDefaultFontName() -> String {
		if NSFont(name: defaultFontName, size: defaultFontSize) != nil {
			return defaultFontName
		}
		return NSFont.monospacedSystemFont(ofSize: defaultFontSize, weight: .regular).fontName
	}

	private static func isUsableEditorFontName(_ fontName: String) -> Bool {
		guard let font = NSFont(name: fontName, size: defaultFontSize) else {
			return false
		}
		return font.fontDescriptor.symbolicTraits.contains(.monoSpace)
	}
}
