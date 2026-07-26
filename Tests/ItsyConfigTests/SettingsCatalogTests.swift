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
	#expect(ItsySettingsCatalog.entries
		.contains { $0.key == "editor.language.<language>.multiple_selections" && $0.isLanguageTemplate })
	#expect(ItsySettingsCatalog.entries.contains { $0.key == "schema_version" && !$0.isResettable })
	#expect(ItsySettingsCatalog.entries.contains { $0.key == "ui.surface.command_palette.height" })
	#expect(ItsySettingsCatalog.entries.contains { $0.key == "ui.notification_position" })
	#expect(ItsySettingsCatalog.entries.contains { $0.key == "editor.cursor_style" })
	#expect(ItsySettingsCatalog.entries.contains { $0.key == "workbench.profile" })
}

@Test func settingsCatalogSearchEffectiveValueAndResetAreDeterministic() {
	var settings = ItsySettings()
	settings.editor.tabWidth = 8
	settings.layout.sidebarPosition = .trailing
	settings.layout.interfaceScale = 1.25
	settings.updates.automaticallyCheck = true
	settings.workbench.profile = .review
	#expect(ItsySettingsCatalog.matching("side bar") == [])
	#expect(ItsySettingsCatalog.matching("sidebar").map(\.key) == [
		"git.presentation",
		"layout.sidebar_visible",
		"layout.sidebar_position",
		"layout.sidebar_width",
	])
	#expect(ItsySettingsCatalog.effectiveValue(for: "editor.tab_width", in: settings) == "8")
	#expect(ItsySettingsCatalog.effectiveValue(for: "terminal.font", in: settings) == "Inherited: Menlo")
	#expect(ItsySettingsCatalog.effectiveValue(for: "layout.sidebar_position", in: settings) == "trailing")
	#expect(ItsySettingsCatalog.effectiveValue(for: "layout.interface_scale", in: settings) == "1.25")
	#expect(ItsySettingsCatalog.effectiveValue(for: "updates.automatically_check", in: settings) == "true")
	#expect(ItsySettingsCatalog.effectiveValue(for: "workbench.profile", in: settings) == "review")
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
	#expect(ItsySettingsCatalog.update(value: "block", for: "editor.cursor_style", in: &settings) == nil)
	#expect(ItsySettingsCatalog.update(value: "Monaco", for: "terminal.font", in: &settings) == nil)
	#expect(ItsySettingsCatalog.update(value: "true", for: "updates.automatically_check", in: &settings) == nil)
	#expect(ItsySettingsCatalog.update(value: "focus", for: "workbench.profile", in: &settings) == nil)
	#expect(ItsySettingsCatalog.update(value: "#12AB34", for: "theme.git.gutter.added", in: &settings) == nil)
	#expect(ItsySettingsCatalog.update(value: "17", for: "editor.tab_width", in: &settings) != nil)
	#expect(ItsySettingsCatalog.update(value: "value", for: "editor.language.<language>.font", in: &settings) != nil)
	#expect(ItsySettingsCatalog.update(value: "340", for: "ui.surface.command_palette.height", in: &settings) == nil)
	#expect(ItsySettingsCatalog.update(value: "top_right", for: "ui.notification_position", in: &settings) == nil)
	#expect(ItsySettingsCatalog.effectiveValue(for: "ui.surface.command_palette.height", in: settings) == "340.0")
	#expect(ItsySettingsCatalog.effectiveValue(for: "editor.cursor_style", in: settings) == "block")
	#expect(ItsySettingsCatalog.effectiveValue(for: "terminal.font", in: settings) == "Monaco")
	#expect(ItsySettingsCatalog.reset("editor.cursor_style", in: &settings))
	#expect(ItsySettingsCatalog.reset("terminal.font", in: &settings))
	#expect(ItsySettingsCatalog.reset("updates.automatically_check", in: &settings))
	#expect(ItsySettingsCatalog.reset("workbench.profile", in: &settings))
	#expect(ItsySettingsCatalog.effectiveValue(for: "ui.notification_position", in: settings) == "top_right")
	#expect(ItsySettingsCatalog.reset("ui.notification_position", in: &settings))
	#expect(ItsySettingsCatalog.reset("ui.surface.command_palette.height", in: &settings))
	#expect(settings.editor.tabWidth == 8)
	#expect(settings.editor.lineNumbers)
	#expect(settings.editor.lineNumberMode == .relative)
	#expect(settings.editor.cursorStyle == .automatic)
	#expect(settings.terminal.font == nil)
	#expect(!settings.updates.automaticallyCheck)
	#expect(settings.theme.gitGutter.added == "#12AB34")
	#expect(settings.ui.notificationPosition == .bottomRight)
}
