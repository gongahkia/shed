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
