import ItsyConfig
@testable import ItsyApp
import Testing

@MainActor @Test func findBarAppliesPersistedDefaultOptionsToControls() {
	let controller = FindBarController()
	let settings = ItsySettings.FindSettings(usesRegex: true, isCaseSensitive: true, matchesWholeWord: true)
	controller.applyDefaultOptions(settings)
	#expect(controller.selectedOptions == settings)
}
