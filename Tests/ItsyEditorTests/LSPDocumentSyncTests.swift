import Foundation
import ItsyEditor
import ItsyLSP
import Testing

@Test func lspDocumentSyncEmitsDidOpenAndIncrementsOnFlush() async throws {
	let sink = RecordingNotificationSink()
	let coordinator = LSPDocumentSyncCoordinator(sink: sink, debounceMillis: 0)
	let url = URL(fileURLWithPath: "/tmp/itsy-sync-A.swift")
	try await coordinator.didOpen(url: url, languageID: "swift", content: "// a")
	await coordinator.didChange(url: url, content: "// b")
	await coordinator.didChange(url: url, content: "// c")
	let methods = await sink.recordedMethods()
	#expect(methods == [
		LSPMethod.textDocumentDidOpen,
		LSPMethod.textDocumentDidChange,
		LSPMethod.textDocumentDidChange,
	])
	let versions = await coordinator.recordedMessages.map(\.version)
	#expect(versions == [1, 2, 3])
}

@Test func lspDocumentSyncDebounceCoalescesRapidChanges() async throws {
	let sink = RecordingNotificationSink()
	let coordinator = LSPDocumentSyncCoordinator(sink: sink, debounceMillis: 60_000)
	let url = URL(fileURLWithPath: "/tmp/itsy-sync-B.swift")
	try await coordinator.didOpen(url: url, languageID: "swift", content: "v0")
	await coordinator.didChange(url: url, content: "v1")
	await coordinator.didChange(url: url, content: "v2")
	await coordinator.didChange(url: url, content: "v3")
	await coordinator.flushPendingChange(for: url)
	let messages = await coordinator.recordedMessages
	let changes = messages.filter { $0.method == LSPMethod.textDocumentDidChange }
	#expect(changes.count == 1)
	#expect(changes.first?.version == 2)
}

@Test func lspDocumentSyncEnforcesMonotonicVersionsAcrossOpenChangeClose() async throws {
	let sink = RecordingNotificationSink()
	let coordinator = LSPDocumentSyncCoordinator(sink: sink, debounceMillis: 0)
	let url = URL(fileURLWithPath: "/tmp/itsy-sync-C.swift")
	try await coordinator.didOpen(url: url, languageID: "swift", content: "open")
	await coordinator.didChange(url: url, content: "v1")
	await coordinator.didChange(url: url, content: "v2")
	try await coordinator.didClose(url: url)
	let versions = await coordinator.recordedMessages.compactMap(\.version)
	#expect(versions == [1, 2, 3])
	let methods = await coordinator.recordedMessages.map(\.method)
	#expect(methods.last == LSPMethod.textDocumentDidClose)
	#expect(await coordinator.currentVersion(for: url) == nil)
}

@Test func lspDocumentSyncIgnoresChangesBeforeOpen() async {
	let sink = RecordingNotificationSink()
	let coordinator = LSPDocumentSyncCoordinator(sink: sink, debounceMillis: 0)
	let url = URL(fileURLWithPath: "/tmp/itsy-sync-D.swift")
	await coordinator.didChange(url: url, content: "orphan")
	#expect(await sink.recordedMethods().isEmpty)
}

@Test func lspDocumentSyncFlushPendingChangeForcesEmission() async throws {
	let sink = RecordingNotificationSink()
	let coordinator = LSPDocumentSyncCoordinator(sink: sink, debounceMillis: 5_000)
	let url = URL(fileURLWithPath: "/tmp/itsy-sync-E.swift")
	try await coordinator.didOpen(url: url, languageID: "swift", content: "open")
	await coordinator.didChange(url: url, content: "after")
	await coordinator.flushPendingChange(for: url)
	let messages = await coordinator.recordedMessages
	#expect(messages.last?.method == LSPMethod.textDocumentDidChange)
	#expect(messages.last?.version == 2)
}

private actor RecordingNotificationSink: LSPNotificationSink {
	private var calls: [(method: String, params: LSPAny)] = []

	func send(method: String, params: LSPAny) async throws {
		calls.append((method, params))
	}

	func recordedMethods() -> [String] {
		calls.map(\.method)
	}
}
