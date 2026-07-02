import Foundation
import Testing
@testable import ItsyConfig

@Test func settingsParserReadsKnownSectionsAndComments() throws {
	let contents = #"""
	# user-editable config
	[editor]
	font = "Monaco" # installed on macOS
	font_size = 16.5
	line_numbers = true
	tab_width = 2

	[editor.experimental]
	storage = "piecetree"

	[theme]
	id = "bundled:default-dark"

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
	#expect(result.settings.editor.tabWidth == 2)
	#expect(result.settings.editor.experimental.storage == .pieceTree)
	#expect(result.settings.theme.id == "bundled:default-dark")
	#expect(result.settings.terminal.fontSize == 13)
	#expect(result.settings.terminal.scrollbackLines == 20_000)
}

@Test func settingsParserWarnsAndKeepsFallbackForBadValues() throws {
	let fallback = ItsySettings(
		editor: .init(font: "Menlo", fontSize: 15, lineNumbers: false, tabWidth: 4),
		theme: .init(id: "bundled:default-light"),
		terminal: .init(fontSize: 12, scrollbackLines: 10_000)
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

@Test func settingsStoreSavesAndReloadsToml() throws {
	let directory = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-settings-\(UUID().uuidString)", isDirectory: true)
	defer {
		try? FileManager.default.removeItem(at: directory)
	}
	let url = directory.appendingPathComponent("settings.toml")
	let store = ItsySettingsStore(fileURL: url)
	let settings = ItsySettings(
		editor: .init(font: "Monaco", fontSize: 18, lineNumbers: true, tabWidth: 8, experimental: .init(storage: .pieceTree)),
		theme: .init(id: "user:night.toml"),
		terminal: .init(fontSize: 14, scrollbackLines: 1234)
	)
	try store.save(settings)
	let loaded = store.load()
	#expect(loaded.loadedFromFile)
	#expect(loaded.warnings.isEmpty)
	#expect(loaded.settings == settings)
}

@Test func settingsStoreUsesFallbackWhenFileIsMissing() throws {
	let url = FileManager.default.temporaryDirectory
		.appendingPathComponent("missing-\(UUID().uuidString)")
		.appendingPathComponent("settings.toml")
	let fallback = ItsySettings(editor: .init(font: "Monaco", fontSize: 17, lineNumbers: true, tabWidth: 3))
	let loaded = ItsySettingsStore(fileURL: url).load(fallback: fallback)
	#expect(!loaded.loadedFromFile)
	#expect(loaded.settings == fallback)
}
