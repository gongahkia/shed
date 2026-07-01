import Foundation
import ItsyEditor
import ItsyLSP
import Testing

@Test func lspSessionSupervisorClearsOwnedDiagnosticsOnCrash() async throws {
	let root = URL(fileURLWithPath: "/tmp/itsy-supervisor")
	let uri = "file:///tmp/itsy-supervisor/Sources/App.swift"
	let source = ManualLSPClientEvents()
	let supervisor = LSPSessionSupervisor(
		key: LSPSessionKey(languageID: "swift", workspaceRoot: root),
		events: source.stream
	)
	let collector = SupervisorEventCollector()
	let eventTask = Task {
		for await event in supervisor.events {
			await collector.append(event)
		}
	}
	defer {
		eventTask.cancel()
		source.finish()
	}

	await supervisor.start()
	await supervisor.recordOwnedURI(uri)
	source.yield(.server(.notification(JSONRPCNotificationMessage(
		method: LSPMethod.textDocumentPublishDiagnostics,
		params: try LSPAny(encoding: LSPPublishDiagnosticsParams(uri: uri, diagnostics: [
			LSPDiagnostic(
				range: LSPRange(start: LSPPosition(line: 0, character: 1), end: LSPPosition(line: 0, character: 2)),
				severity: .error,
				message: "boom"
			),
		]))
	))))
	source.yield(.stderr(Data("compiler crashed\n".utf8)))
	source.yield(.terminated(9))

	let events = try await collector.waitForCount(3)
	guard case let .diagnosticsUpdated(first) = events[0] else {
		Issue.record("expected diagnosticsUpdated")
		return
	}
	#expect(first.problems.count == 1)
	guard case let .diagnosticsUpdated(cleared) = events[1] else {
		Issue.record("expected diagnosticsUpdated")
		return
	}
	#expect(cleared.problems.isEmpty)
	#expect(events[2] == .sessionFailed(reason: LSPSessionFailureReason(status: 9, stderrTail: "compiler crashed\n")))
}

@Test func lspSessionSupervisorLimitsStderrTail() async throws {
	let source = ManualLSPClientEvents()
	let supervisor = LSPSessionSupervisor(
		key: LSPSessionKey(languageID: "swift", workspaceRoot: URL(fileURLWithPath: "/tmp/itsy-supervisor")),
		events: source.stream
	)
	let collector = SupervisorEventCollector()
	let eventTask = Task {
		for await event in supervisor.events {
			await collector.append(event)
		}
	}
	defer {
		eventTask.cancel()
		source.finish()
	}

	await supervisor.start()
	source.yield(.stderr(Data(String(repeating: "a", count: LSPSessionSupervisor.stderrTailLimit + 20).utf8)))
	source.yield(.stderr(Data("tail".utf8)))
	source.yield(.terminated(7))

	let events = try await collector.waitForCount(2)
	guard case let .sessionFailed(reason) = events[1] else {
		Issue.record("expected sessionFailed")
		return
	}
	#expect(reason.status == 7)
	#expect(reason.stderrTail.count == LSPSessionSupervisor.stderrTailLimit)
	#expect(reason.stderrTail.hasSuffix("tail"))
}

private final class ManualLSPClientEvents: @unchecked Sendable {
	let stream: AsyncStream<LSPProcessClientEvent>
	private let continuation: AsyncStream<LSPProcessClientEvent>.Continuation

	init() {
		var capturedContinuation: AsyncStream<LSPProcessClientEvent>.Continuation?
		stream = AsyncStream { continuation in
			capturedContinuation = continuation
		}
		continuation = capturedContinuation!
	}

	func yield(_ event: LSPProcessClientEvent) {
		continuation.yield(event)
	}

	func finish() {
		continuation.finish()
	}
}

private actor SupervisorEventCollector {
	private var events: [LSPSessionSupervisorEvent] = []

	func append(_ event: LSPSessionSupervisorEvent) {
		events.append(event)
	}

	func waitForCount(_ count: Int) async throws -> [LSPSessionSupervisorEvent] {
		for _ in 0 ..< 200 {
			if events.count >= count {
				return Array(events.prefix(count))
			}
			try await Task.sleep(nanoseconds: 1_000_000)
		}
		throw SupervisorEventCollectorError.timeout(expected: count, actual: events.count)
	}
}

private enum SupervisorEventCollectorError: Error {
	case timeout(expected: Int, actual: Int)
}
