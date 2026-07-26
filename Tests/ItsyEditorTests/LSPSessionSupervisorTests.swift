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

	let events = try await collector.waitForCount(4)
	guard case let .diagnosticsUpdated(first) = events[0] else {
		Issue.record("expected diagnosticsUpdated")
		return
	}
	#expect(first.problems.count == 1)
	guard case let .output(output) = events[1] else {
		Issue.record("expected output")
		return
	}
	#expect(output.kind == .process)
	#expect(output.text == "compiler crashed\n")
	guard case let .diagnosticsUpdated(cleared) = events[2] else {
		Issue.record("expected diagnosticsUpdated")
		return
	}
	#expect(cleared.problems.isEmpty)
	#expect(events[3] == .sessionFailed(reason: LSPSessionFailureReason(status: 9, stderrTail: "compiler crashed\n")))
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

	let events = try await collector.waitForCount(3)
	guard case let .sessionFailed(reason) = events[2] else {
		Issue.record("expected sessionFailed")
		return
	}
	#expect(reason.status == 7)
	#expect(reason.stderrTail.count == LSPSessionSupervisor.stderrTailLimit)
	#expect(reason.stderrTail.hasSuffix("tail"))
}

@Test func lspSessionSupervisorRedactsSensitiveProcessAndProtocolOutput() async throws {
	let source = ManualLSPClientEvents()
	let supervisor = LSPSessionSupervisor(
		key: LSPSessionKey(languageID: "swift", workspaceRoot: URL(fileURLWithPath: "/tmp/itsy-supervisor")),
		events: source.stream,
		environment: ["API_TOKEN": "top-secret", "SAFE": "visible"]
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
	source.yield(.stderr(Data("API_TOKEN=top-".utf8)))
	source.yield(.stderr(Data("secret Authorization: Bearer top-secret\n".utf8)))
	source.yield(.failure("protocol payload {\"token\":\"top-secret\"}\n"))
	let events = try await collector.waitForCount(2)
	let output = events.compactMap { event -> LSPSessionOutput? in
		guard case let .output(output) = event else {
			return nil
		}
		return output
	}
	#expect(output.map(\.kind) == [.process, .protocolOutput])
	let rendered = output.map(\.text).joined()
	#expect(!rendered.contains("top-secret"))
	#expect(rendered.contains("<redacted>"))
}

@Test func lspLogRedactionStreamPrioritizesOverlappingSecrets() {
	var redactor = LSPLogRedactor.Stream(environment: ["API_TOKEN": "top-secret", "AUTH_TOKEN": "top"])
	let output = [redactor.append("token=top-"), redactor.append("secret")].joined()
		+ redactor.finish()

	#expect(!output.contains("top-secret"))
	#expect(!output.contains("top"))
	#expect(output == "token=<redacted>")
}

@Test func lspLogRedactionStreamRedactsChunkedBearerCredentials() {
	var redactor = LSPLogRedactor.Stream(environment: [:])
	let output = redactor.append("Authorization: Bearer top-")
		+ redactor.append("secret\n")
		+ redactor.finish()

	#expect(!output.contains("top-secret"))
	#expect(output == "Authorization: Bearer <redacted>\n")
}

@Test func lspSessionSupervisorTreatsUnexpectedZeroExitAsFailure() async throws {
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
	source.yield(.terminated(0))
	let events = try await collector.waitForCount(2)
	#expect(events[1] == .sessionFailed(reason: LSPSessionFailureReason(status: 0, stderrTail: "")))
}

@Test func lspSessionSupervisorIgnoresRepeatedTerminationEvents() async throws {
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
	source.yield(.terminated(9))
	source.yield(.terminated(10))
	let events = try await collector.waitForCount(2)
	#expect(events == [
		.diagnosticsUpdated(WorkspaceProblemSnapshot(root: URL(fileURLWithPath: "/tmp/itsy-supervisor"), problems: [])),
		.sessionFailed(reason: LSPSessionFailureReason(status: 9, stderrTail: "")),
	])
}

@Test func lspSessionSupervisorRejectsStaleDiagnosticsAndClearsReloadedDocuments() async throws {
	let root = URL(fileURLWithPath: "/tmp/itsy-supervisor")
	let uri = "file:///tmp/itsy-supervisor/App.swift"
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
	await supervisor.recordDocumentVersion(2, forURI: uri)
	for version in [1, 2] {
		source.yield(.server(.notification(JSONRPCNotificationMessage(
			method: LSPMethod.textDocumentPublishDiagnostics,
			params: try LSPAny(encoding: LSPPublishDiagnosticsParams(
				uri: uri,
				version: version,
				diagnostics: [LSPDiagnostic(
					range: LSPRange(start: LSPPosition(line: version, character: 0), end: LSPPosition(line: version, character: 1)),
					severity: .error,
					message: "v\(version)"
				)]
			))
		))))
	}
	let accepted = try await collector.waitForCount(1)
	guard case let .diagnosticsUpdated(snapshot) = accepted[0] else {
		Issue.record("expected diagnosticsUpdated")
		return
	}
	#expect(snapshot.problems.map(\.message) == ["v2"])
	await supervisor.clearDiagnostics(forURI: uri)
	let cleared = try await collector.waitForCount(2)
	guard case let .diagnosticsUpdated(snapshot) = cleared[1] else {
		Issue.record("expected cleared diagnosticsUpdated")
		return
	}
	#expect(snapshot.problems.isEmpty)
}

@Test func lspSessionSupervisorEmitsWorkspaceApplyEditRequests() async throws {
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

	let params = LSPApplyWorkspaceEditParams(edit: LSPWorkspaceEdit(changes: [
		"file:///tmp/itsy-supervisor/App.swift": [
			LSPTextEdit(
				range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 0)),
				newText: "import Foundation\n"
			),
		],
	]))
	await supervisor.start()
	source.yield(.server(.request(JSONRPCRequestMessage(
		id: .int(7),
		method: LSPMethod.workspaceApplyEdit,
		params: try LSPAny(encoding: params)
	))))

	let events = try await collector.waitForCount(1)
	#expect(events == [.workspaceEditRequested(id: .int(7), params: params)])
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
