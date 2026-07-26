import Foundation
import ItsyEditor
import Testing

@Test func snippetRegistryLoadsVSCodeSnippetFile() throws {
	let fixture = try TemporarySnippetFixture()
	try fixture.write("swift.json", """
	{
	  "Print value": {
	    "prefix": ["pr", "printv"],
	    "body": ["print(${1:value})", "$0"],
	    "description": "Prints a value",
	    "scope": "swift, typescript"
	  },
	  "Python only": {
	    "prefix": "py",
	    "body": "print($1)",
	    "scope": "python"
	  }
	}
	""")

	let snippets = try SnippetRegistry.load(url: fixture.root.appendingPathComponent("swift.json"), languageID: "swift")

	#expect(snippets == [
		SnippetDefinition(
			name: "Print value",
			prefixes: ["pr", "printv"],
			body: "print(${1:value})\n$0",
			description: "Prints a value",
			scope: ["swift", "typescript"]
		),
	])
}

@Test func snippetRegistryDiscoversGlobalWorkspaceAndExtensionSnippets() throws {
	let fixture = try TemporarySnippetFixture()
	try fixture.writeHome(".config/itsy/snippets/swift.json", """
	{
	  "Global": { "prefix": "glob", "body": "global($1)" }
	}
	""")
	try fixture.write(".itsy/snippets/swift.json", """
	{
	  "Workspace": { "prefix": "work", "body": "workspace($1)" }
	}
	""")
	try fixture.writeHome(".config/itsy/extensions/dev.installed.snippets/1.0.0/extension.json", """
	{
	  "schemaVersion": 2,
	  "identifier": "dev.installed.snippets",
	  "name": "Installed Snippets",
	  "version": "1.0.0",
	  "contributes": {
	    "snippets": [
	      { "language": "swift", "path": "snippets/swift.json" }
	    ]
	  }
	}
	""")
	try fixture.writeHome(".config/itsy/extensions/dev.installed.snippets/1.0.0/snippets/swift.json", """
	{
	  "Installed": { "prefix": "inst", "body": "installed($1)" }
	}
	""")
	try fixture.write(".itsy/extensions/example.json", """
	{
	  "schemaVersion": 2,
	  "identifier": "dev.example.snippets",
	  "name": "Snippets",
	  "version": "0.1.0",
	  "contributes": {
	    "snippets": [
	      { "language": "swift", "path": "snippets/swift.json" }
	    ]
	  }
	}
	""")
	try fixture.write(".itsy/extensions/snippets/swift.json", """
	{
	  "Extension": { "prefix": "ext", "body": "extension($1)" }
	}
	""")

	let snippets = SnippetRegistry.discover(languageID: "swift", workspaceRoot: fixture.root, homeDirectory: fixture.home)

	#expect(snippets.map(\.name) == ["Global", "Workspace", "Installed", "Extension"])
	#expect(snippets.map(\.prefixes.first) == ["glob", "work", "inst", "ext"])
}

private final class TemporarySnippetFixture {
	let root: URL
	let home: URL

	init() throws {
		root = FileManager.default.temporaryDirectory
			.appendingPathComponent("itsy-snippets-\(UUID().uuidString)", isDirectory: true)
		home = FileManager.default.temporaryDirectory
			.appendingPathComponent("itsy-snippets-home-\(UUID().uuidString)", isDirectory: true)
		try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
		try FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
	}

	deinit {
		try? FileManager.default.removeItem(at: root)
		try? FileManager.default.removeItem(at: home)
	}

	func write(_ path: String, _ contents: String) throws {
		try write(path, contents, under: root)
	}

	func writeHome(_ path: String, _ contents: String) throws {
		try write(path, contents, under: home)
	}

	private func write(_ path: String, _ contents: String, under base: URL) throws {
		let url = base.appendingPathComponent(path)
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
	}
}
