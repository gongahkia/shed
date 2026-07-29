import Foundation
import ItsyDAP
import ItsyDebugger
import Testing

@Test func breakpointStoreTogglesAndRemovesLines() async throws {
	let fixture = try BreakpointStoreFixture()
	defer {
		fixture.cleanup()
	}
	let store = BreakpointStore(fileURL: fixture.storeURL)
	let source = fixture.root.appendingPathComponent("main.swift")

	#expect(await store.toggle(line: 12, in: source))
	#expect(await store.breakpoints(for: source) == [SourceBreakpoint(line: 12)])
	#expect(await store.toggle(line: 12, in: source) == false)
	#expect(await store.breakpoints(for: source).isEmpty)

	#expect(await store.toggle(line: 9, in: source))
	await store.remove(line: 9, in: source)
	#expect(await store.breakpoints(for: source).isEmpty)
}

@Test func breakpointStorePersistsSortedBreakpoints() async throws {
	let fixture = try BreakpointStoreFixture()
	defer {
		fixture.cleanup()
	}
	let sourceA = fixture.root.appendingPathComponent("A.swift")
	let sourceB = fixture.root.appendingPathComponent("B.swift")
	let store = BreakpointStore(fileURL: fixture.storeURL)

	await store.replace([
		SourceBreakpoint(line: 20, condition: "value > 0"),
		SourceBreakpoint(line: 7, column: 3, hitCondition: "2", logMessage: "hit"),
	], for: sourceA)
	await store.replace([SourceBreakpoint(line: 3)], for: sourceB)
	try await store.save()

	let loaded = BreakpointStore(fileURL: fixture.storeURL)
	try await loaded.load()

	#expect(await loaded.breakpoints(for: sourceA) == [
		SourceBreakpoint(line: 7, column: 3, hitCondition: "2", logMessage: "hit"),
		SourceBreakpoint(line: 20, condition: "value > 0"),
	])
	#expect(await loaded.breakpoints(for: sourceB) == [SourceBreakpoint(line: 3)])
}

private struct BreakpointStoreFixture {
	let root: URL
	let storeURL: URL

	init(fileManager: FileManager = .default) throws {
		root = fileManager.temporaryDirectory.appendingPathComponent("itsy-breakpoints-\(UUID().uuidString)", isDirectory: true)
		storeURL = root
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("breakpoints.json")
		try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
	}

	func cleanup(fileManager: FileManager = .default) {
		try? fileManager.removeItem(at: root)
	}
}
