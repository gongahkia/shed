import AppKit
import ItsyConfig
@testable import ItsyApp
import Testing

@Test @MainActor func appThemePaletteDerivesWholeIDESurfacesFromThemeID() throws {
	let palette = AppThemePalette(settings: ItsySettings(theme: .init(id: "bundled:tokyo-night")))

	#expect(palette.id == "bundled:tokyo-night")
	#expect(palette.isDark)
	#expect(close(palette.panelBackground, to: NSColor(srgbRed: CGFloat(0x16) / 255, green: CGFloat(0x16) / 255, blue: CGFloat(0x1e) / 255, alpha: 1)))
	#expect(close(palette.sidebarBackground, to: palette.panelBackground))
	#expect(close(palette.terminal.background, to: NSColor(srgbRed: CGFloat(0x1a) / 255, green: CGFloat(0x1b) / 255, blue: CGFloat(0x26) / 255, alpha: 1)))
	#expect(palette.editor.background.x < 0.12)
	#expect(palette.editor.foreground.x > 0.70)
	#expect(palette.terminal.ansi[1] != nil)
	#expect(palette.gitGutterSettings.added.hasPrefix("#"))
}

private func close(_ lhs: NSColor, to rhs: NSColor) -> Bool {
	guard let a = lhs.usingColorSpace(.sRGB), let b = rhs.usingColorSpace(.sRGB) else {
		return false
	}
	return abs(a.redComponent - b.redComponent)
		+ abs(a.greenComponent - b.greenComponent)
		+ abs(a.blueComponent - b.blueComponent)
		+ abs(a.alphaComponent - b.alphaComponent) < 0.01
}
