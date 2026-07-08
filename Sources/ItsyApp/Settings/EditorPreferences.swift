// @file editor preference model and settings bridge.
import AppKit
import CoreText
import Foundation
import ItsyConfig

struct EditorPreferences: Equatable {
	struct FontChoice: Equatable {
		var name: String
		var displayName: String
	}

	static let fontNameKey = "dev.itsy.editor.fontName"
	static let fontSizeKey = "dev.itsy.editor.fontSize"
	static let showLineNumbersKey = "dev.itsy.editor.showLineNumbers"
	static let defaultFontName = "Menlo"
	static let defaultFontSize: CGFloat = 14.95
	static let minFontSize: CGFloat = 9
	static let maxFontSize: CGFloat = 36
	private static let preferredFontNames = ["Menlo", "Monaco", "Courier"]
	private static let fontCatalogLock = NSLock()
	private static var cachedFontChoices: [FontChoice]?

	var fontName: String
	var fontSize: CGFloat
	var showLineNumbers: Bool

	init(fontName: String, fontSize: CGFloat, showLineNumbers: Bool) {
		self.fontName = fontName
		self.fontSize = fontSize
		self.showLineNumbers = showLineNumbers
	}

	init(settings: ItsySettings.EditorSettings) {
		let fontName = Self.isUsableEditorFontName(settings.font) ? settings.font : Self.resolvedDefaultFontName()
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
		availableFontChoices().map(\.name)
	}

	static func availableFontChoices() -> [FontChoice] {
		fontCatalogLock.lock()
		if let cachedFontChoices {
			fontCatalogLock.unlock()
			return cachedFontChoices
		}
		fontCatalogLock.unlock()

		let choices = buildFontChoices()
		fontCatalogLock.lock()
		if cachedFontChoices == nil {
			cachedFontChoices = choices
		}
		let cached = cachedFontChoices ?? choices
		fontCatalogLock.unlock()
		return cached
	}

	private static func buildFontChoices() -> [FontChoice] {
		var result: [FontChoice] = []
		var seenNames = Set<String>()
		var seenDisplayNames = Set<String>()
		func append(_ choice: FontChoice) {
			let displayKey = choice.displayName.lowercased()
			guard seenNames.insert(choice.name).inserted, seenDisplayNames.insert(displayKey).inserted else {
				return
			}
			result.append(choice)
		}
		preferredFontNames.compactMap(fontChoiceIfUsable).forEach(append)
		(CTFontManagerCopyAvailablePostScriptNames() as? [String] ?? [])
			.compactMap(fontChoiceIfUsable)
			.sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
			.forEach(append)
		if result.isEmpty {
			let name = resolvedDefaultFontName()
			result.append(FontChoice(name: name, displayName: fontDisplayNameUncached(for: name)))
		}
		return result
	}

	static func fontDisplayName(for fontName: String) -> String {
		fontCatalogLock.lock()
		let cached = cachedFontChoices?.first { $0.name == fontName }?.displayName
		fontCatalogLock.unlock()
		return cached ?? fontDisplayNameUncached(for: fontName)
	}

	private static func fontDisplayNameUncached(for fontName: String) -> String {
		let font = CTFontCreateWithName(fontName as CFString, defaultFontSize, nil)
		let displayName = CTFontCopyDisplayName(font) as String
		guard displayName != "Helvetica" || fontName == "Helvetica" else {
			return fontName
		}
		return displayName
	}

	private static func resolvedDefaultFontName() -> String {
		if NSFont(name: defaultFontName, size: defaultFontSize) != nil {
			return defaultFontName
		}
		return NSFont.monospacedSystemFont(ofSize: defaultFontSize, weight: .regular).fontName
	}

	private static func isUsableEditorFontName(_ fontName: String) -> Bool {
		fontChoiceIfUsable(fontName) != nil
	}

	private static func fontChoiceIfUsable(_ fontName: String) -> FontChoice? {
		let font = CTFontCreateWithName(fontName as CFString, defaultFontSize, nil)
		guard CTFontGetSymbolicTraits(font).contains(.traitMonoSpace) else {
			return nil
		}
		let postScriptName = CTFontCopyPostScriptName(font) as String
		let familyName = CTFontCopyFamilyName(font) as String
		guard postScriptName == fontName || familyName == fontName else {
			return nil
		}
		return FontChoice(name: fontName, displayName: CTFontCopyDisplayName(font) as String)
	}
}
