import Foundation
import ItsyDAP
import Testing

@Test func dapProcessTransportRejectsWriteBeforeStart() throws {
	let transport = DAPProcessTransport(executableURL: URL(fileURLWithPath: "/bin/cat"))

	#expect(throws: DAPProcessTransportError.self) {
		try transport.write(Data("ping\n".utf8))
	}
}

@Test func dapProcessTransportPublishesStdoutAndTermination() async throws {
	let transport = DAPProcessTransport(executableURL: URL(fileURLWithPath: "/bin/cat"))
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

@Test func dapProcessTransportRejectsDoubleStart() throws {
	let transport = DAPProcessTransport(executableURL: URL(fileURLWithPath: "/bin/cat"))
	try transport.start()
	defer {
		transport.terminate()
	}

	#expect(throws: DAPProcessTransportError.self) {
		try transport.start()
	}
}

@Test func dapProcessTransportExposesProcessMetadataAfterStart() throws {
	let executableURL = URL(fileURLWithPath: "/bin/cat")
	let transport = DAPProcessTransport(executableURL: executableURL, arguments: ["-u"])
	try transport.start()
	defer {
		transport.terminate()
	}

	#expect(transport.executableURL == executableURL)
	#expect(transport.arguments == ["-u"])
	#expect(transport.processIdentifier != nil)
	#expect(transport.startDate != nil)
}

@Test func dapProcessTransportPublishesStderr() async throws {
	let transport = DAPProcessTransport(executableURL: URL(fileURLWithPath: "/bin/sh"), arguments: ["-c", "printf 'warn\\n' >&2"])

	try transport.start()
	let stderr = try await firstProcessEvent(in: transport.events) { event in
		if case .stderr = event {
			return true
		}
		return false
	}
	let terminated = try await firstProcessEvent(in: transport.events) { event in
		if case .terminated = event {
			return true
		}
		return false
	}

	#expect(stderr == .stderr(Data("warn\n".utf8)))
	#expect(terminated == .terminated(0))
}

@Test func dapProcessTransportPublishesManualTermination() async throws {
	let transport = DAPProcessTransport(executableURL: URL(fileURLWithPath: "/bin/cat"))

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

private func firstProcessEvent(in stream: AsyncStream<DAPProcessTransportEvent>, matching predicate: @escaping @Sendable (DAPProcessTransportEvent) -> Bool) async throws -> DAPProcessTransportEvent {
	try await withThrowingTaskGroup(of: DAPProcessTransportEvent?.self) { group in
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
