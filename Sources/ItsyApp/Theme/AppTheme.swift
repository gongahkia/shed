import AppKit
import Foundation
import ItsyConfig
import ItsyRender
import ItsySyntax

extension Notification.Name {
	static let itsyThemeChanged = Notification.Name("dev.itsy.theme.changed")
}

struct TerminalThemePalette {
	var background: NSColor
	var foreground: NSColor
	var cursor: NSColor
	var ansi: [Int: NSColor]
}

struct AppThemePalette {
	var id: String
	var isDark: Bool
	var foreground: NSColor
	var secondaryForeground: NSColor
	var disabledForeground: NSColor
	var errorForeground: NSColor
	var warningForeground: NSColor
	var successForeground: NSColor
	var focusBorder: NSColor
	var border: NSColor
	var panelBackground: NSColor
	var panelForeground: NSColor
	var inputBackground: NSColor
	var inputForeground: NSColor
	var inputPlaceholder: NSColor
	var inputBorder: NSColor
	var listSelectionBackground: NSColor
	var listSelectionForeground: NSColor
	var listHoverBackground: NSColor
	var buttonBackground: NSColor
	var buttonForeground: NSColor
	var sidebarBackground: NSColor
	var sidebarForeground: NSColor
	var sidebarBorder: NSColor
	var tabActiveBackground: NSColor
	var tabInactiveBackground: NSColor
	var tabActiveForeground: NSColor
	var tabInactiveForeground: NSColor
	var tabBorder: NSColor
	var statusBackground: NSColor
	var statusForeground: NSColor
	var bannerBackground: NSColor
	var bannerForeground: NSColor
	var editor: EditorColorPalette
	var terminal: TerminalThemePalette
	var gitAdded: NSColor
	var gitModified: NSColor
	var gitRemoved: NSColor

	var gitGutterSettings: ItsySettings.ThemeSettings.GitGutterSettings {
		ItsySettings.ThemeSettings.GitGutterSettings(
			added: Self.hexString(gitAdded),
			modified: Self.hexString(gitModified),
			removed: Self.hexString(gitRemoved)
		)
	}

	func contrastFailures(minimumRatio: CGFloat = 4.5) -> [String] {
		func editorColor(_ color: SIMD4<Float>) -> NSColor {
			NSColor(srgbRed: CGFloat(color.x), green: CGFloat(color.y), blue: CGFloat(color.z), alpha: CGFloat(color.w))
		}
		let pairs: [(String, NSColor, NSColor)] = [
			("editor", editorColor(editor.foreground), editorColor(editor.background)),
			("panel", panelForeground, panelBackground),
			("input", inputForeground, inputBackground),
			("sidebar", sidebarForeground, sidebarBackground),
			("tab-active", tabActiveForeground, tabActiveBackground),
			("tab-inactive", tabInactiveForeground, tabInactiveBackground),
			("status", statusForeground, statusBackground),
			("button", buttonForeground, buttonBackground),
		]
		return pairs.compactMap { name, foreground, background in
			Self.contrastRatio(foreground, background) >= minimumRatio ? nil : name
		}
	}

	static func contrastRatio(_ foreground: NSColor, _ background: NSColor) -> CGFloat {
		func components(_ color: NSColor) -> (red: CGFloat, green: CGFloat, blue: CGFloat, alpha: CGFloat) {
			let color = color.usingColorSpace(.sRGB) ?? color
			return (color.redComponent, color.greenComponent, color.blueComponent, color.alphaComponent)
		}
		func luminance(_ color: (red: CGFloat, green: CGFloat, blue: CGFloat, alpha: CGFloat)) -> CGFloat {
			func linear(_ value: CGFloat) -> CGFloat {
				value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)
			}
			return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
		}
		let background = components(background)
		let rawForeground = components(foreground)
		let foreground = (
			red: rawForeground.red * rawForeground.alpha + background.red * (1 - rawForeground.alpha),
			green: rawForeground.green * rawForeground.alpha + background.green * (1 - rawForeground.alpha),
			blue: rawForeground.blue * rawForeground.alpha + background.blue * (1 - rawForeground.alpha),
			alpha: CGFloat(1)
		)
		let lighter = max(luminance(foreground), luminance(background))
		let darker = min(luminance(foreground), luminance(background))
		return (lighter + 0.05) / (darker + 0.05)
	}

	init(settings: ItsySettings) {
		id = settings.theme.id
		let theme = try? ItsyTheme.loadChoice(id: settings.theme.id)
		let seed = ThemeSeed.seed(for: settings.theme.id)
		func color(_ key: String, _ fallback: NSColor) -> NSColor {
			theme?.color(for: key).map(Self.nsColor) ?? fallback
		}
		func simd(_ key: String, _ fallback: NSColor) -> SIMD4<Float> {
			Self.simdColor(color(key, fallback))
		}

		let editorBackground = color("editor.background", seed.editorBackground)
		let editorForeground = color("editor.foreground", seed.foreground)
		let accent = color("focusBorder", seed.accent)
		isDark = editorBackground.itsyLuminance < 0.5
		foreground = Self.readable(color("foreground", editorForeground), against: editorBackground)
		secondaryForeground = color("descriptionForeground", foreground.withAlphaComponent(0.72))
		disabledForeground = color("disabledForeground", foreground.withAlphaComponent(0.42))
		errorForeground = color("errorForeground", seed.error)
		warningForeground = color("itsy.warningForeground", seed.warning)
		successForeground = color("itsy.successForeground", seed.success)
		focusBorder = accent
		border = color("widget.border", seed.border)
		panelBackground = color("panel.background", seed.panelBackground)
		panelForeground = Self.readable(color("panel.foreground", foreground), against: panelBackground)
		inputBackground = color("input.background", seed.inputBackground)
		inputForeground = Self.readable(color("input.foreground", foreground), against: inputBackground)
		inputPlaceholder = color("input.placeholderForeground", secondaryForeground)
		inputBorder = color("input.border", border)
		listSelectionBackground = color("list.activeSelectionBackground", accent.withAlphaComponent(isDark ? 0.35 : 0.22))
		listSelectionForeground = color("list.activeSelectionForeground", foreground)
		listHoverBackground = color("list.hoverBackground", foreground.withAlphaComponent(isDark ? 0.08 : 0.06))
		buttonBackground = color("button.background", accent)
		buttonForeground = Self.readable(color("button.foreground", seed.buttonForeground), against: buttonBackground)
		sidebarBackground = color("sideBar.background", seed.sidebarBackground)
		sidebarForeground = Self.readable(color("sideBar.foreground", foreground), against: sidebarBackground)
		sidebarBorder = color("sideBar.border", border)
		tabActiveBackground = color("tab.activeBackground", editorBackground)
		tabInactiveBackground = color("tab.inactiveBackground", seed.panelBackground)
		tabActiveForeground = Self.readable(color("tab.activeForeground", foreground), against: tabActiveBackground)
		tabInactiveForeground = Self.readable(color("tab.inactiveForeground", secondaryForeground), against: tabInactiveBackground)
		tabBorder = color("tab.border", border)
		statusBackground = color("statusBar.background", seed.statusBackground)
		statusForeground = Self.readable(color("statusBar.foreground", seed.statusForeground), against: statusBackground)
		bannerBackground = color("itsy.banner.background", accent.withAlphaComponent(0.14))
		bannerForeground = color("itsy.banner.foreground", foreground)
		gitAdded = color("itsy.git.added", Self.nsColor(hex: settings.theme.gitGutter.added, fallback: seed.success))
		gitModified = color("itsy.git.modified", Self.nsColor(hex: settings.theme.gitGutter.modified, fallback: seed.warning))
		gitRemoved = color("itsy.git.removed", Self.nsColor(hex: settings.theme.gitGutter.removed, fallback: seed.error))
		editor = EditorColorPalette(
			background: simd("editor.background", editorBackground),
			foreground: Self.simdColor(Self.readable(color("editor.foreground", editorForeground), against: editorBackground)),
			cursor: simd("editorCursor.foreground", foreground),
			selection: simd("editor.selectionBackground", accent.withAlphaComponent(0.32)),
			findMatch: simd("editor.findMatchBackground", seed.findMatch),
			findMatchHighlight: simd("editor.findMatchHighlightBackground", seed.findMatch.withAlphaComponent(0.22)),
			documentHighlightUnderline: simd("itsy.editor.documentHighlightUnderline", accent.withAlphaComponent(0.50)),
			inlayHintForeground: simd("itsy.editor.inlayHintForeground", secondaryForeground),
			gutter: GutterColorPalette(
				background: simd("editorGutter.background", editorBackground),
				lineNumber: simd("editorLineNumber.foreground", secondaryForeground),
				activeLineNumber: simd("editorLineNumber.activeForeground", foreground),
				error: Self.simdColor(errorForeground),
				warning: Self.simdColor(warningForeground),
				info: Self.simdColor(accent),
				hint: Self.simdColor(secondaryForeground)
			)
		)
		terminal = TerminalThemePalette(
			background: color("terminal.background", editorBackground),
			foreground: color("terminal.foreground", editorForeground),
			cursor: color("terminalCursor.background", foreground),
			ansi: Self.terminalANSIColors(theme: theme, seed: seed)
		)
	}

	private static func terminalANSIColors(theme: ItsyTheme?, seed: ThemeSeed) -> [Int: NSColor] {
		let ids = [
			"terminal.ansiBlack", "terminal.ansiRed", "terminal.ansiGreen", "terminal.ansiYellow",
			"terminal.ansiBlue", "terminal.ansiMagenta", "terminal.ansiCyan", "terminal.ansiWhite",
			"terminal.ansiBrightBlack", "terminal.ansiBrightRed", "terminal.ansiBrightGreen", "terminal.ansiBrightYellow",
			"terminal.ansiBrightBlue", "terminal.ansiBrightMagenta", "terminal.ansiBrightCyan", "terminal.ansiBrightWhite",
		]
		return Dictionary(uniqueKeysWithValues: ids.enumerated().map { index, id in
			(index, theme?.color(for: id).map(nsColor) ?? seed.ansi[index] ?? .labelColor)
		})
	}

	private static func nsColor(_ color: SyntaxColor) -> NSColor {
		NSColor(srgbRed: CGFloat(color.red), green: CGFloat(color.green), blue: CGFloat(color.blue), alpha: CGFloat(color.alpha))
	}

	private static func readable(_ foreground: NSColor, against background: NSColor, minimumRatio: CGFloat = 4.5) -> NSColor {
		guard contrastRatio(foreground, background) < minimumRatio else {
			return foreground
		}
		return contrastRatio(.white, background) >= contrastRatio(.black, background) ? .white : .black
	}

	private static func simdColor(_ color: NSColor) -> SIMD4<Float> {
		let converted = color.usingColorSpace(.sRGB) ?? color
		return SIMD4<Float>(
			Float(converted.redComponent),
			Float(converted.greenComponent),
			Float(converted.blueComponent),
			Float(converted.alphaComponent)
		)
	}

	private static func nsColor(hex: String, fallback: NSColor) -> NSColor {
		(try? SyntaxColor(hex: hex)).map(nsColor) ?? fallback
	}

	private static func hexString(_ color: NSColor) -> String {
		let converted = color.usingColorSpace(.sRGB) ?? color
		return String(
			format: "#%02X%02X%02X%02X",
			Int((converted.redComponent * 255).rounded()),
			Int((converted.greenComponent * 255).rounded()),
			Int((converted.blueComponent * 255).rounded()),
			Int((converted.alphaComponent * 255).rounded())
		)
	}
}

@MainActor enum AppTheme {
	private static var observer: NSObjectProtocol?
	private(set) static var palette = AppThemePalette(settings: .default)

	static func install() {
		guard observer == nil else {
			return
		}
		observer = NotificationCenter.default.addObserver(
			forName: NSWindow.didBecomeKeyNotification,
			object: nil,
			queue: .main
		) { notification in
			MainActor.assumeIsolated {
				guard let window = notification.object as? NSWindow else {
					return
				}
				AppThemeApplier.apply(palette, to: window)
			}
		}
	}

	static func update(settings: ItsySettings) {
		palette = AppThemePalette(settings: settings.normalized())
		NSApp.appearance = NSAppearance(named: palette.isDark ? .darkAqua : .aqua)
		applyToOpenWindows()
		NotificationCenter.default.post(name: .itsyThemeChanged, object: nil, userInfo: ["palette": palette])
	}

	static func applyToOpenWindows() {
		for window in NSApp.windows {
			AppThemeApplier.apply(palette, to: window)
		}
	}
}

@MainActor enum AppThemeApplier {
	static func apply(_ palette: AppThemePalette, to window: NSWindow) {
		window.appearance = NSAppearance(named: palette.isDark ? .darkAqua : .aqua)
		if let contentView = window.contentView {
			contentView.wantsLayer = true
			contentView.layer?.backgroundColor = palette.panelBackground.cgColor
			apply(palette, to: contentView)
		}
	}

	static func apply(_ palette: AppThemePalette, to view: NSView) {
		if let editor = view as? MetalTextView {
			editor.applyEditorColorPalette(palette.editor)
		} else if let terminal = view as? ItsyTerminalView {
			terminal.applyTerminalTheme(palette.terminal)
		} else if let scrollView = view as? NSScrollView {
			scrollView.backgroundColor = palette.panelBackground
			scrollView.contentView.backgroundColor = palette.panelBackground
		} else if let outlineView = view as? NSOutlineView {
			outlineView.backgroundColor = palette.panelBackground
			outlineView.gridColor = palette.border
		} else if let tableView = view as? NSTableView {
			tableView.backgroundColor = palette.panelBackground
			tableView.gridColor = palette.border
		} else if let textField = view as? NSTextField {
			apply(palette, to: textField)
		} else if let textView = view as? NSTextView {
			textView.textColor = palette.foreground
			textView.backgroundColor = palette.panelBackground
			textView.insertionPointColor = palette.foreground
		} else if let button = view as? NSButton {
			apply(palette, to: button)
		} else if view.wantsLayer, !(view is NSControl) {
			view.layer?.borderColor = palette.border.cgColor
		}
		for subview in view.subviews {
			apply(palette, to: subview)
		}
	}

	private static func apply(_ palette: AppThemePalette, to textField: NSTextField) {
		if textField.isEditable || textField.isSelectable, textField.isBordered {
			textField.textColor = palette.inputForeground
			textField.backgroundColor = palette.inputBackground
			textField.layer?.borderColor = palette.inputBorder.cgColor
		} else {
			textField.textColor = themedLabelColor(existing: textField.textColor, palette: palette)
		}
		if let placeholder = textField.placeholderString {
			textField.placeholderAttributedString = NSAttributedString(
				string: placeholder,
				attributes: [.foregroundColor: palette.inputPlaceholder]
			)
		}
	}

	private static func apply(_ palette: AppThemePalette, to button: NSButton) {
		button.contentTintColor = button.isEnabled ? palette.buttonForeground : palette.disabledForeground
		guard !button.title.isEmpty else {
			return
		}
		button.attributedTitle = NSAttributedString(
			string: button.title,
			attributes: [
				.font: button.font ?? NSFont.systemFont(ofSize: NSFont.systemFontSize),
				.foregroundColor: button.isEnabled ? palette.buttonForeground : palette.disabledForeground,
			]
		)
	}

	private static func themedLabelColor(existing: NSColor?, palette: AppThemePalette) -> NSColor {
		guard let existing else {
			return palette.foreground
		}
		if existing.isClose(to: .systemRed) {
			return palette.errorForeground
		}
		if existing.isClose(to: .systemOrange) {
			return palette.warningForeground
		}
		if existing.isClose(to: .systemGreen) {
			return palette.successForeground
		}
		if existing.isClose(to: .secondaryLabelColor) {
			return palette.secondaryForeground
		}
		return palette.foreground
	}
}

private struct ThemeSeed {
	var editorBackground: NSColor
	var foreground: NSColor
	var panelBackground: NSColor
	var sidebarBackground: NSColor
	var inputBackground: NSColor
	var statusBackground: NSColor
	var statusForeground: NSColor
	var accent: NSColor
	var border: NSColor
	var error: NSColor
	var warning: NSColor
	var success: NSColor
	var buttonForeground: NSColor
	var findMatch: NSColor
	var ansi: [Int: NSColor]

	static func seed(for id: String) -> ThemeSeed {
		switch id {
		case "bundled:default-dark":
			return dark(bg: "#1E1E1E", fg: "#D4D4D4", panel: "#252526", side: "#252526", accent: "#007ACC")
		case "bundled:solarized-dark":
			return dark(bg: "#002B36", fg: "#839496", panel: "#073642", side: "#073642", accent: "#268BD2")
		case "bundled:gruvbox-dark":
			return dark(bg: "#282828", fg: "#EBDBB2", panel: "#32302F", side: "#3C3836", accent: "#83A598")
		case "bundled:nord":
			return dark(bg: "#2E3440", fg: "#D8DEE9", panel: "#3B4252", side: "#3B4252", accent: "#88C0D0")
		case "bundled:catppuccin-mocha":
			return dark(bg: "#1E1E2E", fg: "#CDD6F4", panel: "#181825", side: "#181825", accent: "#89B4FA")
		case "bundled:tokyo-night":
			return dark(bg: "#1A1B26", fg: "#C0CAF5", panel: "#16161E", side: "#16161E", accent: "#7AA2F7")
		case "bundled:catppuccin-latte":
			return light(bg: "#EFF1F5", fg: "#4C4F69", panel: "#E6E9EF", side: "#E6E9EF", accent: "#1E66F5")
		case "bundled:solarized-light":
			return light(bg: "#FDF6E3", fg: "#586E75", panel: "#EEE8D5", side: "#EEE8D5", accent: "#268BD2")
		case "bundled:gruvbox-light":
			return light(bg: "#FBF1C7", fg: "#3C3836", panel: "#F2E5BC", side: "#EBDBB2", accent: "#076678")
		default:
			return light(bg: "#FFFFFF", fg: "#1F2328", panel: "#F3F3F3", side: "#F3F3F3", accent: "#0969DA")
		}
	}

	private static func dark(bg: String, fg: String, panel: String, side: String, accent: String) -> ThemeSeed {
		make(bg: bg, fg: fg, panel: panel, side: side, input: "#000000", status: accent, statusFg: "#FFFFFF", accent: accent)
	}

	private static func light(bg: String, fg: String, panel: String, side: String, accent: String) -> ThemeSeed {
		make(bg: bg, fg: fg, panel: panel, side: side, input: "#FFFFFF", status: accent, statusFg: "#FFFFFF", accent: accent)
	}

	private static func make(bg: String, fg: String, panel: String, side: String, input: String, status: String, statusFg: String, accent: String) -> ThemeSeed {
		ThemeSeed(
			editorBackground: NSColor.itsyHex(bg),
			foreground: NSColor.itsyHex(fg),
			panelBackground: NSColor.itsyHex(panel),
			sidebarBackground: NSColor.itsyHex(side),
			inputBackground: NSColor.itsyHex(input),
			statusBackground: NSColor.itsyHex(status),
			statusForeground: NSColor.itsyHex(statusFg),
			accent: NSColor.itsyHex(accent),
			border: NSColor.itsyHex(fg).withAlphaComponent(0.18),
			error: NSColor.itsyHex("#F85149"),
			warning: NSColor.itsyHex("#D29922"),
			success: NSColor.itsyHex("#3FB950"),
			buttonForeground: NSColor.itsyHex(statusFg),
			findMatch: NSColor.itsyHex("#D29922").withAlphaComponent(0.34),
			ansi: ThemeSeed.defaultANSI
		)
	}

	private static let defaultANSI: [Int: NSColor] = [
		0: NSColor.itsyHex("#000000"),
		1: NSColor.itsyHex("#CD3131"),
		2: NSColor.itsyHex("#0DBC79"),
		3: NSColor.itsyHex("#E5E510"),
		4: NSColor.itsyHex("#2472C8"),
		5: NSColor.itsyHex("#BC3FBC"),
		6: NSColor.itsyHex("#11A8CD"),
		7: NSColor.itsyHex("#E5E5E5"),
		8: NSColor.itsyHex("#666666"),
		9: NSColor.itsyHex("#F14C4C"),
		10: NSColor.itsyHex("#23D18B"),
		11: NSColor.itsyHex("#F5F543"),
		12: NSColor.itsyHex("#3B8EEA"),
		13: NSColor.itsyHex("#D670D6"),
		14: NSColor.itsyHex("#29B8DB"),
		15: NSColor.itsyHex("#E5E5E5"),
	]
}

private extension NSColor {
	static func itsyHex(_ hex: String) -> NSColor {
		guard let color = try? SyntaxColor(hex: hex) else {
			return .labelColor
		}
		return NSColor(
			srgbRed: CGFloat(color.red),
			green: CGFloat(color.green),
			blue: CGFloat(color.blue),
			alpha: CGFloat(color.alpha)
		)
	}

	var itsyLuminance: CGFloat {
		let converted = usingColorSpace(.sRGB) ?? self
		return 0.2126 * converted.redComponent + 0.7152 * converted.greenComponent + 0.0722 * converted.blueComponent
	}

	func isClose(to other: NSColor) -> Bool {
		guard
			let a = usingColorSpace(.sRGB),
			let b = other.usingColorSpace(.sRGB)
		else {
			return false
		}
		let delta = abs(a.redComponent - b.redComponent)
			+ abs(a.greenComponent - b.greenComponent)
			+ abs(a.blueComponent - b.blueComponent)
		return delta < 0.05
	}
}
