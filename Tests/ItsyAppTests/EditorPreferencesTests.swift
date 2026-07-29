@testable import ItsyApp
import Testing

@Test func editorPreferencesFontCatalogReturnsStableChoices() {
	let choices = EditorPreferences.availableFontChoices()
	#expect(!choices.isEmpty)
	#expect(choices.map(\.name) == EditorPreferences.availableFontNames())
	#expect(Set(choices.map(\.name)).count == choices.count)
}
