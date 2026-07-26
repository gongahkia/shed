import CoreServices
import Foundation
import ItsyEditor
import Testing

@Test func workspaceFileEventBatchCoalescesByPathAndTracksRescan() {
	let root = URL(fileURLWithPath: "/tmp/itsy-events", isDirectory: true)
	let first = root.appendingPathComponent("Sources/App.swift")
	let second = root.appendingPathComponent("Sources/Other.swift")
	let batch = WorkspaceFileEventBatch(events: [
		WorkspaceFileEvent(url: first, flags: FSEventStreamEventFlags(kFSEventStreamEventFlagItemCreated), eventID: 11),
		WorkspaceFileEvent(url: second, flags: FSEventStreamEventFlags(kFSEventStreamEventFlagItemModified), eventID: 12),
		WorkspaceFileEvent(url: first, flags: FSEventStreamEventFlags(kFSEventStreamEventFlagItemRemoved), eventID: 13),
		WorkspaceFileEvent(url: root, flags: FSEventStreamEventFlags(kFSEventStreamEventFlagMustScanSubDirs), eventID: 14),
	])

	#expect(batch.events.map { $0.url.lastPathComponent } == ["App.swift", "Other.swift", "itsy-events"])
	#expect(batch.events[0].eventID == 13)
	#expect(batch.lastEventID == 14)
	#expect(batch.requiresFullRescan)
}

@Test func workspaceFSEventIDStorePersistsPerWorkspace() throws {
	let root = FileManager.default.temporaryDirectory
		.appendingPathComponent("itsy-fsevent-store-\(UUID().uuidString)", isDirectory: true)
	let store = WorkspaceFSEventIDStore(directory: root.appendingPathComponent("store", isDirectory: true))
	let workspace = root.appendingPathComponent("workspace", isDirectory: true)
	let otherWorkspace = root.appendingPathComponent("other", isDirectory: true)
	defer {
		try? FileManager.default.removeItem(at: root)
	}

	store.save(eventID: 12345, for: workspace)

	#expect(store.eventID(for: workspace) == 12345)
	#expect(store.eventID(for: otherWorkspace) == nil)
}
