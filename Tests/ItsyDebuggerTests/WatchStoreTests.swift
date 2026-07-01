import Foundation
import ItsyDebugger
import Testing

@Test func watchStoreAddsAndRemovesUniqueExpressions() async throws {
	let fixture = try WatchStoreFixture()
	defer {
		fixture.cleanup()
	}
	let store = WatchStore(fileURL: fixture.storeURL)
	let workspace = fixture.root.appendingPathComponent("Project")

	#expect(await store.add(" value ", for: workspace))
	#expect(await store.add("value", for: workspace) == false)
	#expect(await store.expressions(for: workspace) == ["value"])
	await store.remove("value", for: workspace)
	#expect(await store.expressions(for: workspace).isEmpty)
}

@Test func watchStorePersistsPerWorkspaceExpressions() async throws {
	let fixture = try WatchStoreFixture()
	defer {
		fixture.cleanup()
	}
	let workspaceA = fixture.root.appendingPathComponent("A")
	let workspaceB = fixture.root.appendingPathComponent("B")
	let store = WatchStore(fileURL: fixture.storeURL)

	await store.replace(["a", "b", "a", ""], for: workspaceA)
	await store.replace(["c"], for: workspaceB)
	try await store.save()

	let loaded = WatchStore(fileURL: fixture.storeURL)
	try await loaded.load()

	#expect(await loaded.expressions(for: workspaceA) == ["a", "b"])
	#expect(await loaded.expressions(for: workspaceB) == ["c"])
}

private struct WatchStoreFixture {
	let root: URL
	let storeURL: URL

	init(fileManager: FileManager = .default) throws {
		root = fileManager.temporaryDirectory.appendingPathComponent("itsy-watches-\(UUID().uuidString)", isDirectory: true)
		storeURL = root
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("watches.json")
		try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
	}

	func cleanup(fileManager: FileManager = .default) {
		try? fileManager.removeItem(at: root)
	}
}
