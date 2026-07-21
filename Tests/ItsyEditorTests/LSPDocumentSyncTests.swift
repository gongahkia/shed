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

@Test func lspDocumentSyncDidSaveFlushesPendingChange() async throws {
	let sink = RecordingNotificationSink()
	let coordinator = LSPDocumentSyncCoordinator(sink: sink, debounceMillis: 60_000)
	let url = URL(fileURLWithPath: "/tmp/itsy-sync-F.swift")
	try await coordinator.didOpen(url: url, languageID: "swift", content: "open")
	await coordinator.didChange(url: url, content: "saved")
	try await coordinator.didSave(url: url, text: "saved")
	let messages = await coordinator.recordedMessages
	#expect(messages.map(\.method) == [
		LSPMethod.textDocumentDidOpen,
		LSPMethod.textDocumentDidChange,
		LSPMethod.textDocumentDidSave,
	])
	#expect(messages.map(\.version) == [1, 2, 2])
	let calls = await sink.recordedCalls()
	#expect(calls.last?.method == LSPMethod.textDocumentDidSave)
	#expect(calls.last?.params == .object([
		"textDocument": .object(["uri": .string(url.standardizedFileURL.absoluteString)]),
		"text": .string("saved"),
	]))
}

@Test func lspDocumentSyncReplaysIncrementalUnicodeMultiCursorUndoReloadAndLargeEdits() async throws {
	let sink = RecordingNotificationSink()
	let coordinator = LSPDocumentSyncCoordinator(sink: sink, debounceMillis: 0)
	let url = URL(fileURLWithPath: "/tmp/itsy-sync-fixture.swift")
	let initial = "let café = \"🧪\"\nalpha\nbeta\n"
	let fixtures = [
		"let café = \"🧪!\"\nALPHA\nbeta\n",
		initial,
		"// reloaded\nlet café = \"🧪\"\nalpha\nbeta\n",
	]
	try await coordinator.didOpen(url: url, languageID: "swift", content: initial)
	var reference = initial
	for (index, content) in fixtures.enumerated() {
		await coordinator.didChange(url: url, content: content)
		let call = try #require(await sink.recordedCalls().last)
		let params = try decodeChangeParams(call.params)
		let change = try #require(params.contentChanges.first)
		let range = try #require(change.range)
		#expect(params.textDocument.version == index + 2)
		#expect(change.rangeLength != nil)
		reference = try LSPTextEditApply.apply([LSPTextEdit(range: range, newText: change.text)], to: reference)
		#expect(reference == content)
	}

	let largeURL = URL(fileURLWithPath: "/tmp/itsy-sync-large.swift")
	let large = "prefix\n" + String(repeating: "a", count: 128 * 1024) + "\nsuffix\n"
	let replacementOffset = large.index(large.startIndex, offsetBy: 64 * 1024)
	let largeEdited = String(large[..<replacementOffset]) + "Z" + String(large[large.index(after: replacementOffset)...])
	try await coordinator.didOpen(url: largeURL, languageID: "swift", content: large)
	await coordinator.didChange(url: largeURL, content: largeEdited)
	let largeCall = try #require(await sink.recordedCalls().last)
	let largeParams = try decodeChangeParams(largeCall.params)
	let largeChange = try #require(largeParams.contentChanges.first)
	#expect(largeChange.range != nil)
	#expect(largeChange.text == "Z")
	#expect(largeChange.rangeLength == 1)
}

@Test func lspDocumentSyncSerializesChangesArrivingDuringAnInFlightNotification() async throws {
	let sink = DelayedNotificationSink()
	let coordinator = LSPDocumentSyncCoordinator(sink: sink, debounceMillis: 0)
	let url = URL(fileURLWithPath: "/tmp/itsy-sync-race.swift")
	try await coordinator.didOpen(url: url, languageID: "swift", content: "zero")
	let firstChange = Task {
		await coordinator.didChange(url: url, content: "one")
	}
	try await sink.waitForChangeStart()
	await coordinator.didChange(url: url, content: "two")
	await firstChange.value
	let recordedCalls = await sink.recordedCalls()
	let calls = recordedCalls.filter { $0.method == LSPMethod.textDocumentDidChange }
	let params = try calls.map { try decodeChangeParams($0.params) }
	#expect(params.map(\.textDocument.version) == [2, 3])
	var reference = "zero"
	for param in params {
		let change = try #require(param.contentChanges.first)
		let range = try #require(change.range)
		reference = try LSPTextEditApply.apply([LSPTextEdit(range: range, newText: change.text)], to: reference)
	}
	#expect(reference == "two")
}

private actor RecordingNotificationSink: LSPNotificationSink {
	private var calls: [(method: String, params: LSPAny)] = []

	func send(method: String, params: LSPAny) async throws {
		calls.append((method, params))
	}

	func recordedMethods() -> [String] {
		calls.map(\.method)
	}

	func recordedCalls() -> [(method: String, params: LSPAny)] {
		calls
	}
}

private func decodeChangeParams(_ value: LSPAny) throws -> LSPDidChangeTextDocumentParams {
	try JSONDecoder().decode(LSPDidChangeTextDocumentParams.self, from: JSONEncoder().encode(value))
}

private actor DelayedNotificationSink: LSPNotificationSink {
	private var calls: [(method: String, params: LSPAny)] = []
	private var changeStarted = false

	func send(method: String, params: LSPAny) async throws {
		calls.append((method, params))
		if method == LSPMethod.textDocumentDidChange {
			changeStarted = true
			try await Task.sleep(nanoseconds: 20_000_000)
		}
	}

	func waitForChangeStart() async throws {
		for _ in 0 ..< 200 {
			if changeStarted {
				return
			}
			try await Task.sleep(nanoseconds: 1_000_000)
		}
		throw DelayedNotificationSinkError.timeout
	}

	func recordedCalls() -> [(method: String, params: LSPAny)] {
		calls
	}
}

private enum DelayedNotificationSinkError: Error {
	case timeout
}
