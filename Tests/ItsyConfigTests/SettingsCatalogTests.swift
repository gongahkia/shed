import ItsyConfig
import Testing

@Test func settingsCatalogCoversEveryResolvedSchemaKeyAndLanguageTemplate() {
	let resolution = ItsySettingsResolver.resolve()
	for entry in ItsySettingsCatalog.baseEntries {
		#expect(resolution.source(for: entry.key) == .default)
		var settings = ItsySettings.default
		#expect(ItsySettingsCatalog.reset(entry.key, in: &settings))
	}
	#expect(ItsySettingsCatalog.entries.contains { $0.key == "editor.language.<language>.font" && $0.isLanguageTemplate })
	#expect(ItsySettingsCatalog.entries.contains { $0.key == "editor.language.<language>.multiple_selections" && $0.isLanguageTemplate })
	#expect(ItsySettingsCatalog.entries.contains { $0.key == "schema_version" && !$0.isResettable })
}

@Test func settingsCatalogSearchEffectiveValueAndResetAreDeterministic() {
	var settings = ItsySettings()
	settings.editor.tabWidth = 8
	settings.layout.sidebarPosition = .trailing
	settings.layout.interfaceScale = 1.25
	#expect(ItsySettingsCatalog.matching("side bar") == [])
	#expect(ItsySettingsCatalog.matching("sidebar").map(\.key) == ["layout.sidebar_visible", "layout.sidebar_position", "layout.sidebar_width"])
	#expect(ItsySettingsCatalog.effectiveValue(for: "editor.tab_width", in: settings) == "8")
	#expect(ItsySettingsCatalog.effectiveValue(for: "layout.sidebar_position", in: settings) == "trailing")
	#expect(ItsySettingsCatalog.effectiveValue(for: "layout.interface_scale", in: settings) == "1.25")
	#expect(ItsySettingsCatalog.reset("editor.tab_width", in: &settings))
	#expect(ItsySettingsCatalog.reset("layout.sidebar_position", in: &settings))
	#expect(!ItsySettingsCatalog.reset("editor.language.<language>.font", in: &settings))
	#expect(settings.editor.tabWidth == ItsySettings.EditorSettings.defaultTabWidth)
	#expect(settings.layout.sidebarPosition == .leading)
}

@Test func settingsCatalogUpdatesValuesUsingParserValidation() {
	var settings = ItsySettings()
	#expect(ItsySettingsCatalog.update(value: "8", for: "editor.tab_width", in: &settings) == nil)
	#expect(ItsySettingsCatalog.update(value: "false", for: "editor.line_numbers", in: &settings) == nil)
	#expect(ItsySettingsCatalog.update(value: "relative", for: "editor.line_number_mode", in: &settings) == nil)
	#expect(ItsySettingsCatalog.update(value: "#12AB34", for: "theme.git.gutter.added", in: &settings) == nil)
	#expect(ItsySettingsCatalog.update(value: "17", for: "editor.tab_width", in: &settings) != nil)
	#expect(ItsySettingsCatalog.update(value: "value", for: "editor.language.<language>.font", in: &settings) != nil)
	#expect(settings.editor.tabWidth == 8)
	#expect(settings.editor.lineNumbers)
	#expect(settings.editor.lineNumberMode == .relative)
	#expect(settings.theme.gitGutter.added == "#12AB34")
}
