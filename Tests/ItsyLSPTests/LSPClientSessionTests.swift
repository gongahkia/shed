import Foundation
import ItsyLSP
import Testing

@Test func clientInitializeSendsInitializeThenInitialized() async throws {
	let transport = RecordingLSPTransport()
	let session = LSPClientSession(transport: transport)
	let task = Task {
		try await session.initialize(LSPInitializeParams(processId: 42, rootUri: "file:///tmp/project"))
	}

	try await transport.waitForWriteCount(1)
	let initialize = try transport.message(at: 0)
	#expect(initialize == .request(JSONRPCRequestMessage(
		id: .int(1),
		method: LSPMethod.initialize,
		params: .object([
			"processId": .int(42),
			"rootUri": .string("file:///tmp/project"),
			"capabilities": .object([:]),
		])
	)))

	let result: LSPAny = .object(["capabilities": .object([:])])
	let events = try await session.receive(LSPMessageFramer.frame(message: .response(JSONRPCResponseMessage(id: .int(1), result: result))))
	#expect(events.isEmpty)
	#expect(try await task.value == result)
	try await transport.waitForWriteCount(2)
	#expect(try transport.message(at: 1) == .notification(JSONRPCNotificationMessage(method: LSPMethod.initialized, params: .object([:]))))
	#expect(await session.state == .running)
}

@Test func clientRoutesOutOfOrderResponsesToPendingRequests() async throws {
	let (session, transport) = try await initializedSession()
	let first = Task {
		try await session.sendRequest(method: LSPMethod.textDocumentHover).result
	}
	let second = Task {
		try await session.sendRequest(method: LSPMethod.textDocumentDefinition).result
	}

	try await transport.waitForWriteCount(4)
	let requests = [try transport.message(at: 2), try transport.message(at: 3)]
	let hoverID = try requestID(for: LSPMethod.textDocumentHover, in: requests)
	let definitionID = try requestID(for: LSPMethod.textDocumentDefinition, in: requests)
	_ = try await session.receive(LSPMessageFramer.frame(message: .response(JSONRPCResponseMessage(id: definitionID, result: .string("definition")))))
	_ = try await session.receive(LSPMessageFramer.frame(message: .response(JSONRPCResponseMessage(id: hoverID, result: .string("hover")))))

	#expect(try await first.value == .string("hover"))
	#expect(try await second.value == .string("definition"))
}

@Test func clientWorkspaceSymbolSendsTypedRequest() async throws {
	let (session, transport) = try await initializedSession()
	let task = Task {
		try await session.workspaceSymbol(query: "App").workspaceSymbols
	}

	try await transport.waitForWriteCount(3)
	#expect(try transport.message(at: 2) == .request(JSONRPCRequestMessage(
		id: .int(2),
		method: LSPMethod.workspaceSymbol,
		params: .object(["query": .string("App")])
	)))
	_ = try await session.receive(LSPMessageFramer.frame(message: .response(JSONRPCResponseMessage(
		id: .int(2),
		result: .array([
			.object([
				"name": .string("AppShell"),
				"kind": .int(23),
				"location": .object([
					"uri": .string("file:///tmp/App.swift"),
					"range": .object([
						"start": .object(["line": .int(0), "character": .int(7)]),
						"end": .object(["line": .int(0), "character": .int(15)]),
					]),
				]),
			]),
		])
	))))

	let symbols = try await task.value
	#expect(symbols.map(\.name) == ["AppShell"])
}

@Test func clientDocumentSymbolSendsTypedRequest() async throws {
	let (session, transport) = try await initializedSession()
	let task = Task {
		try await session.documentSymbol(textDocument: LSPTextDocumentIdentifier(uri: "file:///tmp/App.swift")).documentSymbols
	}

	try await transport.waitForWriteCount(3)
	#expect(try transport.message(at: 2) == .request(JSONRPCRequestMessage(
		id: .int(2),
		method: LSPMethod.textDocumentDocumentSymbol,
		params: .object(["textDocument": .object(["uri": .string("file:///tmp/App.swift")])])
	)))
	_ = try await session.receive(LSPMessageFramer.frame(message: .response(JSONRPCResponseMessage(
		id: .int(2),
		result: .array([
			.object([
				"name": .string("AppShell"),
				"kind": .int(23),
				"range": .object([
					"start": .object(["line": .int(0), "character": .int(0)]),
					"end": .object(["line": .int(1), "character": .int(1)]),
				]),
				"selectionRange": .object([
					"start": .object(["line": .int(0), "character": .int(7)]),
					"end": .object(["line": .int(0), "character": .int(15)]),
				]),
			]),
		])
	))))

	let symbols = try await task.value
	#expect(symbols.map(\.name) == ["AppShell"])
}

@Test func clientEditingActionsSendTypedRequests() async throws {
	let (session, transport) = try await initializedSession()
	let position = LSPPosition(line: 2, character: 4)
	let range = LSPRange(start: position, end: LSPPosition(line: 2, character: 8))
	let renameTask = Task {
		try await session.rename(uri: "file:///tmp/App.swift", position: position, newName: "renamed")
	}

	try await transport.waitForWriteCount(3)
	#expect(try transport.message(at: 2) == .request(JSONRPCRequestMessage(
		id: .int(2),
		method: LSPMethod.textDocumentRename,
		params: .object([
			"textDocument": .object(["uri": .string("file:///tmp/App.swift")]),
			"position": .object(["line": .int(2), "character": .int(4)]),
			"newName": .string("renamed"),
		])
	)))
	_ = try await session.receive(LSPMessageFramer.frame(message: .response(JSONRPCResponseMessage(
		id: .int(2),
		result: .object(["changes": .object(["file:///tmp/App.swift": .array([
			.object([
				"range": .object([
					"start": .object(["line": .int(2), "character": .int(4)]),
					"end": .object(["line": .int(2), "character": .int(8)]),
				]),
				"newText": .string("renamed"),
			]),
		])])])
	))))
	#expect(try await renameTask.value?.changes?["file:///tmp/App.swift"]?.first?.newText == "renamed")

	let formatTask = Task {
		try await session.formatRange(uri: "file:///tmp/App.swift", range: range, options: LSPFormattingOptions(tabSize: 2, insertSpaces: true))
	}
	try await transport.waitForWriteCount(4)
	#expect(try transport.message(at: 3) == .request(JSONRPCRequestMessage(
		id: .int(3),
		method: LSPMethod.textDocumentRangeFormatting,
		params: .object([
			"textDocument": .object(["uri": .string("file:///tmp/App.swift")]),
			"range": .object([
				"start": .object(["line": .int(2), "character": .int(4)]),
				"end": .object(["line": .int(2), "character": .int(8)]),
			]),
			"options": .object(["tabSize": .int(2), "insertSpaces": .bool(true)]),
		])
	)))
	_ = try await session.receive(LSPMessageFramer.frame(message: .response(JSONRPCResponseMessage(
		id: .int(3),
		result: .array([.object([
			"range": .object([
				"start": .object(["line": .int(0), "character": .int(0)]),
				"end": .object(["line": .int(0), "character": .int(0)]),
			]),
			"newText": .string("\t"),
		])])
	))))
	#expect(try await formatTask.value.first?.newText == "\t")

	let actionsTask = Task {
		try await session.codeActions(uri: "file:///tmp/App.swift", range: range, context: LSPCodeActionContext(diagnostics: []))
	}
	try await transport.waitForWriteCount(5)
	#expect(try transport.message(at: 4) == .request(JSONRPCRequestMessage(
		id: .int(4),
		method: LSPMethod.textDocumentCodeAction,
		params: .object([
			"textDocument": .object(["uri": .string("file:///tmp/App.swift")]),
			"range": .object([
				"start": .object(["line": .int(2), "character": .int(4)]),
				"end": .object(["line": .int(2), "character": .int(8)]),
			]),
			"context": .object(["diagnostics": .array([])]),
		])
	)))
	_ = try await session.receive(LSPMessageFramer.frame(message: .response(JSONRPCResponseMessage(
		id: .int(4),
		result: .array([.object(["title": .string("Fix"), "kind": .string("quickfix")])])
	))))
	#expect(try await actionsTask.value.entries.map(\.title) == ["Fix"])

	let prepareTask = Task {
		try await session.prepareRename(uri: "file:///tmp/App.swift", position: position)
	}
	try await transport.waitForWriteCount(6)
	#expect(try transport.message(at: 5) == .request(JSONRPCRequestMessage(
		id: .int(5),
		method: LSPMethod.textDocumentPrepareRename,
		params: .object([
			"textDocument": .object(["uri": .string("file:///tmp/App.swift")]),
			"position": .object(["line": .int(2), "character": .int(4)]),
		])
	)))
	_ = try await session.receive(LSPMessageFramer.frame(message: .response(JSONRPCResponseMessage(
		id: .int(5),
		result: .object([
			"range": .object([
				"start": .object(["line": .int(2), "character": .int(4)]),
				"end": .object(["line": .int(2), "character": .int(8)]),
			]),
			"placeholder": .string("name"),
		])
	))))
	#expect(try await prepareTask.value.placeholder == "name")

	let documentFormatTask = Task {
		try await session.formatDocument(uri: "file:///tmp/App.swift", options: LSPFormattingOptions(tabSize: 4, insertSpaces: false))
	}
	try await transport.waitForWriteCount(7)
	#expect(try transport.message(at: 6) == .request(JSONRPCRequestMessage(
		id: .int(6),
		method: LSPMethod.textDocumentFormatting,
		params: .object([
			"textDocument": .object(["uri": .string("file:///tmp/App.swift")]),
			"options": .object(["tabSize": .int(4), "insertSpaces": .bool(false)]),
		])
	)))
	_ = try await session.receive(LSPMessageFramer.frame(message: .response(JSONRPCResponseMessage(
		id: .int(6),
		result: .array([.object([
			"range": .object([
				"start": .object(["line": .int(0), "character": .int(0)]),
				"end": .object(["line": .int(0), "character": .int(0)]),
			]),
			"newText": .string(" "),
		])])
	))))
	#expect(try await documentFormatTask.value.first?.newText == " ")

	let resolveTask = Task {
		try await session.resolveCodeAction(LSPCodeAction(title: "Resolve", data: .int(7)))
	}
	try await transport.waitForWriteCount(8)
	#expect(try transport.message(at: 7) == .request(JSONRPCRequestMessage(
		id: .int(7),
		method: LSPMethod.codeActionResolve,
		params: .object(["title": .string("Resolve"), "data": .int(7)])
	)))
	_ = try await session.receive(LSPMessageFramer.frame(message: .response(JSONRPCResponseMessage(
		id: .int(7),
		result: .object(["title": .string("Resolved"), "kind": .string("quickfix")])
	))))
	#expect(try await resolveTask.value.title == "Resolved")

	let executeTask = Task {
		try await session.executeCommand(LSPCommand(title: "Run", command: "swift.run", arguments: [.string("a")]))
	}
	try await transport.waitForWriteCount(9)
	#expect(try transport.message(at: 8) == .request(JSONRPCRequestMessage(
		id: .int(8),
		method: LSPMethod.workspaceExecuteCommand,
		params: .object(["command": .string("swift.run"), "arguments": .array([.string("a")])])
	)))
	_ = try await session.receive(LSPMessageFramer.frame(message: .response(JSONRPCResponseMessage(id: .int(8), result: .null))))
	#expect(try await executeTask.value == .null)
}

@Test func clientReturnsServerNotificationsAsEvents() async throws {
	let (session, _) = try await initializedSession()
	let diagnostics = JSONRPCMessage.notification(JSONRPCNotificationMessage(
		method: LSPMethod.textDocumentPublishDiagnostics,
		params: .object([
			"uri": .string("file:///tmp/main.swift"),
			"diagnostics": .array([]),
		])
	))

	let events = try await session.receive(LSPMessageFramer.frame(message: diagnostics))

	#expect(events == [.notification(JSONRPCNotificationMessage(method: LSPMethod.textDocumentPublishDiagnostics, params: .object([
		"uri": .string("file:///tmp/main.swift"),
		"diagnostics": .array([]),
	])))])
}

@Test func clientRoutesResponseErrorsToAwaitingRequest() async throws {
	let (session, transport) = try await initializedSession()
	let task = Task {
		try await session.sendRequest(method: LSPMethod.textDocumentHover)
	}

	try await transport.waitForWriteCount(3)
	_ = try await session.receive(LSPMessageFramer.frame(message: .response(JSONRPCResponseMessage(
		id: .int(2),
		error: JSONRPCError(code: JSONRPCErrorCode.invalidParams, message: "bad params")
	))))
	var thrown: LSPClientError?
	do {
		_ = try await task.value
	} catch let error as LSPClientError {
		thrown = error
	}

	#expect(thrown == .responseError(JSONRPCError(code: JSONRPCErrorCode.invalidParams, message: "bad params")))
}

@Test func clientRejectsRequestsBeforeInitialize() async throws {
	let session = LSPClientSession(transport: RecordingLSPTransport())
	var thrown: LSPClientError?

	do {
		_ = try await session.sendRequest(method: LSPMethod.textDocumentHover)
	} catch let error as LSPClientError {
		thrown = error
	}

	#expect(thrown == .invalidState(expected: [.running], actual: .idle))
}

@Test func clientShutdownSendsShutdownThenExit() async throws {
	let (session, transport) = try await initializedSession()
	let task = Task {
		try await session.shutdown()
	}

	try await transport.waitForWriteCount(3)
	#expect(try transport.message(at: 2) == .request(JSONRPCRequestMessage(id: .int(2), method: LSPMethod.shutdown)))
	_ = try await session.receive(LSPMessageFramer.frame(message: .response(JSONRPCResponseMessage(id: .int(2), result: .null))))
	try await task.value
	try await transport.waitForWriteCount(4)
	#expect(try transport.message(at: 3) == .notification(JSONRPCNotificationMessage(method: LSPMethod.exit)))
	#expect(await session.state == .exited)
}

private func initializedSession() async throws -> (LSPClientSession, RecordingLSPTransport) {
	let transport = RecordingLSPTransport()
	let session = LSPClientSession(transport: transport)
	let task = Task {
		try await session.initialize(LSPInitializeParams(processId: nil, rootUri: "file:///tmp/project"))
	}
	try await transport.waitForWriteCount(1)
	_ = try await session.receive(LSPMessageFramer.frame(message: .response(JSONRPCResponseMessage(id: .int(1), result: .object(["capabilities": .object([:])])))))
	_ = try await task.value
	try await transport.waitForWriteCount(2)
	return (session, transport)
}

private enum RecordingTransportError: Error {
	case timeout(expected: Int, actual: Int)
	case missingWrite(Int)
	case invalidFrame(Int)
	case missingRequest(String)
}

private func requestID(for method: String, in messages: [JSONRPCMessage]) throws -> JSONRPCID {
	for message in messages {
		if case let .request(request) = message, request.method == method {
			return request.id
		}
	}
	throw RecordingTransportError.missingRequest(method)
}

private final class RecordingLSPTransport: LSPClientTransport, @unchecked Sendable {
	private let lock = NSLock()
	private var writes: [Data] = []

	func write(_ data: Data) throws {
		lock.lock()
		writes.append(data)
		lock.unlock()
	}

	func waitForWriteCount(_ expected: Int) async throws {
		for _ in 0 ..< 100 {
			if writeCount >= expected {
				return
			}
			try await Task.sleep(nanoseconds: 1_000_000)
		}
		throw RecordingTransportError.timeout(expected: expected, actual: writeCount)
	}

	func message(at index: Int) throws -> JSONRPCMessage {
		guard let data = write(at: index) else {
			throw RecordingTransportError.missingWrite(index)
		}
		var framer = LSPMessageFramer()
		let payloads = try framer.append(data)
		guard payloads.count == 1 else {
			throw RecordingTransportError.invalidFrame(index)
		}
		return try JSONDecoder().decode(JSONRPCMessage.self, from: payloads[0])
	}

	private var writeCount: Int {
		lock.lock()
		let count = writes.count
		lock.unlock()
		return count
	}

	private func write(at index: Int) -> Data? {
		lock.lock()
		let data = writes.indices.contains(index) ? writes[index] : nil
		lock.unlock()
		return data
	}
}
