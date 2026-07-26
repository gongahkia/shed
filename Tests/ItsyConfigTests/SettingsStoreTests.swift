import Foundation
@testable import ItsyConfig
import Testing

private func encodeRawJSONSettings(_ settings: ItsySettings) throws -> Data {
	let encoder = JSONEncoder()
	encoder.keyEncodingStrategy = .convertToSnakeCase
	return try encoder.encode(ItsySettingsJSONDocument(settings: settings))
}

@Test func settingsJSONCodecRoundTripsNormalizedSettings() throws {
	var settings = ItsySettings.default
	settings.editor.font = "JetBrains Mono"
	settings.editor.language["swift"] = .init(tabWidth: 2, useSpaces: true)
	settings.ui.surfaces["terminal"] = .init(width: 860, height: 320)
	settings.lsp.modes["swift"] = .system

	let data = try ItsySettingsJSONCodec.encode(settings)
	let document = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
	#expect(document["schema_version"] as? Int == ItsySettingsSchema.currentVersion)
	#expect(try ItsySettingsJSONCodec.decode(data) == settings.normalized())
}

@Test func settingsJSONCodecRejectsUnknownSchemaVersion() throws {
	let encoded = try ItsySettingsJSONCodec.encode(.default)
	let document = try #require(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
	let settings = try #require(document["settings"])
	let data = try JSONSerialization.data(withJSONObject: [
		"schema_version": ItsySettingsSchema.currentVersion + 1,
		"settings": settings,
	])
	#expect(throws: ItsySettingsJSONCodecError.unsupportedSchemaVersion(ItsySettingsSchema.currentVersion + 1)) {
		try ItsySettingsJSONCodec.decode(data)
	}
}

@Test func settingsJSONCodecRejectsValuesThatRequireNormalization() throws {
	var settings = ItsySettings.default.normalized()
	settings.editor.fontSize = 99
	let data = try encodeRawJSONSettings(settings)
	#expect(throws: ItsySettingsJSONCodecError.invalidSettings) {
		try ItsySettingsJSONCodec.decode(data)
	}
}

@Test func settingsParserReadsKnownSectionsAndComments() {
	let contents = #"""
	# user-editable config
	[editor]
	font = "Monaco" # installed on macOS
	font_size = 16.5
	font_rendering = "subpixel"
	line_numbers = true
	line_number_mode = "relative"
	tab_width = 2
	auto_pairs = false
	smart_indent = false
	multiple_selections = false
	keymap = "vim"
	cursor_style = "block"
	tab_groups = "pane"
	wrap = "soft"
	wrap_column = 88

	[editor.experimental]
	storage = "piecetree"

	[theme]
	id = "bundled:default-dark"
	git.gutter.added = "#123456"
	git.gutter.modified = "#abcdef"
	git.gutter.removed = "#fedcba"

	[syntax]
	preload_grammars = "all"

	[terminal]
	font = "Monaco"
	font_size = 13
	scrollback_lines = 20000
	presentation = "window"

	[git]
	presentation = "window"

	[debugger]
	presentation = "window"

	[find]
	uses_regex = true
	case_sensitive = true
	whole_word = true

	[recovery]
	journal_enabled = false

	[updates]
	automatically_check = true

	[workbench]
	profile = "review"
	file_tree = "hidden"
	terminal = "visible"
	git = "visible"

	[layout]
	sidebar_visible = false
	sidebar_position = "trailing"
	sidebar_width = 320
	tab_bar_visible = false
	status_bar_visible = false
	interface_scale = 1.4
	"""#
	var parser = ItsySettingsParser()
	let result = parser.parse(contents)
	#expect(result.warnings.isEmpty)
	#expect(result.settings.editor.font == "Monaco")
	#expect(result.settings.editor.fontSize == 16.5)
	#expect(result.settings.editor.fontRendering == .subpixel)
	#expect(result.settings.editor.lineNumbers)
	#expect(result.settings.editor.lineNumberMode == .relative)
	#expect(result.settings.editor.tabWidth == 2)
	#expect(!result.settings.editor.autoPairs)
	#expect(!result.settings.editor.smartIndent)
	#expect(!result.settings.editor.multipleSelections)
	#expect(result.settings.editor.keymap == .vim)
	#expect(result.settings.editor.cursorStyle == .block)
	#expect(result.settings.editor.tabGroups == .pane)
	#expect(result.settings.editor.wrap == .soft)
	#expect(result.settings.editor.wrapColumn == 88)
	#expect(!result.settings.editor.useSpaces)
	#expect(result.settings.editor.experimental.storage == .pieceTree)
	#expect(result.settings.theme.id == "bundled:default-dark")
	#expect(result.settings.theme.gitGutter.added == "#123456")
	#expect(result.settings.theme.gitGutter.modified == "#abcdef")
	#expect(result.settings.theme.gitGutter.removed == "#fedcba")
	#expect(result.settings.syntax.preloadGrammars == .all)
	#expect(result.settings.terminal.font == "Monaco")
	#expect(result.settings.terminal.fontSize == 13)
	#expect(result.settings.terminal.scrollbackLines == 20000)
	#expect(result.settings.terminal.presentation == .window)
	#expect(result.settings.git.presentation == .window)
	#expect(result.settings.debugger.presentation == .window)
	#expect(result.settings.find == .init(usesRegex: true, isCaseSensitive: true, matchesWholeWord: true))
	#expect(!result.settings.recovery.journalEnabled)
	#expect(result.settings.updates.automaticallyCheck)
	#expect(result.settings.workbench.profile.rawValue == "review")
	#expect(result.settings.workbench.fileTree.rawValue == "hidden")
	#expect(result.settings.workbench.terminal.rawValue == "visible")
	#expect(result.settings.workbench.git.rawValue == "visible")
	#expect(result.settings.layout == .init(
		sidebarVisible: false,
		sidebarPosition: .trailing,
		sidebarWidth: 320,
		tabBarVisible: false,
		statusBarVisible: false,
		interfaceScale: 1.4
	))
}

@Test func settingsParserReadsAndSerializesGlobalLSPModes() {
	var parser = ItsySettingsParser()
	let parsed = parser.parse("""
	[lsp]
	catalog_automatically_check = true

	[lsp.python]
	mode = "managed"

	[lsp.typescript]
	mode = "disabled"
	""")
	#expect(parsed.warnings.isEmpty)
	#expect(parsed.settings.lsp.catalogAutomaticallyCheck)
	#expect(parsed.settings.lsp.mode(for: "python") == .managed)
	#expect(parsed.settings.lsp.mode(for: "typescript") == .disabled)
	let serialized = ItsySettingsStore.serialize(parsed.settings)
	let roundTrip = parser.parse(serialized)
	#expect(roundTrip.settings.lsp == parsed.settings.lsp)
}

@Test func settingsParserWarnsAndKeepsFallbackForBadValues() {
	let fallback = ItsySettings(
		editor: .init(font: "Menlo", fontSize: 15, lineNumbers: false, tabWidth: 4),
		theme: .init(id: "bundled:default-light"),
		terminal: .init(fontSize: 12, scrollbackLines: 10000)
	)
	let contents = #"""
	[editor]
	font_size = "large"
	line_numbers = maybe
	nope = true

	[extra]
	value = 1
	"""#
	var parser = ItsySettingsParser(settings: fallback)
	let result = parser.parse(contents)
	#expect(result.settings.editor.fontSize == 15)
	#expect(result.settings.editor.lineNumbers == false)
	#expect(result.warnings.count == 5)
	#expect(result.warnings.map(\.description).contains("line 2: editor.font_size expects number"))
	#expect(result.warnings.map(\.description).contains("line 4: unknown setting editor.nope"))
}

@Test func settingsSchemaVersionDiagnosticsRetainFallbackAndProvenance() {
	let fallback = ItsySettings(editor: .init(fontSize: 15, tabWidth: 4))
	var parser = ItsySettingsParser(settings: fallback, source: "/workspace/.itsy/settings.toml")
	let result = parser.parse("""
	schema_version = 13
	[editor]
	font_size = 80
	unknown_toggle = true
	""")
	#expect(ItsySettingsSchema.currentVersion == 12)
	#expect(ItsySettingsSchema.compatibilityPolicy == .warnAndIgnoreUnknownFields)
	#expect(result.settings.editor.fontSize == 15)
	let fontWarning = result.warnings.first { $0.key == "editor.font_size" }
	#expect(fontWarning?.source == "/workspace/.itsy/settings.toml")
	#expect(fontWarning?.expected == "number between 9.0 and 36.0")
	#expect(fontWarning?.retainedFallback == true)
	let versionWarning = result.warnings.first { $0.key == "schema_version" }
	#expect(versionWarning?.retainedFallback == true)
	#expect(result.warnings.contains { $0.key == "editor.unknown_toggle" && $0.retainedFallback })
}

@Test func settingsParserReadsGlobalUIAndSurfaceOverrides() {
	var parser = ItsySettingsParser()
	let result = parser.parse("""
	[ui]
	font_scale = 1.2
	density = "compact"
	corner_radius = 4
	border_width = 2
	padding = 10
	notification_position = "top_right"

	[ui.surface.command_palette]
	width = 680
	height = 340
	row_height = 28
	input_font_size = 20
	item_font_size = 14
	""")
	#expect(result.warnings.isEmpty)
	#expect(result.settings.ui.fontScale == 1.2)
	#expect(result.settings.ui.density == .compact)
	#expect(result.settings.ui.notificationPosition == .topRight)
	#expect(result.settings.ui.surface("command_palette") == .init(
		width: 680,
		height: 340,
		rowHeight: 28,
		inputFontSize: 20,
		itemFontSize: 14
	))
}

@Test func settingsParserDisablesInvalidWorkbenchOverride() {
	var parser = ItsySettingsParser()
	let result = parser.parse("""
	[workbench]
	profile = "focus"
	file_tree = "visible"
	""")
	#expect(result.settings.workbench.profile.rawValue == "focus")
	#expect(result.settings.workbench.fileTree.rawValue == "automatic")
	#expect(result.warnings.contains { $0.key == "workbench" })
}

@Test func settingsParserReadsPerLanguageEditorOverrides() {
	let contents = #"""
	[editor]
	font_size = 15
	font_rendering = "grayscale"
	line_numbers = false
	tab_width = 8
	use_spaces = false
	auto_pairs = true
	smart_indent = true
	multiple_selections = true

	[editor.language.python]
	tab_width = 4
	use_spaces = true
	auto_pairs = false
	smart_indent = false
	multiple_selections = false
	font_rendering = "subpixel"
	line_numbers = true
	"""#
	var parser = ItsySettingsParser()
	let result = parser.parse(contents)

	#expect(result.warnings.isEmpty)
	#expect(result.settings.editor.tabWidth == 8)
	#expect(!result.settings.editor.useSpaces)
	let python = result.settings.editorSettings(languageID: "python")
	#expect(python.fontSize == 15)
	#expect(python.fontRendering == .subpixel)
	#expect(python.tabWidth == 4)
	#expect(python.useSpaces)
	#expect(python.lineNumbers)
	#expect(!python.autoPairs)
	#expect(!python.smartIndent)
	#expect(!python.multipleSelections)
}

@Test func settingsDefaultsUsePieceTreeStorage() {
	let settings = ItsySettings()
	#expect(settings.editor.experimental.storage == .pieceTree)
	#expect(settings.syntax.preloadGrammars == .opened)
	#expect(ItsySettingsStore.serialize(settings).contains(#"storage = "piecetree""#))
	#expect(ItsySettingsStore.serialize(settings).contains("schema_version = 12"))
	#expect(ItsySettingsStore.serialize(settings).contains("[debugger]\npresentation = \"sidebar\""))
	#expect(!ItsySettingsStore.serialize(settings).contains("[terminal]\nfont ="))
	#expect(ItsySettingsStore.serialize(settings).contains(#"preload_grammars = "opened""#))
	#expect(ItsySettingsStore.serialize(settings).contains("use_spaces = false"))
	#expect(ItsySettingsStore.serialize(settings).contains("auto_pairs = true"))
	#expect(ItsySettingsStore.serialize(settings).contains("smart_indent = true"))
	#expect(ItsySettingsStore.serialize(settings).contains("multiple_selections = true"))
	#expect(ItsySettingsStore.serialize(settings).contains(#"line_number_mode = "off""#))
	#expect(ItsySettingsStore.serialize(settings).contains(#"keymap = "plain""#))
	#expect(ItsySettingsStore.serialize(settings).contains(#"tab_groups = "window""#))
	#expect(ItsySettingsStore.serialize(settings).contains(#"wrap = "none""#))
	#expect(ItsySettingsStore.serialize(settings).contains("wrap_column = 100"))
	#expect(ItsySettingsStore.serialize(settings).contains("[find]"))
	#expect(ItsySettingsStore.serialize(settings).contains("journal_enabled = true"))
	#expect(ItsySettingsStore.serialize(settings).contains("[updates]\nautomatically_check = false"))
	#expect(ItsySettingsStore.serialize(settings).contains("[workbench]\nprofile = \"workbench\""))
	#expect(ItsySettingsStore.serialize(settings).contains("font_rendering = \"grayscale\""))
	#expect(ItsySettingsStore.serialize(settings).contains("interface_scale = 1"))
	#expect(ItsySettingsStore.serialize(settings).contains(#"presentation = "bottom""#))
	#expect(ItsySettingsStore.serialize(settings).contains("[git]\npresentation = \"sidebar\""))
	#expect(ItsySettingsStore.serialize(settings).contains(##"git.gutter.added = "#47C775""##))
}

@Test func settingsStoreSavesAndReloadsToml() throws {
	let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
		"itsy-settings-\(UUID().uuidString)",
		isDirectory: true
	)
	defer {
		try? FileManager.default.removeItem(at: directory)
	}
	let url = directory.appendingPathComponent("settings.toml")
	let store = ItsySettingsStore(fileURL: url)
	let settings = ItsySettings(
		editor: .init(
			font: "Monaco",
			fontSize: 18,
			lineNumbers: true,
			lineNumberMode: .relative,
			tabWidth: 8,
			tabGroups: .pane,
			keymap: .emacs,
			wrap: .hard,
			wrapColumn: 72,
			experimental: .init(storage: .pieceTree)
		),
		theme: .init(id: "user:night.toml", gitGutter: .init(added: "#101010", modified: "#202020", removed: "#303030")),
		syntax: .init(preloadGrammars: .none),
		terminal: .init(fontSize: 14, scrollbackLines: 1234, font: "Monaco")
	)
	try store.save(settings)
	let loaded = store.load()
	#expect(loaded.loadedFromFile)
	#expect(loaded.warnings.isEmpty)
	#expect(loaded.settings == settings)
}

@Test func settingsStoreUsesJSONForGlobalPath() {
	let globalURL = ItsySettingsStore.globalFileURL()
	#expect(globalURL.lastPathComponent == "settings.json")
	#expect(globalURL.deletingLastPathComponent().lastPathComponent == "itsy")
	#expect(ItsySettingsStore.defaultFileURL() == globalURL)
}

@Test func settingsStoreUsesJSONForWorkspacePath() {
	let workspaceRoot = URL(fileURLWithPath: "/tmp/itsy-workspace", isDirectory: true)
	let workspaceURL = ItsySettingsStore.workspaceFileURL(workspaceRoot: workspaceRoot)
	#expect(workspaceURL.lastPathComponent == "settings.json")
	#expect(workspaceURL.deletingLastPathComponent().lastPathComponent == ".itsy")
}

@Test func settingsStoreSavesAndReloadsJSON() throws {
	let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
		"itsy-settings-json-\(UUID().uuidString)",
		isDirectory: true
	)
	defer { try? FileManager.default.removeItem(at: directory) }
	let url = directory.appendingPathComponent("settings.json")
	let settings = ItsySettings(
		editor: .init(font: "Monaco", fontSize: 18, tabWidth: 2),
		theme: .init(id: "bundled:default-dark"),
		terminal: .init(fontSize: 14, scrollbackLines: 1234)
	)
	let store = ItsySettingsStore(fileURL: url)
	try store.save(settings)
	let document = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
	#expect(document["schema_version"] as? Int == ItsySettingsSchema.currentVersion)
	let loaded = store.load()
	#expect(loaded.loadedFromFile)
	#expect(loaded.warnings.isEmpty)
	#expect(loaded.settings == settings)
}

@Test func settingsStoreFallsBackForMalformedJSON() throws {
	let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
		"itsy-settings-json-invalid-\(UUID().uuidString)",
		isDirectory: true
	)
	defer { try? FileManager.default.removeItem(at: directory) }
	try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
	let url = directory.appendingPathComponent("settings.json")
	try "{".write(to: url, atomically: true, encoding: .utf8)
	let fallback = ItsySettings(editor: .init(font: "Monaco"))
	let loaded = ItsySettingsStore(fileURL: url).load(fallback: fallback)
	#expect(loaded.loadedFromFile)
	#expect(loaded.settings == fallback)
	#expect(loaded.warnings.first?.source == url.path)
	#expect(loaded.warnings.first?.expected == "valid JSON settings document")
	#expect(loaded.warnings.first?.retainedFallback == true)
	#expect(loaded.warnings.first?.message.hasPrefix("invalid JSON settings document") == true)
}

@Test func settingsStoreReportsInvalidJSONValuesWithFallback() throws {
	let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
		"itsy-settings-json-range-\(UUID().uuidString)",
		isDirectory: true
	)
	defer { try? FileManager.default.removeItem(at: directory) }
	try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
	let url = directory.appendingPathComponent("settings.json")
	var invalidSettings = ItsySettings.default.normalized()
	invalidSettings.editor.fontSize = 99
	try encodeRawJSONSettings(invalidSettings).write(to: url)
	let fallback = ItsySettings(editor: .init(font: "Monaco", fontSize: 15))
	let loaded = ItsySettingsStore(fileURL: url).load(fallback: fallback)
	#expect(loaded.loadedFromFile)
	#expect(loaded.settings == fallback)
	#expect(loaded.warnings.first?.key == "settings")
	#expect(loaded.warnings.first?.source == url.path)
	#expect(loaded.warnings.first?.expected == "values within supported ranges")
	#expect(loaded.warnings.first?.retainedFallback == true)
}

@Test func settingsStoreReportsUnsupportedJSONSchemaWithFallback() throws {
	let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
		"itsy-settings-json-schema-\(UUID().uuidString)",
		isDirectory: true
	)
	defer { try? FileManager.default.removeItem(at: directory) }
	try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
	let url = directory.appendingPathComponent("settings.json")
	let encoder = JSONEncoder()
	encoder.keyEncodingStrategy = .convertToSnakeCase
	let data = try encoder.encode(ItsySettingsJSONDocument(
		schemaVersion: ItsySettingsSchema.currentVersion + 1,
		settings: .default.normalized()
	))
	try data.write(to: url)
	let fallback = ItsySettings(editor: .init(font: "Monaco"))
	let loaded = ItsySettingsStore(fileURL: url).load(fallback: fallback)
	#expect(loaded.settings == fallback)
	#expect(loaded.warnings.first?.key == "schema_version")
	#expect(loaded.warnings.first?.source == url.path)
	#expect(loaded.warnings.first?.expected == "version == \(ItsySettingsSchema.currentVersion)")
	#expect(loaded.warnings.first?.retainedFallback == true)
}

@Test func terminalFontInheritsEditorFontUnlessOverridden() {
	let terminal = ItsySettings.TerminalSettings()
	#expect(terminal.resolvedFontName(inheriting: "Monaco") == "Monaco")
	#expect(ItsySettings.TerminalSettings(font: "Menlo").resolvedFontName(inheriting: "Monaco") == "Menlo")
}

@Test func settingsStoreUsesFallbackWhenFileIsMissing() {
	let url = FileManager.default.temporaryDirectory
		.appendingPathComponent("missing-\(UUID().uuidString)")
		.appendingPathComponent("settings.toml")
	let fallback = ItsySettings(editor: .init(font: "Monaco", fontSize: 17, lineNumbers: true, tabWidth: 3))
	let loaded = ItsySettingsStore(fileURL: url).load(fallback: fallback)
	#expect(!loaded.loadedFromFile)
	#expect(loaded.settings == fallback)
}

@Test func settingsStoreLoadsWorkspaceJSONAndPerLanguageOverrides() throws {
	let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
		"itsy-settings-\(UUID().uuidString)",
		isDirectory: true
	)
	defer {
		try? FileManager.default.removeItem(at: directory)
	}
	let globalURL = directory.appendingPathComponent("settings.toml")
	let workspaceRoot = directory.appendingPathComponent("workspace", isDirectory: true)
	let workspaceURL = ItsySettingsStore.workspaceFileURL(workspaceRoot: workspaceRoot)
	try FileManager.default.createDirectory(
		at: workspaceURL.deletingLastPathComponent(),
		withIntermediateDirectories: true
	)
	try """
	[editor]
	tab_width = 2
	use_spaces = false

	[editor.language.python]
	tab_width = 3
	""".write(to: globalURL, atomically: true, encoding: .utf8)
	var workspaceSettings = ItsySettings.default
	workspaceSettings.editor.fontSize = 18
	workspaceSettings.editor.tabWidth = 6
	workspaceSettings.editor.useSpaces = false
	workspaceSettings.editor.language["python"] = .init(tabWidth: 3, useSpaces: true)
	try ItsySettingsStore(fileURL: workspaceURL).save(workspaceSettings)

	let loaded = ItsySettingsStore(fileURL: globalURL).load(workspaceRoot: workspaceRoot)
	let base = loaded.settings.editorSettings(languageID: nil)
	let python = loaded.settings.editorSettings(languageID: "python")

	#expect(loaded.loadedFromFile)
	#expect(base.fontSize == 18)
	#expect(base.tabWidth == 6)
	#expect(!base.useSpaces)
	#expect(python.fontSize == 18)
	#expect(python.tabWidth == 3)
	#expect(python.useSpaces)
}

@Test func workspaceUIOverridesAreIgnored() throws {
	let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
		"itsy-ui-scope-\(UUID().uuidString)",
		isDirectory: true
	)
	defer { try? FileManager.default.removeItem(at: directory) }
	let globalURL = directory.appendingPathComponent("settings.toml")
	let workspaceRoot = directory.appendingPathComponent("workspace", isDirectory: true)
	let workspaceURL = ItsySettingsStore.workspaceFileURL(workspaceRoot: workspaceRoot)
	try FileManager.default.createDirectory(
		at: workspaceURL.deletingLastPathComponent(),
		withIntermediateDirectories: true
	)
	try "[ui]\nfont_scale = 1.25\n\n[updates]\nautomatically_check = true\n".write(to: globalURL, atomically: true, encoding: .utf8)
	var workspaceSettings = ItsySettings.default
	workspaceSettings.ui.fontScale = 1.75
	try ItsySettingsStore(fileURL: workspaceURL).save(workspaceSettings)
	let result = ItsySettingsStore(fileURL: globalURL).load(workspaceRoot: workspaceRoot)
	#expect(result.settings.ui.fontScale == 1.25)
	#expect(result.settings.updates.automaticallyCheck)
	#expect(result.warnings.contains { $0.key == "ui.font_scale" && $0.message.contains("user-only") })
	#expect(result.warnings.contains { $0.key == "updates.automatically_check" && $0.message.contains("user-only") })
}

@Test func settingsResolverTracksGlobalWorkspaceLanguageAndSessionPrecedence() throws {
	let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
		"itsy-settings-resolution-\(UUID().uuidString)",
		isDirectory: true
	)
	defer { try? FileManager.default.removeItem(at: directory) }
	let globalURL = directory.appendingPathComponent("settings.toml")
	let workspaceRoot = directory.appendingPathComponent("workspace", isDirectory: true)
	let workspaceURL = ItsySettingsStore.workspaceFileURL(workspaceRoot: workspaceRoot)
	try FileManager.default.createDirectory(
		at: workspaceURL.deletingLastPathComponent(),
		withIntermediateDirectories: true
	)
	try """
	[editor]
	tab_width = 2
	wrap = "soft"
	multiple_selections = false
	keymap = "vim"

	[editor.language.python]
	tab_width = 3

	[updates]
	automatically_check = true
	""".write(to: globalURL, atomically: true, encoding: .utf8)
	var workspaceSettings = ItsySettings.default
	workspaceSettings.editor.tabWidth = 4
	workspaceSettings.editor.wrap = .soft
	workspaceSettings.editor.multipleSelections = true
	workspaceSettings.editor.keymap = .vim
	workspaceSettings.editor.language["python"] = .init(tabWidth: 3, useSpaces: true)
	workspaceSettings.find = .init(usesRegex: true, matchesWholeWord: true)
	workspaceSettings.recovery = .init(journalEnabled: false)
	workspaceSettings.layout = .init(sidebarPosition: .trailing, interfaceScale: 1.25)
	try ItsySettingsStore(fileURL: workspaceURL).save(workspaceSettings)
	let session = ItsySettingsSessionLayer(
		settings: ItsySettings(editor: .init(
			wrap: .hard,
			language: ["python": .init(tabWidth: 7, multipleSelections: false)]
		)),
		assignedKeys: ["editor.wrap", "editor.language.python.tab_width", "editor.language.python.multiple_selections"]
	)
	let store = ItsySettingsStore(fileURL: globalURL)
	let resolved = store.resolve(workspaceRoot: workspaceRoot, session: session)
	#expect(resolved.settings.editor.tabWidth == 4)
	#expect(resolved.settings.editor.wrap == .hard)
	let python = resolved.settings.editorSettings(languageID: "python")
	#expect(python.tabWidth == 7)
	#expect(python.useSpaces)
	#expect(!python.multipleSelections)
	#expect(resolved.settings.editor.keymap == .vim)
	#expect(resolved.settings.editor.multipleSelections)
	#expect(resolved.settings.find == .init(usesRegex: true, isCaseSensitive: false, matchesWholeWord: true))
	#expect(!resolved.settings.recovery.journalEnabled)
	#expect(resolved.settings.updates.automaticallyCheck)
	#expect(resolved.settings.layout.sidebarPosition == .trailing)
	#expect(resolved.settings.layout.interfaceScale == 1.25)
	#expect(resolved.source(for: "editor.tab_width") == .workspace)
	#expect(resolved.source(for: "editor.wrap") == .session)
	#expect(resolved.source(for: "editor.tab_width", languageID: "python") == .language)
	#expect(resolved.sources["editor.language.python.tab_width"] == .session)
	#expect(resolved.sources["editor.language.python.use_spaces"] == .workspace)
	#expect(resolved.source(for: "updates.automatically_check") == .global)
	#expect(resolved.source(for: "editor.multiple_selections", languageID: "python") == .language)

	workspaceSettings.editor.tabWidth = 6
	try ItsySettingsStore(fileURL: workspaceURL).save(workspaceSettings)
	let reloaded = store.resolve(workspaceRoot: workspaceRoot, session: session)
	#expect(reloaded.settings.editor.tabWidth == 6)
	#expect(reloaded.source(for: "editor.tab_width") == .workspace)
}

@Test func settingsWatcherPublishesFileChangesForHotReload() throws {
	let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
		"itsy-settings-watch-\(UUID().uuidString)",
		isDirectory: true
	)
	defer {
		try? FileManager.default.removeItem(at: directory)
	}
	try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
	let url = directory.appendingPathComponent("settings.toml")
	let semaphore = DispatchSemaphore(value: 0)
	let watcher = ItsySettingsWatcher(
		urls: [url],
		queue: DispatchQueue(label: "dev.itsy.settings-watch-test"),
		debounce: 0.02
	) {
		semaphore.signal()
	}
	#expect(watcher.start())
	try "[editor]\ntab_width = 7\n".write(to: url, atomically: true, encoding: .utf8)
	#expect(semaphore.wait(timeout: .now() + .seconds(3)) == .success)
	watcher.stop()
}
