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
	settings.git.autoIgnoreItsy = false

	let data = try ItsySettingsJSONCodec.encode(settings)
	let document = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
	#expect(document["schema_version"] as? Int == ItsySettingsSchema.currentVersion)
	#expect(try ItsySettingsJSONCodec.decode(data) == settings.normalized())
}

@Test func settingsJSONCodecEncodesEmptyLayerForSettingsFileCreation() throws {
	let data = try ItsySettingsJSONCodec.encodeEmptyLayer()
	let layer = try ItsySettingsJSONCodec.decodeLayer(data)
	#expect(layer.settings == ItsySettings.default.normalized())
	#expect(layer.assignedKeys.isEmpty)
}

@Test func settingsJSONCodecMergesPartialLayerAndLanguageOverrides() throws {
	var fallback = ItsySettings.default.normalized()
	fallback.editor.font = "Monaco"
	fallback.editor.language["swift"] = .init(useSpaces: false)
	let data = Data(#"""
	{
	  "schema_version": 12,
	  "settings": {
	    "editor": {
	      "font_size": 18,
	      "language": { "swift": { "tab_width": 2 } }
	    },
	    "layout": { "sidebar_position": "trailing" },
	    "git": { "auto_ignore_itsy": false }
	  }
	}
	"""#.utf8)
	let layer = try ItsySettingsJSONCodec.decodeLayer(data, fallback: fallback)
	#expect(layer.settings.editor.font == "Monaco")
	#expect(layer.settings.editor.fontSize == 18)
	#expect(layer.settings.editor.language["swift"] == .init(tabWidth: 2, useSpaces: false))
	#expect(layer.settings.layout.sidebarPosition == .trailing)
	#expect(!layer.settings.git.autoIgnoreItsy)
	#expect(layer.assignedKeys == [
		"editor.font_size",
		"editor.language.swift.tab_width",
		"layout.sidebar_position",
		"git.auto_ignore_itsy",
	])
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


@Test func settingsJSONCodecRoundTripsGlobalLSPModes() throws {
	let settings = ItsySettings(lsp: .init(catalogAutomaticallyCheck: true, modes: ["python": .managed, "typescript": .disabled]))
	let roundTrip = try ItsySettingsJSONCodec.decode(ItsySettingsJSONCodec.encode(settings))
	#expect(roundTrip.lsp == settings.lsp)
}






@Test func settingsDefaultsUsePieceTreeStorage() {
	let settings = ItsySettings()
	#expect(settings.editor.experimental.storage == .pieceTree)
	#expect(settings.syntax.preloadGrammars == .opened)
	#expect(settings.git.autoIgnoreItsy)
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
		.appendingPathComponent("settings.json")
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
	let globalURL = directory.appendingPathComponent("settings.json")
	let workspaceRoot = directory.appendingPathComponent("workspace", isDirectory: true)
	let workspaceURL = ItsySettingsStore.workspaceFileURL(workspaceRoot: workspaceRoot)
	try FileManager.default.createDirectory(
		at: workspaceURL.deletingLastPathComponent(),
		withIntermediateDirectories: true
	)
	var globalSettings = ItsySettings.default
	globalSettings.editor.tabWidth = 2
	globalSettings.editor.useSpaces = false
	globalSettings.editor.language["python"] = .init(tabWidth: 3)
	try ItsySettingsStore(fileURL: globalURL).save(globalSettings)
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

@Test func settingsStoreMergesGlobalAndWorkspaceJSONLayers() throws {
	let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
		"itsy-settings-json-layers-\(UUID().uuidString)",
		isDirectory: true
	)
	defer { try? FileManager.default.removeItem(at: directory) }
	let globalURL = directory.appendingPathComponent("settings.json")
	let workspaceRoot = directory.appendingPathComponent("workspace", isDirectory: true)
	let workspaceURL = ItsySettingsStore.workspaceFileURL(workspaceRoot: workspaceRoot)
	var globalSettings = ItsySettings.default.normalized()
	globalSettings.editor.font = "Monaco"
	globalSettings.editor.tabWidth = 2
	globalSettings.editor.language["swift"] = .init(useSpaces: false)
	try ItsySettingsStore(fileURL: globalURL).save(globalSettings)
	try FileManager.default.createDirectory(at: workspaceURL.deletingLastPathComponent(), withIntermediateDirectories: true)
	try #"""
	{
	  "schema_version": 12,
	  "settings": {
	    "editor": {
	      "font_size": 18,
	      "language": { "swift": { "tab_width": 3 } }
	    }
	  }
	}
	"""#.write(to: workspaceURL, atomically: true, encoding: .utf8)
	let resolution = ItsySettingsStore(fileURL: globalURL).resolve(workspaceRoot: workspaceRoot)
	#expect(resolution.settings.editor.font == "Monaco")
	#expect(resolution.settings.editor.tabWidth == 2)
	#expect(resolution.settings.editor.fontSize == 18)
	#expect(resolution.settings.editorSettings(languageID: "swift").tabWidth == 3)
	#expect(!resolution.settings.editorSettings(languageID: "swift").useSpaces)
	#expect(resolution.source(for: "editor.font") == .global)
	#expect(resolution.source(for: "editor.font_size") == .workspace)
	#expect(resolution.sources["editor.language.swift.tab_width"] == .workspace)
}

@Test func workspaceUIOverridesAreIgnored() throws {
	let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
		"itsy-ui-scope-\(UUID().uuidString)",
		isDirectory: true
	)
	defer { try? FileManager.default.removeItem(at: directory) }
	let globalURL = directory.appendingPathComponent("settings.json")
	let workspaceRoot = directory.appendingPathComponent("workspace", isDirectory: true)
	let workspaceURL = ItsySettingsStore.workspaceFileURL(workspaceRoot: workspaceRoot)
	try FileManager.default.createDirectory(
		at: workspaceURL.deletingLastPathComponent(),
		withIntermediateDirectories: true
	)
	var globalSettings = ItsySettings.default
	globalSettings.ui.fontScale = 1.25
	globalSettings.updates.automaticallyCheck = true
	try ItsySettingsStore(fileURL: globalURL).save(globalSettings)
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
	let globalURL = directory.appendingPathComponent("settings.json")
	let workspaceRoot = directory.appendingPathComponent("workspace", isDirectory: true)
	let workspaceURL = ItsySettingsStore.workspaceFileURL(workspaceRoot: workspaceRoot)
	try FileManager.default.createDirectory(
		at: workspaceURL.deletingLastPathComponent(),
		withIntermediateDirectories: true
	)
	var globalSettings = ItsySettings.default
	globalSettings.editor.tabWidth = 2
	globalSettings.editor.wrap = .soft
	globalSettings.editor.multipleSelections = false
	globalSettings.editor.keymap = .vim
	globalSettings.editor.language["python"] = .init(tabWidth: 3)
	globalSettings.updates.automaticallyCheck = true
	try ItsySettingsStore(fileURL: globalURL).save(globalSettings)
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

@Test func settingsWatcherPublishesJSONFileChangesForHotReload() throws {
	let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
		"itsy-settings-watch-\(UUID().uuidString)",
		isDirectory: true
	)
	defer {
		try? FileManager.default.removeItem(at: directory)
	}
	try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
	let url = directory.appendingPathComponent("settings.json")
	let semaphore = DispatchSemaphore(value: 0)
	let watcher = ItsySettingsWatcher(
		urls: [url],
		queue: DispatchQueue(label: "dev.itsy.settings-watch-test"),
		debounce: 0.02
	) {
		semaphore.signal()
	}
	#expect(watcher.start())
	try ItsySettingsStore(fileURL: url).save(ItsySettings(editor: .init(tabWidth: 7)))
	#expect(try ItsySettingsJSONCodec.decode(Data(contentsOf: url)).editor.tabWidth == 7)
	#expect(semaphore.wait(timeout: .now() + .seconds(3)) == .success)
	watcher.stop()
}
