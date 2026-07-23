import Foundation
@testable import ItsyConfig
import Testing

@Test func settingsParserReadsKnownSectionsAndComments() {
	let contents = #"""
	# user-editable config
	[editor]
	font = "Monaco" # installed on macOS
	font_size = 16.5
	line_numbers = true
	line_number_mode = "relative"
	tab_width = 2
	auto_pairs = false
	smart_indent = false
	multiple_selections = false
	keymap = "vim"
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
	font_size = 13
	scrollback_lines = 20000

	[find]
	uses_regex = true
	case_sensitive = true
	whole_word = true

	[recovery]
	journal_enabled = false
	"""#
	var parser = ItsySettingsParser()
	let result = parser.parse(contents)
	#expect(result.warnings.isEmpty)
	#expect(result.settings.editor.font == "Monaco")
	#expect(result.settings.editor.fontSize == 16.5)
	#expect(result.settings.editor.lineNumbers)
	#expect(result.settings.editor.lineNumberMode == .relative)
	#expect(result.settings.editor.tabWidth == 2)
	#expect(!result.settings.editor.autoPairs)
	#expect(!result.settings.editor.smartIndent)
	#expect(!result.settings.editor.multipleSelections)
	#expect(result.settings.editor.keymap == .vim)
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
	#expect(result.settings.terminal.fontSize == 13)
	#expect(result.settings.terminal.scrollbackLines == 20000)
	#expect(result.settings.find == .init(usesRegex: true, isCaseSensitive: true, matchesWholeWord: true))
	#expect(!result.settings.recovery.journalEnabled)
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
	schema_version = 9
	[editor]
	font_size = 80
	unknown_toggle = true
	""")
	#expect(ItsySettingsSchema.currentVersion == 2)
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

@Test func settingsParserReadsPerLanguageEditorOverrides() {
	let contents = #"""
	[editor]
	font_size = 15
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
	line_numbers = true
	"""#
	var parser = ItsySettingsParser()
	let result = parser.parse(contents)

	#expect(result.warnings.isEmpty)
	#expect(result.settings.editor.tabWidth == 8)
	#expect(!result.settings.editor.useSpaces)
	let python = result.settings.editorSettings(languageID: "python")
	#expect(python.fontSize == 15)
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
	#expect(ItsySettingsStore.serialize(settings).contains("schema_version = 2"))
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
		editor: .init(font: "Monaco", fontSize: 18, lineNumbers: true, lineNumberMode: .relative, tabWidth: 8, tabGroups: .pane, keymap: .emacs, wrap: .hard, wrapColumn: 72, experimental: .init(storage: .pieceTree)),
		theme: .init(id: "user:night.toml", gitGutter: .init(added: "#101010", modified: "#202020", removed: "#303030")),
		syntax: .init(preloadGrammars: .none),
		terminal: .init(fontSize: 14, scrollbackLines: 1234)
	)
	try store.save(settings)
	let loaded = store.load()
	#expect(loaded.loadedFromFile)
	#expect(loaded.warnings.isEmpty)
	#expect(loaded.settings == settings)
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

@Test func settingsStoreMergesGlobalWorkspaceAndPerLanguageOverrides() throws {
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
	try """
	[editor]
	font_size = 18
	tab_width = 6

	[editor.language.python]
	use_spaces = true
	""".write(to: workspaceURL, atomically: true, encoding: .utf8)

	let loaded = ItsySettingsStore(fileURL: globalURL).load(workspaceRoot: workspaceRoot)
	let base = loaded.settings.editorSettings(languageID: nil)
	let python = loaded.settings.editorSettings(languageID: "python")

	#expect(loaded.loadedFromFile)
	#expect(loaded.warnings.isEmpty)
	#expect(base.fontSize == 18)
	#expect(base.tabWidth == 6)
	#expect(!base.useSpaces)
	#expect(python.fontSize == 18)
	#expect(python.tabWidth == 3)
	#expect(python.useSpaces)
}

@Test func settingsResolverTracksGlobalWorkspaceLanguageAndSessionPrecedence() throws {
	let directory = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-settings-resolution-\(UUID().uuidString)", isDirectory: true)
	defer { try? FileManager.default.removeItem(at: directory) }
	let globalURL = directory.appendingPathComponent("settings.toml")
	let workspaceRoot = directory.appendingPathComponent("workspace", isDirectory: true)
	let workspaceURL = ItsySettingsStore.workspaceFileURL(workspaceRoot: workspaceRoot)
	try FileManager.default.createDirectory(at: workspaceURL.deletingLastPathComponent(), withIntermediateDirectories: true)
	try """
	[editor]
	tab_width = 2
	wrap = "soft"
	multiple_selections = false
	keymap = "vim"

	[editor.language.python]
	tab_width = 3
	""".write(to: globalURL, atomically: true, encoding: .utf8)
	try """
	[editor]
	tab_width = 4
	multiple_selections = true

	[find]
	uses_regex = true
	whole_word = true

	[recovery]
	journal_enabled = false

	[editor.language.python]
	use_spaces = true
	""".write(to: workspaceURL, atomically: true, encoding: .utf8)
	let session = ItsySettingsSessionLayer(
		settings: ItsySettings(editor: .init(wrap: .hard, language: ["python": .init(tabWidth: 7, multipleSelections: false)])),
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
	#expect(resolved.source(for: "editor.tab_width") == .workspace)
	#expect(resolved.source(for: "editor.wrap") == .session)
	#expect(resolved.source(for: "editor.tab_width", languageID: "python") == .language)
	#expect(resolved.sources["editor.language.python.tab_width"] == .session)
	#expect(resolved.sources["editor.language.python.use_spaces"] == .workspace)
	#expect(resolved.source(for: "editor.multiple_selections", languageID: "python") == .language)

	try "[editor]\ntab_width = 6\n".write(to: workspaceURL, atomically: true, encoding: .utf8)
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
