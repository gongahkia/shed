@testable import ItsyApp
import Foundation
import ItsyEditor
import ItsyKeymap
import Testing

@Test func extensionKeybindingMapperScopesReferencesAndSkipsInvalidBindings() throws {
	let manifest = ExtensionManifest(
		schemaVersion: 2,
		identifier: "dev.example.commands",
		name: "Commands",
		version: "0.2.0",
		contributes: ExtensionContributions(
			commands: [
				ExtensionCommandContribution(id: "openInspector", title: "Open Inspector"),
				ExtensionCommandContribution(id: "reload", title: "Reload"),
			],
			keybindings: [
				ExtensionKeybindingContribution(command: "openInspector", key: "cmd+shift+i"),
				ExtensionKeybindingContribution(command: "extension:dev.example.commands:reload", key: "Cmd-R"),
				ExtensionKeybindingContribution(command: "dev.example.commands.openInspector", key: "Cmd-Opt-I"),
				ExtensionKeybindingContribution(command: "missing", key: "Cmd-M"),
				ExtensionKeybindingContribution(command: "reload", key: "Hyper-R"),
			]
		)
	)
	let validCommandIDs: Set<String> = [
		"extension:dev.example.commands:openInspector",
		"extension:dev.example.commands:reload",
	]

	let bindings = ExtensionKeybindingMapper.bindings(from: manifest, mode: .insert, validCommandIDs: validCommandIDs)

	#expect(bindings.map(\.commandID) == [
		"extension:dev.example.commands:openInspector",
		"extension:dev.example.commands:reload",
		"extension:dev.example.commands:openInspector",
	])
	#expect(bindings.map(\.mode) == [.insert, .insert, .insert])
	#expect(bindings[0].chord == [Key("i", modifiers: [.command, .shift])])
	#expect(bindings[1].chord == [Key("r", modifiers: .command)])
	#expect(bindings[2].chord == [Key("i", modifiers: [.command, .option])])
}

@Test func extensionKeybindingMapperDiscoversWorkspaceManifestBindings() throws {
	let fixture = try TemporaryExtensionKeybindingFixture()
	try fixture.write(".itsy/extensions/commands.json", """
	{
	  "schemaVersion": 2,
	  "identifier": "dev.example.commands",
	  "name": "Commands",
	  "version": "0.2.0",
	  "contributes": {
	    "commands": [
	      { "id": "openInspector", "title": "Open Inspector" }
	    ],
	    "keybindings": [
	      { "command": "openInspector", "key": "cmd+shift+i", "when": "editorFocus" }
	    ]
	  }
	}
	""")

	let bindings = ExtensionKeybindingMapper.discover(
		root: fixture.root,
		mode: .insert,
		validCommandIDs: ["extension:dev.example.commands:openInspector"]
	)

	#expect(bindings.count == 1)
	#expect(bindings[0].commandID == "extension:dev.example.commands:openInspector")
	#expect(bindings[0].chord == [Key("i", modifiers: [.command, .shift])])
}

private final class TemporaryExtensionKeybindingFixture {
	let root: URL

	init() throws {
		root = FileManager.default.temporaryDirectory
			.appendingPathComponent("itsy-extension-keybinding-\(UUID().uuidString)", isDirectory: true)
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
