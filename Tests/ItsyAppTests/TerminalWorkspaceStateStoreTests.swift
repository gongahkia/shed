import Foundation
@testable import ItsyApp
import Testing

@Test func terminalWorkspaceRestorationRepairsUnavailablePaneDirectories() throws {
	let fixture = try TerminalWorkspaceStateStoreFixture()
	defer { fixture.remove() }
	let existingDirectory = fixture.root.appendingPathComponent("existing", isDirectory: true)
	let fallbackDirectory = fixture.root.appendingPathComponent("fallback", isDirectory: true)
	let fileURL = fixture.root.appendingPathComponent("not-a-directory")
	try FileManager.default.createDirectory(at: existingDirectory, withIntermediateDirectories: true)
	try FileManager.default.createDirectory(at: fallbackDirectory, withIntermediateDirectories: true)
	try Data().write(to: fileURL)
	let state = TerminalWorkspaceState(
		selectedTabIndex: 0,
		tabs: [
			.init(
				title: "workspace",
				activePaneIndex: 2,
				rootPane: .split(
					orientation: .horizontal,
					children: [
						.leaf(currentDirectoryPath: existingDirectory.path),
						.leaf(currentDirectoryPath: fixture.root.appendingPathComponent("missing").path),
						.leaf(currentDirectoryPath: fileURL.path),
					]
				)
			)
		]
	)

	let restoration = try #require(TerminalWorkspaceRestoration.restore(data: JSONEncoder().encode(state), fallbackDirectoryURL: fallbackDirectory))
	#expect(restoration.requiresPersistence)
	#expect(restoration.state.tabs[0].rootPane == .split(
		orientation: .horizontal,
		children: [
			.leaf(currentDirectoryPath: existingDirectory.path),
			.leaf(currentDirectoryPath: fallbackDirectory.standardizedFileURL.path),
			.leaf(currentDirectoryPath: fallbackDirectory.standardizedFileURL.path),
		]
	))
}

@Test func terminalWorkspaceStateStoreSavesRestoresRemovesAndRejectsInvalidState() throws {
	let fixture = try TerminalWorkspaceStateStoreFixture()
	defer { fixture.remove() }
	let fallbackDirectory = fixture.root.appendingPathComponent("fallback", isDirectory: true)
	try FileManager.default.createDirectory(at: fallbackDirectory, withIntermediateDirectories: true)
	let store = TerminalWorkspaceStateStore(workspaceURL: fixture.root)
	let state = TerminalWorkspaceState(
		selectedTabIndex: 0,
		tabs: [.init(title: "workspace", activePaneIndex: 0, rootPane: .leaf(currentDirectoryPath: fallbackDirectory.path))]
	)

	try store.save(state)
	#expect(FileManager.default.fileExists(atPath: store.url.path))
	#expect(store.restore(fallbackDirectoryURL: fallbackDirectory)?.state == state)
	store.remove()
	#expect(!FileManager.default.fileExists(atPath: store.url.path))
	try Data("invalid".utf8).write(to: store.url)
	#expect(store.restore(fallbackDirectoryURL: fallbackDirectory) == nil)
}

private final class TerminalWorkspaceStateStoreFixture {
	let root = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-terminal-workspace-\(UUID().uuidString)", isDirectory: true)

	init() throws {
		try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
	}

	func remove() {
		try? FileManager.default.removeItem(at: root)
	}
}
