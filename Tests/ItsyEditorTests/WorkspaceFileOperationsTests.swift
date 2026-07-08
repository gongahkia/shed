import Foundation
import ItsyEditor
import Testing

@Test func workspaceFileOperationsPerformCRUDAndMove() throws {
	let fixture = try TemporaryWorkspaceFileOperationsFixture()
	let operations = WorkspaceFileOperations(roots: [fixture.root])

	let sources = try operations.createFolder(named: "Sources", in: fixture.root)
	let file = try operations.createFile(named: "App.swift", in: sources)
	#expect(FileManager.default.fileExists(atPath: file.path))

	let renamed = try operations.rename(file, to: "Main.swift")
	try "print(1)\n".write(to: renamed, atomically: true, encoding: .utf8)
	let copy = try operations.duplicate(renamed)
	#expect(copy.lastPathComponent == "Main copy.swift")

	let nested = try operations.createFolder(named: "Nested", in: fixture.root)
	let moved = try operations.move(copy, toDirectory: nested)
	#expect(moved.deletingLastPathComponent().standardizedFileURL == nested.standardizedFileURL)
	#expect(try String(contentsOf: moved, encoding: .utf8) == "print(1)\n")

	try operations.delete(renamed)
	#expect(!FileManager.default.fileExists(atPath: renamed.path))
}

@Test func workspaceFileOperationsRejectInvalidNames() throws {
	let fixture = try TemporaryWorkspaceFileOperationsFixture()
	let operations = WorkspaceFileOperations(roots: [fixture.root])

	#expect(throws: WorkspaceFileOperationError.emptyName) {
		_ = try operations.createFile(named: " ", in: fixture.root)
	}
	#expect(throws: WorkspaceFileOperationError.invalidName("bad/name")) {
		_ = try operations.createFolder(named: "bad/name", in: fixture.root)
	}
}

@Test func workspaceFileOperationsRejectPathsOutsideRoots() throws {
	let fixture = try TemporaryWorkspaceFileOperationsFixture()
	let outside = fixture.parent.appendingPathComponent("outside", isDirectory: true)
	try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: true)
	let operations = WorkspaceFileOperations(roots: [fixture.root])

	#expect(throws: WorkspaceFileOperationError.pathEscapesWorkspace(outside)) {
		_ = try operations.createFile(named: "leak.txt", in: outside)
	}
}

private final class TemporaryWorkspaceFileOperationsFixture {
	let parent: URL
	let root: URL

	init() throws {
		parent = FileManager.default.temporaryDirectory
			.appendingPathComponent("itsy-workspace-file-ops-\(UUID().uuidString)", isDirectory: true)
		root = parent.appendingPathComponent("workspace", isDirectory: true)
		try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
	}

	deinit {
		try? FileManager.default.removeItem(at: parent)
	}
}
