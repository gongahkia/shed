import Foundation
import ItsyLSP
import Testing

@Test func processEventRouterFeedsStdoutIntoSession() async throws {
	let transport = RouterRecordingTransport()
	let session = LSPClientSession(transport: transport)
	let router = LSPProcessEventRouter(session: session)
	let initialize = Task {
		try await session.initialize(LSPInitializeParams(processId: nil, rootUri: "file:///tmp/project"))
	}

	try await transport.waitForWriteCount(1)
	let result: LSPAny = .object(["capabilities": .object([:])])
	let initializeEvents = try await router.handle(.stdout(LSPMessageFramer.frame(message: .response(JSONRPCResponseMessage(id: .int(1), result: result)))))
	#expect(initializeEvents.isEmpty)
	#expect(try await initialize.value == result)

	let notification = JSONRPCNotificationMessage(
		method: LSPMethod.textDocumentPublishDiagnostics,
		params: .object(["uri": .string("file:///tmp/main.swift"), "diagnostics": .array([])])
	)
	let notificationEvents = try await router.handle(.stdout(LSPMessageFramer.frame(message: .notification(notification))))

	#expect(notificationEvents == [.server(.notification(notification))])
}

@Test func processEventRouterMapsStderrAndTermination() async throws {
	let session = LSPClientSession(transport: RouterRecordingTransport())
	let router = LSPProcessEventRouter(session: session)
	let stderr = Data("server warning\n".utf8)

	#expect(try await router.handle(.stderr(stderr)) == [.stderr(stderr)])
	#expect(try await router.handle(.terminated(0)) == [.terminated(0)])
}

@Test func processClientPumpsStdoutIntoServerEvents() async throws {
	let transport = LSPProcessTransport(executableURL: URL(fileURLWithPath: "/bin/cat"))
	let client = LSPProcessClient(transport: transport)
	let notification = JSONRPCNotificationMessage(method: LSPMethod.initialized, params: .object([:]))

	try client.start()
	try transport.write(LSPMessageFramer.frame(message: .notification(notification)))
	let event = try await firstClientEvent(in: client.events) { event in
		if case .server = event {
			return true
		}
		return false
	}
	client.terminate()

	#expect(event == .server(.notification(notification)))
}

@Test func processClientRejectsDoubleStart() throws {
	let client = LSPProcessClient(executableURL: URL(fileURLWithPath: "/bin/cat"))
	try client.start()
	defer {
		client.terminate()
	}

	#expect(throws: LSPProcessClientError.self) {
		try client.start()
	}
}

private enum RouterRecordingTransportError: Error {
	case timeout(expected: Int, actual: Int)
}

private enum ClientEventWaitError: Error {
	case timeout
	case ended
}

private func firstClientEvent(in stream: AsyncStream<LSPProcessClientEvent>, matching predicate: @escaping @Sendable (LSPProcessClientEvent) -> Bool) async throws -> LSPProcessClientEvent {
	try await withThrowingTaskGroup(of: LSPProcessClientEvent?.self) { group in
		group.addTask {
			for await event in stream {
				if predicate(event) {
					return event
				}
			}
			return nil
		}
		group.addTask {
			try await Task.sleep(nanoseconds: 1_000_000_000)
			throw ClientEventWaitError.timeout
		}
		let event = try await group.next()!
		group.cancelAll()
		guard let event else {
			throw ClientEventWaitError.ended
		}
		return event
	}
}

private final class RouterRecordingTransport: LSPClientTransport, @unchecked Sendable {
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
		throw RouterRecordingTransportError.timeout(expected: expected, actual: writeCount)
	}

	private var writeCount: Int {
		lock.lock()
		let count = writes.count
		lock.unlock()
		return count
	}
}
