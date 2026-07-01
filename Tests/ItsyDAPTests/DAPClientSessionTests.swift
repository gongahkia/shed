import Foundation
import ItsyDAP
import Testing

@Test func dapClientSessionSendsRequestAndRoutesResponse() async throws {
	let transport = RecordingDAPTransport()
	let session = DAPClientSession(transport: transport)
	let task = Task {
		try await session.sendRequest(command: DAPCommand.initialize, arguments: .object(["adapterID": .string("lldb")]))
	}

	try await transport.waitForWriteCount(1)
	#expect(try transport.message(at: 0) == .request(DAPRequestMessage(
		seq: 1,
		command: DAPCommand.initialize,
		arguments: .object(["adapterID": .string("lldb")])
	)))
	_ = try await session.receive(DAPMessageFramer.frame(message: .response(DAPResponseMessage(
		seq: 2,
		requestSeq: 1,
		success: true,
		command: DAPCommand.initialize,
		body: .object(["supportsConfigurationDoneRequest": .bool(true)])
	))))

	let response = try await task.value
	#expect(response.requestSeq == 1)
	#expect(response.command == DAPCommand.initialize)
	#expect(response.body == .object(["supportsConfigurationDoneRequest": .bool(true)]))
}

@Test func dapClientSessionRoutesOutOfOrderResponses() async throws {
	let transport = RecordingDAPTransport()
	let session = DAPClientSession(transport: transport)
	let first = Task {
		try await session.sendRequest(command: DAPCommand.threads).body
	}
	let second = Task {
		try await session.sendRequest(command: DAPCommand.stackTrace).body
	}

	try await transport.waitForWriteCount(2)
	let requests = [try transport.message(at: 0), try transport.message(at: 1)]
	let threadsSeq = try requestSeq(for: DAPCommand.threads, in: requests)
	let stackTraceSeq = try requestSeq(for: DAPCommand.stackTrace, in: requests)
	_ = try await session.receive(DAPMessageFramer.frame(message: .response(DAPResponseMessage(
		seq: 3,
		requestSeq: stackTraceSeq,
		success: true,
		command: DAPCommand.stackTrace,
		body: .object(["totalFrames": .int(1)])
	))))
	_ = try await session.receive(DAPMessageFramer.frame(message: .response(DAPResponseMessage(
		seq: 4,
		requestSeq: threadsSeq,
		success: true,
		command: DAPCommand.threads,
		body: .object(["threads": .array([])])
	))))

	#expect(try await first.value == .object(["threads": .array([])]))
	#expect(try await second.value == .object(["totalFrames": .int(1)]))
}

@Test func dapClientSessionRoutesFailureResponseToAwaitingRequest() async throws {
	let transport = RecordingDAPTransport()
	let session = DAPClientSession(transport: transport)
	let task = Task {
		try await session.sendRequest(command: DAPCommand.launch)
	}

	try await transport.waitForWriteCount(1)
	let failure = DAPResponseMessage(seq: 2, requestSeq: 1, success: false, command: DAPCommand.launch, message: "launch failed")
	_ = try await session.receive(DAPMessageFramer.frame(message: .response(failure)))
	var thrown: DAPClientError?
	do {
		_ = try await task.value
	} catch let error as DAPClientError {
		thrown = error
	}

	#expect(thrown == .responseFailure(failure))
}

@Test func dapClientSessionRejectsUnexpectedResponseSeq() async throws {
	let session = DAPClientSession(transport: RecordingDAPTransport())
	var thrown: DAPClientError?

	do {
		_ = try await session.receive(DAPMessageFramer.frame(message: .response(DAPResponseMessage(
			seq: 1,
			requestSeq: 99,
			success: true,
			command: DAPCommand.threads
		))))
	} catch let error as DAPClientError {
		thrown = error
	}

	#expect(thrown == .unexpectedResponseSeq(99))
}

@Test func dapClientSessionPublishesServerEventsToFilteredStream() async throws {
	let session = DAPClientSession(transport: RecordingDAPTransport())
	let stoppedEvents = await session.on(event: DAPEvent.stopped)
	let task = Task {
		try await firstDAPEvent(in: stoppedEvents)
	}

	_ = try await session.receive(DAPMessageFramer.frame(message: .event(DAPEventMessage(seq: 1, event: DAPEvent.initialized))))
	_ = try await session.receive(DAPMessageFramer.frame(message: .event(DAPEventMessage(
		seq: 2,
		event: DAPEvent.stopped,
		body: .object(["reason": .string("breakpoint")])
	))))

	let event = try await task.value
	#expect(event == DAPEventMessage(seq: 2, event: DAPEvent.stopped, body: .object(["reason": .string("breakpoint")])))
}

@Test func dapClientSessionReturnsServerRequestsAsEvents() async throws {
	let session = DAPClientSession(transport: RecordingDAPTransport())
	let request = DAPRequestMessage(seq: 1, command: "runInTerminal", arguments: .object(["kind": .string("integrated")]))

	let events = try await session.receive(DAPMessageFramer.frame(message: .request(request)))

	#expect(events == [.request(request)])
}

@Test func dapClientSessionInitializeTransitionsToConfiguringAfterInitializedEvent() async throws {
	let transport = RecordingDAPTransport()
	let session = DAPClientSession(transport: transport)
	let task = Task {
		try await session.initialize(clientCapabilities: DAPInitializeRequestArguments(adapterID: "lldb"))
	}

	try await transport.waitForWriteCount(1)
	#expect(await session.state == .initializing)
	#expect(try transport.message(at: 0) == .request(DAPRequestMessage(
		seq: 1,
		command: DAPCommand.initialize,
		arguments: .object(["adapterID": .string("lldb")])
	)))
	_ = try await session.receive(DAPMessageFramer.frame(message: .response(DAPResponseMessage(
		seq: 2,
		requestSeq: 1,
		success: true,
		command: DAPCommand.initialize
	))))
	_ = try await task.value
	#expect(await session.state == .initializing)

	_ = try await session.receive(DAPMessageFramer.frame(message: .event(DAPEventMessage(seq: 3, event: DAPEvent.initialized))))

	#expect(await session.state == .configuring)
}

@Test func dapClientSessionLaunchSendsRequestWhileConfiguring() async throws {
	let (session, transport) = try await configuredSession()
	let task = Task {
		try await session.launch(arguments: .object(["program": .string("/tmp/app")]))
	}

	try await transport.waitForWriteCount(2)
	#expect(try transport.message(at: 1) == .request(DAPRequestMessage(
		seq: 2,
		command: DAPCommand.launch,
		arguments: .object(["program": .string("/tmp/app")])
	)))
	_ = try await session.receive(DAPMessageFramer.frame(message: .response(DAPResponseMessage(
		seq: 3,
		requestSeq: 2,
		success: true,
		command: DAPCommand.launch
	))))
	_ = try await task.value

	#expect(await session.state == .configuring)
}

@Test func dapClientSessionSetBreakpointsThenConfigurationDoneTransitionsToRunning() async throws {
	let (session, transport) = try await configuredSession()
	let breakpoints = DAPSetBreakpointsArguments(
		source: DAPSource(path: "/tmp/main.swift"),
		breakpoints: [DAPSourceBreakpoint(line: 7)]
	)
	let breakpointsTask = Task {
		try await session.setBreakpoints(breakpoints)
	}

	try await transport.waitForWriteCount(2)
	#expect(try transport.message(at: 1) == .request(DAPRequestMessage(
		seq: 2,
		command: DAPCommand.setBreakpoints,
		arguments: .object([
			"breakpoints": .array([.object(["line": .int(7)])]),
			"source": .object(["path": .string("/tmp/main.swift")]),
		])
	)))
	_ = try await session.receive(DAPMessageFramer.frame(message: .response(DAPResponseMessage(
		seq: 3,
		requestSeq: 2,
		success: true,
		command: DAPCommand.setBreakpoints
	))))
	_ = try await breakpointsTask.value
	#expect(await session.state == .configuring)

	let configurationTask = Task {
		try await session.configurationDone()
	}
	try await transport.waitForWriteCount(3)
	#expect(try transport.message(at: 2) == .request(DAPRequestMessage(seq: 3, command: DAPCommand.configurationDone)))
	_ = try await session.receive(DAPMessageFramer.frame(message: .response(DAPResponseMessage(
		seq: 4,
		requestSeq: 3,
		success: true,
		command: DAPCommand.configurationDone
	))))
	_ = try await configurationTask.value

	#expect(await session.state == .running)
}

@Test func dapClientSessionStoppedAndTerminatedEventsUpdateState() async throws {
	let (session, _) = try await runningSession()

	_ = try await session.receive(DAPMessageFramer.frame(message: .event(DAPEventMessage(seq: 4, event: DAPEvent.stopped))))
	#expect(await session.state == .stopped)

	_ = try await session.receive(DAPMessageFramer.frame(message: .event(DAPEventMessage(
		seq: 5,
		event: DAPEvent.continued,
		body: .object(["threadId": .int(1)])
	))))
	#expect(await session.state == .running)

	_ = try await session.receive(DAPMessageFramer.frame(message: .event(DAPEventMessage(seq: 6, event: DAPEvent.exited))))
	#expect(await session.state == .terminated)
}

@Test func dapClientSessionRejectsLifecycleRequestsInWrongState() async throws {
	let session = DAPClientSession(transport: RecordingDAPTransport())
	var thrown: DAPClientError?

	do {
		_ = try await session.setBreakpoints(DAPSetBreakpointsArguments(source: DAPSource(path: "/tmp/main.swift")))
	} catch let error as DAPClientError {
		thrown = error
	}

	#expect(thrown == .invalidState(expected: [.configuring], actual: .idle))
}

private enum RecordingDAPTransportError: Error {
	case timeout(expected: Int, actual: Int)
	case missingWrite(Int)
	case invalidFrame(Int)
	case missingRequest(String)
	case timeoutWaitingForEvent
	case streamEnded
}

private func requestSeq(for command: String, in messages: [DAPMessage]) throws -> Int {
	for message in messages {
		if case let .request(request) = message, request.command == command {
			return request.seq
		}
	}
	throw RecordingDAPTransportError.missingRequest(command)
}

private func configuredSession() async throws -> (DAPClientSession, RecordingDAPTransport) {
	let transport = RecordingDAPTransport()
	let session = DAPClientSession(transport: transport)
	let task = Task {
		try await session.initialize(clientCapabilities: DAPInitializeRequestArguments(adapterID: "lldb"))
	}
	try await transport.waitForWriteCount(1)
	_ = try await session.receive(DAPMessageFramer.frame(message: .response(DAPResponseMessage(
		seq: 2,
		requestSeq: 1,
		success: true,
		command: DAPCommand.initialize
	))))
	_ = try await task.value
	_ = try await session.receive(DAPMessageFramer.frame(message: .event(DAPEventMessage(seq: 3, event: DAPEvent.initialized))))
	#expect(await session.state == .configuring)
	return (session, transport)
}

private func runningSession() async throws -> (DAPClientSession, RecordingDAPTransport) {
	let (session, transport) = try await configuredSession()
	let task = Task {
		try await session.configurationDone()
	}
	try await transport.waitForWriteCount(2)
	_ = try await session.receive(DAPMessageFramer.frame(message: .response(DAPResponseMessage(
		seq: 4,
		requestSeq: 2,
		success: true,
		command: DAPCommand.configurationDone
	))))
	_ = try await task.value
	#expect(await session.state == .running)
	return (session, transport)
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

	func message(at index: Int) throws -> DAPMessage {
		guard let data = write(at: index) else {
			throw RecordingDAPTransportError.missingWrite(index)
		}
		var framer = DAPMessageFramer()
		let payloads = try framer.append(data)
		guard payloads.count == 1 else {
			throw RecordingDAPTransportError.invalidFrame(index)
		}
		return try JSONDecoder().decode(DAPMessage.self, from: payloads[0])
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

private func firstDAPEvent(in stream: AsyncStream<DAPEventMessage>) async throws -> DAPEventMessage {
	try await withThrowingTaskGroup(of: DAPEventMessage?.self) { group in
		group.addTask {
			for await event in stream {
				return event
			}
			return nil
		}
		group.addTask {
			try await Task.sleep(nanoseconds: 1_000_000_000)
			throw RecordingDAPTransportError.timeoutWaitingForEvent
		}
		let event = try await group.next()!
		group.cancelAll()
		guard let event else {
			throw RecordingDAPTransportError.streamEnded
		}
		return event
	}
}
