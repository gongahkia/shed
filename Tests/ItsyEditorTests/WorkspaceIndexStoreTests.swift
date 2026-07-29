import Foundation
import ItsyEditor
import Testing

@Test func workspaceIndexStoreSavesAndLoadsIndex() throws {
	let fixture = try TemporaryWorkspaceIndexStoreFixture()
	let root = try fixture.workspaceRoot()
	let store = WorkspaceIndexStore(directory: fixture.cacheRoot)
	let index = WorkspaceIndex(root: root, files: [
		WorkspaceIndexedFile(
			relativePath: "Sources/Beta.swift",
			symbols: [
				WorkspaceSymbol(name: "second", kind: .function, relativePath: "Sources/Beta.swift", line: 3, column: 7),
				WorkspaceSymbol(name: "first", kind: .function, relativePath: "Sources/Beta.swift", line: 2, column: 7),
			]
		),
		WorkspaceIndexedFile(
			relativePath: "Sources/Alpha.swift",
			symbols: [
				WorkspaceSymbol(name: "Alpha", kind: .type, relativePath: "Sources/Alpha.swift", line: 1, column: 8),
			]
		),
	])

	try store.save(index)
	let loaded = try #require(try store.load(for: root))

	#expect(loaded.root.standardizedFileURL == root.standardizedFileURL)
	#expect(loaded.files.map(\.relativePath) == ["Sources/Alpha.swift", "Sources/Beta.swift"])
	#expect(loaded.symbols.map(\.name) == ["Alpha", "first", "second"])
}

@Test func workspaceIndexStoreReturnsNilForMissingIndex() throws {
	let fixture = try TemporaryWorkspaceIndexStoreFixture()
	let root = try fixture.workspaceRoot()
	let store = WorkspaceIndexStore(directory: fixture.cacheRoot)

	#expect(try store.load(for: root) == nil)
}

@Test func workspaceIndexStoreRejectsUnsupportedVersion() throws {
	let fixture = try TemporaryWorkspaceIndexStoreFixture()
	let root = try fixture.workspaceRoot()
	let store = WorkspaceIndexStore(directory: fixture.cacheRoot)
	let index = WorkspaceIndex(root: root, files: [])

	try store.save(index)
	let files = try FileManager.default.contentsOfDirectory(at: fixture.cacheRoot, includingPropertiesForKeys: nil)
	let fileURL = try #require(files.first)
	try """
	{
	  "files" : [],
	  "rootPath" : "\(root.standardizedFileURL.path)",
	  "version" : 99
	}
	""".write(to: fileURL, atomically: true, encoding: .utf8)

	#expect(throws: WorkspaceIndexStoreError.unsupportedVersion(99)) {
		_ = try store.load(for: root)
	}
}

private final class TemporaryWorkspaceIndexStoreFixture {
	let root: URL
	let cacheRoot: URL

	init() throws {
		root = FileManager.default.temporaryDirectory
			.appendingPathComponent("itsy-workspace-index-store-\(UUID().uuidString)", isDirectory: true)
		cacheRoot = root.appendingPathComponent("cache", isDirectory: true)
		try FileManager.default.createDirectory(at: cacheRoot, withIntermediateDirectories: true)
	}

	deinit {
		try? FileManager.default.removeItem(at: root)
	}

	func workspaceRoot() throws -> URL {
		let url = root.appendingPathComponent("workspace", isDirectory: true)
		try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
		return url
	}
}
