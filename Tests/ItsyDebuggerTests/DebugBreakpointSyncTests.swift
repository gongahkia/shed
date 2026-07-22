import Foundation
import ItsyDAP
import ItsyDebugger
import Testing

@Test func debugBreakpointSyncSendsPersistedBreakpointsBeforeConfigurationDone() async throws {
	let fixture = try DebugBreakpointSyncFixture()
	defer {
		fixture.cleanup()
	}
	let source = fixture.workspaceRoot.appendingPathComponent("Sources/App.swift")
	let outside = fixture.root.appendingPathComponent("Other.swift")
	let seedStore = BreakpointStore(fileURL: fixture.storeURL)
	await seedStore.replace([
		SourceBreakpoint(line: 14, condition: "value > 0"),
		SourceBreakpoint(line: 8, column: 2, hitCondition: "3", logMessage: "hit"),
	], for: source)
	await seedStore.replace([SourceBreakpoint(line: 99)], for: outside)
	try await seedStore.save()
	let persistedStore = BreakpointStore(fileURL: fixture.storeURL)
	let (session, transport) = try await configuredSession()

	let syncTask = Task {
		try await DebugBreakpointSync.syncPersistedBreakpoints(from: persistedStore, using: session, workspaceRoot: fixture.workspaceRoot)
	}
	try await transport.waitForWriteCount(2)
	let breakpointRequest = try transport.request(at: 1)
	#expect(breakpointRequest.command == DAPCommand.setBreakpoints)
	#expect(breakpointRequest.arguments == .object([
		"breakpoints": .array([
			.object([
				"column": .int(2),
				"hitCondition": .string("3"),
				"line": .int(8),
				"logMessage": .string("hit"),
			]),
			.object([
				"condition": .string("value > 0"),
				"line": .int(14),
			]),
		]),
		"source": .object([
			"name": .string("App.swift"),
			"path": .string(source.standardizedFileURL.path),
		]),
	]))
	try await respond(session, request: breakpointRequest, body: DAPSetBreakpointsResponseBody(breakpoints: [
		DAPBreakpoint(verified: true, line: 8),
		DAPBreakpoint(verified: true, line: 14),
	]))
	let verification = try await syncTask.value
	#expect(verification == [
		DebugBreakpointVerification(sourceURL: source.standardizedFileURL, requested: SourceBreakpoint(line: 8, column: 2, hitCondition: "3", logMessage: "hit"), adapterBreakpoint: DAPBreakpoint(verified: true, line: 8)),
		DebugBreakpointVerification(sourceURL: source.standardizedFileURL, requested: SourceBreakpoint(line: 14, condition: "value > 0"), adapterBreakpoint: DAPBreakpoint(verified: true, line: 14)),
	])
	#expect(try transport.requests().map(\.command) == [DAPCommand.initialize, DAPCommand.setBreakpoints])

	let doneTask = Task {
		try await session.configurationDone()
	}
	try await transport.waitForWriteCount(3)
	#expect(try transport.request(at: 2).command == DAPCommand.configurationDone)
	try await respond(session, request: try transport.request(at: 2), body: DAPAny.object([:]))
	_ = try await doneTask.value
}

@Test func debugBreakpointSyncReconcilesMissingAndChangedAdapterStatus() async throws {
	let fixture = try DebugBreakpointSyncFixture()
	defer {
		fixture.cleanup()
	}
	let source = fixture.workspaceRoot.appendingPathComponent("Sources/App.swift")
	let store = BreakpointStore(fileURL: fixture.storeURL)
	await store.replace([SourceBreakpoint(line: 8), SourceBreakpoint(line: 14)], for: source)
	try await store.save()
	let (session, transport) = try await configuredSession()
	let syncTask = Task {
		try await DebugBreakpointSync.syncPersistedBreakpoints(from: BreakpointStore(fileURL: fixture.storeURL), using: session, workspaceRoot: fixture.workspaceRoot)
	}

	try await transport.waitForWriteCount(2)
	let request = try transport.request(at: 1)
	try await respond(session, request: request, body: DAPSetBreakpointsResponseBody(breakpoints: [
		DAPBreakpoint(id: 4, verified: false, message: "Pending symbols", line: 10),
	]))
	let verification = try await syncTask.value
	#expect(verification == [
		DebugBreakpointVerification(sourceURL: source.standardizedFileURL, requested: SourceBreakpoint(line: 8), adapterBreakpoint: DAPBreakpoint(id: 4, verified: false, message: "Pending symbols", line: 10)),
		DebugBreakpointVerification(sourceURL: source.standardizedFileURL, requested: SourceBreakpoint(line: 14), adapterBreakpoint: DAPBreakpoint(verified: false, message: "Adapter returned no breakpoint status.", line: 14)),
	])

	let statusStore = DebugBreakpointVerificationStore()
	await statusStore.replace(verification)
	await statusStore.apply(DAPBreakpoint(id: 4, verified: true, line: 11))
	#expect(await statusStore.snapshot().first?.adapterBreakpoint == DAPBreakpoint(id: 4, verified: true, line: 11))
}

private func configuredSession() async throws -> (DAPClientSession, RecordingDAPTransport) {
	let transport = RecordingDAPTransport()
	let session = DAPClientSession(transport: transport)
	let task = Task {
		try await session.initialize(clientCapabilities: DAPInitializeRequestArguments(adapterID: "mock"))
	}
	try await transport.waitForWriteCount(1)
	try await respond(session, request: try transport.request(at: 0), body: DAPCapabilities())
	_ = try await task.value
	_ = try await session.receive(DAPMessageFramer.frame(message: .event(DAPEventMessage(seq: 200, event: DAPEvent.initialized))))
	#expect(await session.state == .configuring)
	return (session, transport)
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

	func requests() throws -> [DAPRequestMessage] {
		try (0 ..< writeCount).map { try request(at: $0) }
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

private struct DebugBreakpointSyncFixture {
	let root: URL
	let workspaceRoot: URL
	let storeURL: URL

	init(fileManager: FileManager = .default) throws {
		root = fileManager.temporaryDirectory.appendingPathComponent("itsy-debug-bp-sync-\(UUID().uuidString)", isDirectory: true)
		workspaceRoot = root.appendingPathComponent("workspace", isDirectory: true)
		storeURL = root
			.appendingPathComponent("home", isDirectory: true)
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("breakpoints.json")
		try fileManager.createDirectory(at: workspaceRoot.appendingPathComponent("Sources", isDirectory: true), withIntermediateDirectories: true)
	}

	func cleanup(fileManager: FileManager = .default) {
		try? fileManager.removeItem(at: root)
	}
}
