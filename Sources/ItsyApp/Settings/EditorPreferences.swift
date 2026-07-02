// @file editor preference model and settings bridge.
import AppKit
import Foundation
import ItsyConfig

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

	init(fontName: String, fontSize: CGFloat, showLineNumbers: Bool) {
		self.fontName = fontName
		self.fontSize = fontSize
		self.showLineNumbers = showLineNumbers
	}

	init(settings: ItsySettings.EditorSettings) {
		let fontNames = Self.availableFontNames()
		let fontName = fontNames.contains(settings.font) ? settings.font : Self.resolvedDefaultFontName()
		self.init(
			fontName: fontName,
			fontSize: Self.clampedFontSize(CGFloat(settings.fontSize)),
			showLineNumbers: settings.lineNumbers
		)
	}

	static func load(defaults: UserDefaults = .standard, settingsStore: ItsySettingsStore = ItsySettingsStore()) -> EditorPreferences {
		let settings = settingsStore.load(fallback: legacySettings(defaults: defaults)).settings
		return EditorPreferences(settings: settings.editor)
	}

	static func legacySettings(defaults: UserDefaults = .standard) -> ItsySettings {
		var settings = ItsySettings.default
		let storedName = defaults.string(forKey: fontNameKey) ?? defaultFontName
		settings.editor.font = storedName
		let storedSize = defaults.double(forKey: fontSizeKey)
		settings.editor.fontSize = storedSize > 0 ? storedSize : Double(defaultFontSize)
		settings.editor.lineNumbers = defaults.bool(forKey: showLineNumbersKey)
		return settings
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

	func apply(to settings: inout ItsySettings) {
		settings.editor.font = fontName
		settings.editor.fontSize = Double(Self.clampedFontSize(fontSize))
		settings.editor.lineNumbers = showLineNumbers
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
