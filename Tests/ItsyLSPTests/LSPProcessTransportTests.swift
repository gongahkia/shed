import Foundation
import ItsyLSP
import Testing

@Test func processTransportRejectsWriteBeforeStart() throws {
	let transport = LSPProcessTransport(executableURL: URL(fileURLWithPath: "/bin/cat"))

	#expect(throws: LSPProcessTransportError.self) {
		try transport.write(Data("ping\n".utf8))
	}
}

@Test func processTransportPublishesStdoutAndTermination() async throws {
	let transport = LSPProcessTransport(executableURL: URL(fileURLWithPath: "/bin/cat"))
	let payload = Data("ping\n".utf8)

	try transport.start()
	try transport.write(payload)
	let stdout = try await firstProcessEvent(in: transport.events) { event in
		if case .stdout = event {
			return true
		}
		return false
	}
	try transport.closeInput()
	let terminated = try await firstProcessEvent(in: transport.events) { event in
		if case .terminated = event {
			return true
		}
		return false
	}

	#expect(stdout == .stdout(payload))
	#expect(terminated == .terminated(0))
}

@Test func processTransportRejectsDoubleStart() throws {
	let transport = LSPProcessTransport(executableURL: URL(fileURLWithPath: "/bin/cat"))
	try transport.start()
	defer {
		transport.terminate()
	}

	#expect(throws: LSPProcessTransportError.self) {
		try transport.start()
	}
}

@Test func processTransportPublishesManualTermination() async throws {
	let transport = LSPProcessTransport(executableURL: URL(fileURLWithPath: "/bin/cat"))

	try transport.start()
	transport.terminate()
	let terminated = try await firstProcessEvent(in: transport.events) { event in
		if case .terminated = event {
			return true
		}
		return false
	}
	let isTerminated: Bool
	if case .terminated = terminated {
		isTerminated = true
	} else {
		isTerminated = false
	}

	#expect(isTerminated)
}

private enum ProcessEventWaitError: Error {
	case timeout
	case ended
}

private func firstProcessEvent(in stream: AsyncStream<LSPProcessTransportEvent>, matching predicate: @escaping @Sendable (LSPProcessTransportEvent) -> Bool) async throws -> LSPProcessTransportEvent {
	try await withThrowingTaskGroup(of: LSPProcessTransportEvent?.self) { group in
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
			throw ProcessEventWaitError.timeout
		}
		let event = try await group.next()!
		group.cancelAll()
		guard let event else {
			throw ProcessEventWaitError.ended
		}
		return event
	}
}
