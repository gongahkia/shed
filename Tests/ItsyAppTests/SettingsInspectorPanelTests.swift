@testable import ItsyApp
import AppKit
import ItsyConfig
import Testing

@MainActor
@Test func settingsInspectorSearchesCatalogKeysTitlesAndDescriptions() throws {
	let sidebar = try #require(ItsySettingsCatalog.entries.first { $0.key == "layout.sidebar_width" })
	let font = try #require(ItsySettingsCatalog.entries.first { $0.key == "editor.font" })
	let items = [
		SettingsInspectorPanel.Item(entry: sidebar, effectiveValue: "240", sourceLabel: "Global", sourceURL: nil),
		SettingsInspectorPanel.Item(entry: font, effectiveValue: "Menlo", sourceLabel: "Built-in Default", sourceURL: nil),
	]
	#expect(SettingsInspectorPanel.filtered(items: items, query: "sidebar") == [items[0]])
	#expect(SettingsInspectorPanel.filtered(items: items, query: "font family") == [items[1]])
	#expect(SettingsInspectorPanel.filtered(items: items, query: "") == items)
}

@MainActor
@Test func settingsInspectorExposesSearchAndResultsToAccessibility() {
	let panel = SettingsInspectorPanel(
		resetEntry: { _ in [] },
		updateEntry: { _, _ in .init(items: [], validationError: nil) }
	)
	let labels = settingsInspectorAccessibilityLabels(in: panel.makeContentViewForTesting())
	#expect(labels.contains("Settings catalog search"))
	#expect(labels.contains("Settings catalog results"))
	#expect(labels.contains("Selected setting value"))
}

private func settingsInspectorAccessibilityLabels(in view: NSView) -> [String] {
	let label = view.accessibilityLabel().map { [$0] } ?? []
	return label + view.subviews.flatMap(settingsInspectorAccessibilityLabels)
}
