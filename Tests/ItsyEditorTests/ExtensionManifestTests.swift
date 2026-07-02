import Foundation
import ItsyEditor
import Testing

@Test func extensionManifestLoadsDeclarativeTaskContribution() throws {
	let fixture = try TemporaryExtensionFixture()
	try fixture.write(".itsy/extensions/example.json", """
	{
	  "schemaVersion": 1,
	  "identifier": "dev.example.tasks",
	  "name": "Example Tasks",
	  "version": "0.1.0",
	  "contributes": {
	    "tasks": [
	      {
	        "id": "hello",
	        "label": "Say Hello",
	        "command": "/bin/echo",
	        "arguments": ["hello"]
	      }
	    ]
	  }
	}
	""")

	let manifests = ExtensionManifestLoader.discover(root: fixture.root)
	let tasks = WorkspaceTaskDiscovery.discover(root: fixture.root)

	#expect(manifests.count == 1)
	#expect(manifests[0].identifier == "dev.example.tasks")
	#expect(tasks.contains(WorkspaceTask(
		id: "extension:dev.example.tasks:hello",
		label: "Say Hello",
		source: .extensionManifest,
		command: "/bin/echo",
		arguments: ["hello"],
		workingDirectory: fixture.root
	)))
}

@Test func extensionManifestRejectsUnsupportedSchema() throws {
	let fixture = try TemporaryExtensionFixture()
	try fixture.write(".itsy/extensions/bad.json", """
	{
	  "schemaVersion": 99,
	  "identifier": "dev.example.bad",
	  "name": "Bad",
	  "version": "0.1.0",
	  "contributes": { "tasks": [] }
	}
	""")

	#expect(throws: ExtensionManifestError.self) {
		_ = try ExtensionManifestLoader.load(url: fixture.root.appendingPathComponent(".itsy/extensions/bad.json"))
	}
}

@Test func extensionManifestLoadsSchemaV2ContributionMetadata() throws {
	let fixture = try TemporaryExtensionFixture()
	try fixture.write(".itsy/extensions/v2.json", """
	{
	  "schemaVersion": 2,
	  "identifier": "dev.example.metadata",
	  "name": "Metadata",
	  "version": "0.2.0",
	  "contributes": {
	    "commands": [
	      { "id": "openInspector", "title": "Open Inspector", "category": "Tools" }
	    ],
	    "keybindings": [
	      { "command": "dev.example.metadata.openInspector", "key": "cmd+shift+i", "when": "editorFocus" }
	    ],
	    "themes": [
	      { "id": "night", "label": "Night", "path": "./themes/night.toml" }
	    ],
	    "snippets": [
	      { "language": "swift", "path": "./snippets/swift.json" }
	    ],
	    "languages": [
	      { "id": "itsylog", "aliases": ["Itsy Log"], "extensions": [".itsylog"] }
	    ],
	    "problemMatchers": [
	      { "id": "swiftc", "label": "swiftc", "pattern": "^(.*):(\\\\d+):(\\\\d+): error: (.*)$", "fileLocation": "relative" }
	    ]
	  }
	}
	""")

	let manifest = try ExtensionManifestLoader.load(url: fixture.root.appendingPathComponent(".itsy/extensions/v2.json"))

	#expect(manifest.schemaVersion == 2)
	#expect(manifest.contributes.tasks.isEmpty)
	#expect(manifest.contributes.commands == [
		ExtensionCommandContribution(id: "openInspector", title: "Open Inspector", category: "Tools"),
	])
	#expect(manifest.contributes.keybindings == [
		ExtensionKeybindingContribution(command: "dev.example.metadata.openInspector", key: "cmd+shift+i", when: "editorFocus"),
	])
	#expect(manifest.contributes.themes == [
		ExtensionThemeContribution(id: "night", label: "Night", path: "./themes/night.toml"),
	])
	#expect(manifest.contributes.snippets == [
		ExtensionSnippetContribution(language: "swift", path: "./snippets/swift.json"),
	])
	#expect(manifest.contributes.languages == [
		ExtensionLanguageContribution(id: "itsylog", aliases: ["Itsy Log"], extensions: [".itsylog"]),
	])
	#expect(manifest.contributes.problemMatchers == [
		ExtensionProblemMatcherContribution(
			id: "swiftc",
			label: "swiftc",
			pattern: #"^(.*):(\d+):(\d+): error: (.*)$"#,
			fileLocation: "relative"
		),
	])
}

@Test func extensionManifestSchemaV2DefaultsMissingContributionArrays() throws {
	let fixture = try TemporaryExtensionFixture()
	try fixture.write(".itsy/extensions/minimal-v2.json", """
	{
	  "schemaVersion": 2,
	  "identifier": "dev.example.empty",
	  "name": "Empty",
	  "version": "0.2.0",
	  "contributes": {}
	}
	""")

	let manifest = try ExtensionManifestLoader.load(url: fixture.root.appendingPathComponent(".itsy/extensions/minimal-v2.json"))

	#expect(manifest.contributes.tasks.isEmpty)
	#expect(manifest.contributes.commands.isEmpty)
	#expect(manifest.contributes.keybindings.isEmpty)
	#expect(manifest.contributes.themes.isEmpty)
	#expect(manifest.contributes.snippets.isEmpty)
	#expect(manifest.contributes.languages.isEmpty)
	#expect(manifest.contributes.problemMatchers.isEmpty)
}

@Test func extensionManifestSchemaV2RejectsEmptyContributionMetadata() throws {
	let fixture = try TemporaryExtensionFixture()
	try fixture.write(".itsy/extensions/empty-command.json", """
	{
	  "schemaVersion": 2,
	  "identifier": "dev.example.bad",
	  "name": "Bad",
	  "version": "0.2.0",
	  "contributes": {
	    "commands": [
	      { "id": " ", "title": "Open Inspector" }
	    ]
	  }
	}
	""")

	#expect(throws: ExtensionManifestError.emptyContributionID) {
		_ = try ExtensionManifestLoader.load(url: fixture.root.appendingPathComponent(".itsy/extensions/empty-command.json"))
	}
}

private final class TemporaryExtensionFixture {
	let root: URL

	init() throws {
		root = FileManager.default.temporaryDirectory
			.appendingPathComponent("itsy-extension-\(UUID().uuidString)", isDirectory: true)
		try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
	}

	deinit {
		try? FileManager.default.removeItem(at: root)
	}

	func write(_ path: String, _ contents: String) throws {
		let url = root.appendingPathComponent(path)
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
	}
}
