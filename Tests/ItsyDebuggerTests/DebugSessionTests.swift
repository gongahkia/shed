import Foundation
import ItsyDAP
import ItsyDebugger
import Testing

@Test func debugSessionRefreshThreadsStoresFocus() async throws {
	let (debug, client, transport) = makeDebugSession()
	let task = Task {
		try await debug.refreshThreads()
	}

	try await transport.waitForWriteCount(1)
	#expect(try transport.request(at: 0) == DAPRequestMessage(seq: 1, command: DAPCommand.threads))
	try await respond(client, request: try transport.request(at: 0), body: DAPThreadsResponseBody(threads: [
		DAPThread(id: 11, name: "main"),
		DAPThread(id: 12, name: "worker"),
	]))

	let threads = try await task.value
	#expect(threads == [DebugThread(id: 11, name: "main"), DebugThread(id: 12, name: "worker")])
	#expect(await debug.threads == threads)
	#expect(await debug.focusedThreadID == 11)
}

@Test func debugSessionFetchesStackScopesVariablesAndEvaluate() async throws {
	let (debug, client, transport) = makeDebugSession()
	let stackTask = Task {
		try await debug.stackFrames(for: 11)
	}

	try await transport.waitForWriteCount(1)
	let stackRequest = try transport.request(at: 0)
	#expect(stackRequest.command == DAPCommand.stackTrace)
	#expect(stackRequest.arguments == .object(["threadId": .int(11)]))
	try await respond(client, request: stackRequest, body: DAPStackTraceResponseBody(stackFrames: [
		DAPStackFrame(id: 99, name: "main", source: DAPSource(name: "main.swift", path: "/tmp/main.swift"), line: 7, column: 3),
	]))

	let frames = try await stackTask.value
	#expect(frames == [DebugStackFrame(id: 99, name: "main", sourceName: "main.swift", sourcePath: "/tmp/main.swift", line: 7, column: 3)])
	#expect(await debug.focusedThreadID == 11)
	#expect(await debug.focusedFrameID == 99)

	let scopesTask = Task {
		try await debug.scopes(for: 99)
	}
	try await transport.waitForWriteCount(2)
	let scopesRequest = try transport.request(at: 1)
	#expect(scopesRequest.command == DAPCommand.scopes)
	#expect(scopesRequest.arguments == .object(["frameId": .int(99)]))
	try await respond(client, request: scopesRequest, body: DAPScopesResponseBody(scopes: [
		DAPScope(name: "Locals", variablesReference: 300, expensive: false, namedVariables: 2),
	]))
	#expect(try await scopesTask.value == [DebugScope(name: "Locals", variablesReference: 300, expensive: false, namedVariables: 2)])

	let variablesTask = Task {
		try await debug.variables(for: 300)
	}
	try await transport.waitForWriteCount(3)
	let variablesRequest = try transport.request(at: 2)
	#expect(variablesRequest.command == DAPCommand.variables)
	#expect(variablesRequest.arguments == .object(["variablesReference": .int(300)]))
	try await respond(client, request: variablesRequest, body: DAPVariablesResponseBody(variables: [
		DAPVariable(name: "value", value: "42", type: "Int", variablesReference: 0),
	]))
	#expect(try await variablesTask.value == [DebugVariable(name: "value", value: "42", type: "Int", variablesReference: 0)])

	let evaluateTask = Task {
		try await debug.evaluate(expression: "value", frameID: 99, context: "watch")
	}
	try await transport.waitForWriteCount(4)
	let evaluateRequest = try transport.request(at: 3)
	#expect(evaluateRequest.command == DAPCommand.evaluate)
	#expect(evaluateRequest.arguments == .object([
		"context": .string("watch"),
		"expression": .string("value"),
		"frameId": .int(99),
	]))
	try await respond(client, request: evaluateRequest, body: DAPEvaluateResponseBody(result: "42", type: "Int", variablesReference: 0))
	#expect(try await evaluateTask.value == DebugValue(result: "42", type: "Int", variablesReference: 0))
}

private func makeDebugSession() -> (DebugSession, DAPClientSession, RecordingDAPTransport) {
	let transport = RecordingDAPTransport()
	let client = DAPClientSession(transport: transport)
	let debug = DebugSession(client: client)
	return (debug, client, transport)
}

private func respond<Value: Encodable>(_ client: DAPClientSession, request: DAPRequestMessage, body: Value) async throws {
	_ = try await client.receive(DAPMessageFramer.frame(message: .response(DAPResponseMessage(
		seq: request.seq + 100,
		requestSeq: request.seq,
		success: true,
		command: request.command,
		body: try DAPAny(encoding: body)
	))))
}

private enum RecordingDAPTransportError: Error {
	case timeout(expected: Int, actual: Int)
	case missingWrite(Int)
	case invalidFrame(Int)
}

private final class RecordingDAPTransport: DAPClientTransport, @unchecked Sendable {
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
		throw RecordingDAPTransportError.timeout(expected: expected, actual: writeCount)
	}

	func request(at index: Int) throws -> DAPRequestMessage {
		guard let data = write(at: index) else {
			throw RecordingDAPTransportError.missingWrite(index)
		}
		var framer = DAPMessageFramer()
		let payloads = try framer.append(data)
		guard payloads.count == 1 else {
			throw RecordingDAPTransportError.invalidFrame(index)
		}
		let message = try JSONDecoder().decode(DAPMessage.self, from: payloads[0])
		if case let .request(request) = message {
			return request
		}
		throw RecordingDAPTransportError.invalidFrame(index)
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
