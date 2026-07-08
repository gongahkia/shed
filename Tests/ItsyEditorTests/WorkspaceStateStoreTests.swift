import Foundation
import ItsyEditor
import Testing

@Test func workspaceStateStoreRoundTripsDescriptorAndWindowState() throws {
	let fixture = try TemporaryWorkspaceStateStoreFixture()
	let store = WorkspaceStateStore()
	let secondRoot = fixture.root.appendingPathComponent("second", isDirectory: true)
	try FileManager.default.createDirectory(at: secondRoot, withIntermediateDirectories: true)
	let descriptor = WorkspaceDescriptor(roots: [fixture.root.path, secondRoot.path])

	try store.saveDescriptor(descriptor, for: fixture.root)
	let loadedDescriptor = try #require(store.loadDescriptor(for: fixture.root))
	#expect(loadedDescriptor == descriptor)
	#expect(FileManager.default.fileExists(atPath: fixture.root.appendingPathComponent(".itsy/workspace.json").path))

	let state = WorkspaceWindowState(
		paneLayout: "V[L,L]",
		selectedPath: fixture.root.appendingPathComponent("Sources/App.swift").path,
		openFiles: [
			WorkspaceWindowFileState(
				path: fixture.root.appendingPathComponent("Sources/App.swift").path,
				selectionAnchor: 2,
				selectionHead: 7,
				foldedRanges: [WorkspaceRangeState(lowerBound: 3, upperBound: 9)]
			),
		]
	)
	try store.saveWindowState(state, for: fixture.root)
	let loadedState = try #require(store.loadWindowState(for: fixture.root))

	#expect(loadedState == state)
	#expect(FileManager.default.fileExists(atPath: fixture.root.appendingPathComponent(".itsy/state.json").path))
}

private final class TemporaryWorkspaceStateStoreFixture {
	let root: URL

	init() throws {
		root = FileManager.default.temporaryDirectory
			.appendingPathComponent("itsy-workspace-state-store-\(UUID().uuidString)", isDirectory: true)
		try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
	}

	deinit {
		try? FileManager.default.removeItem(at: root)
	}
}
