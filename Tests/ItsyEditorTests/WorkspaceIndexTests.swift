import Foundation
import ItsyEditor
import Testing

@Test func workspaceIndexerBuildsGitignoreAwareFileAndSymbolIndex() throws {
	let fixture = try TemporaryWorkspaceIndexFixture()
	try fixture.write(".gitignore", "ignored/\n*.bin\n")
	try fixture.write("Sources/App.swift", """
	struct AppShell {
		func renderFrame() {}
		let frameCount = 0
	}
	""")
	try fixture.write("src/main.py", """
	class Worker:
	    def run_job(self):
	        pass
	""")
	try fixture.write("ignored/Hidden.swift", "struct Hidden {}\n")
	try fixture.writeData("asset.bin", Data([0, 1, 2, 3]))

	let index = WorkspaceIndexer.build(root: fixture.root)

	#expect(index.files.map(\.relativePath).contains("Sources/App.swift"))
	#expect(index.files.map(\.relativePath).contains("src/main.py"))
	#expect(!index.files.map(\.relativePath).contains("ignored/Hidden.swift"))
	#expect(!index.files.map(\.relativePath).contains("asset.bin"))
	#expect(index.symbols.contains(WorkspaceSymbol(name: "AppShell", kind: .type, relativePath: "Sources/App.swift", line: 1, column: 8)))
	#expect(index.symbols.contains(WorkspaceSymbol(name: "renderFrame", kind: .function, relativePath: "Sources/App.swift", line: 2, column: 7)))
	#expect(index.symbols.contains(WorkspaceSymbol(name: "Worker", kind: .type, relativePath: "src/main.py", line: 1, column: 7)))
	#expect(index.symbols.contains(WorkspaceSymbol(name: "run_job", kind: .function, relativePath: "src/main.py", line: 2, column: 9)))
}

@Test func workspaceIndexSearchRanksFilesAndSymbols() throws {
	let fixture = try TemporaryWorkspaceIndexFixture()
	try fixture.write("Sources/AppCoordinator.swift", """
	struct AppCoordinator {
		func openProject() {}
	}
	""")
	try fixture.write("Sources/Renderer.swift", "func drawFrame() {}\n")

	let index = WorkspaceIndexer.build(root: fixture.root)

	#expect(index.searchFiles(query: "appcoord", limit: 1) == ["Sources/AppCoordinator.swift"])
	#expect(index.searchSymbols(query: "openproj", limit: 1).map(\.name) == ["openProject"])
}

@Test func workspaceIndexerReindexUpdatesAddsAndRemovesFiles() throws {
	let fixture = try TemporaryWorkspaceIndexFixture()
	try fixture.write(".gitignore", "ignored/\n")
	try fixture.write("Sources/Foo.swift", """
	struct Foo {
		func runFoo() {}
	}
	""")

	var index = WorkspaceIndexer.build(root: fixture.root)
	let matcher = GitIgnoreMatcher(root: fixture.root)
	#expect(index.symbolsForFile(relativePath: "Sources/Foo.swift").map(\.name) == ["Foo", "runFoo"])

	try fixture.write("Sources/Foo.swift", """
	struct Foo {
		func runFoo() {}
		func extraStep() {}
	}
	""")
	let fooURL = fixture.root.appendingPathComponent("Sources/Foo.swift")
	WorkspaceIndexer.reindex(&index, changedURLs: [fooURL], matcher: matcher)
	#expect(index.symbolsForFile(relativePath: "Sources/Foo.swift").map(\.name) == ["Foo", "runFoo", "extraStep"])

	try fixture.write("Sources/Bar.swift", "struct Bar {}\n")
	let barURL = fixture.root.appendingPathComponent("Sources/Bar.swift")
	WorkspaceIndexer.reindex(&index, changedURLs: [barURL], matcher: matcher)
	#expect(index.symbolsForFile(relativePath: "Sources/Bar.swift").map(\.name) == ["Bar"])

	try FileManager.default.removeItem(at: fooURL)
	WorkspaceIndexer.reindex(&index, changedURLs: [fooURL], matcher: matcher)
	#expect(index.symbolsForFile(relativePath: "Sources/Foo.swift").isEmpty)
	#expect(!index.files.map(\.relativePath).contains("Sources/Foo.swift"))

	try fixture.write("ignored/Hidden.swift", "struct Hidden {}\n")
	let hiddenURL = fixture.root.appendingPathComponent("ignored/Hidden.swift")
	WorkspaceIndexer.reindex(&index, changedURLs: [hiddenURL], matcher: matcher)
	#expect(!index.files.map(\.relativePath).contains("ignored/Hidden.swift"))
}

@Test func workspaceIndexScopesSymbolsAndPathsByActiveFile() throws {
	let fixture = try TemporaryWorkspaceIndexFixture()
	try fixture.write("Sources/Foo.swift", """
	struct Foo {
		func runFoo() {}
	}
	""")
	try fixture.write("Sources/Bar.swift", """
	struct Bar {
		func runBar() {}
	}
	""")

	let index = WorkspaceIndexer.build(root: fixture.root)
	let fooURL = fixture.root.appendingPathComponent("Sources/Foo.swift")

	#expect(index.relativePath(for: fooURL) == "Sources/Foo.swift")
	#expect(index.relativePath(for: URL(fileURLWithPath: "/elsewhere/Foo.swift")) == nil)
	#expect(index.symbolsForFile(relativePath: "Sources/Foo.swift").map(\.name) == ["Foo", "runFoo"])
	#expect(index.symbolsForFile(relativePath: "Sources/Missing.swift").isEmpty)
}

private final class TemporaryWorkspaceIndexFixture {
	let root: URL

	init() throws {
		root = FileManager.default.temporaryDirectory
			.appendingPathComponent("itsy-workspace-index-\(UUID().uuidString)", isDirectory: true)
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

	func writeData(_ path: String, _ data: Data) throws {
		let url = root.appendingPathComponent(path)
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try data.write(to: url)
	}
}
