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
	"""#
	var parser = ItsySettingsParser()
	let result = parser.parse(contents)
	#expect(result.warnings.isEmpty)
	#expect(result.settings.editor.font == "Monaco")
	#expect(result.settings.editor.fontSize == 16.5)
	#expect(result.settings.editor.lineNumbers)
	#expect(result.settings.editor.lineNumberMode == .relative)
	#expect(result.settings.editor.tabWidth == 2)
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

@Test func settingsParserReadsPerLanguageEditorOverrides() {
	let contents = #"""
	[editor]
	font_size = 15
	line_numbers = false
	tab_width = 8
	use_spaces = false

	[editor.language.python]
	tab_width = 4
	use_spaces = true
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
}

@Test func settingsDefaultsUsePieceTreeStorage() {
	let settings = ItsySettings()
	#expect(settings.editor.experimental.storage == .pieceTree)
	#expect(settings.syntax.preloadGrammars == .opened)
	#expect(ItsySettingsStore.serialize(settings).contains(#"storage = "piecetree""#))
	#expect(ItsySettingsStore.serialize(settings).contains(#"preload_grammars = "opened""#))
	#expect(ItsySettingsStore.serialize(settings).contains("use_spaces = false"))
	#expect(ItsySettingsStore.serialize(settings).contains(#"line_number_mode = "off""#))
	#expect(ItsySettingsStore.serialize(settings).contains(#"keymap = "plain""#))
	#expect(ItsySettingsStore.serialize(settings).contains(#"tab_groups = "window""#))
	#expect(ItsySettingsStore.serialize(settings).contains(#"wrap = "none""#))
	#expect(ItsySettingsStore.serialize(settings).contains("wrap_column = 100"))
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
