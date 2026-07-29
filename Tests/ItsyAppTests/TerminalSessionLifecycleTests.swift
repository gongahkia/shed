import Darwin
import Foundation
@testable import ItsyApp
import Testing

@Test @MainActor func terminalSessionLifecycleOwnsStartRestartAndStaleExitHandling() async throws {
	var sessions: [TerminalSessionLifecycleFixture] = []
	let lifecycle = TerminalSessionLifecycle { _ in
		let session = TerminalSessionLifecycleFixture(processIdentifier: pid_t(100 + sessions.count))
		sessions.append(session)
		return session
	}
	let paneID = UUID()
	var outputs: [Data] = []
	var exits: [Int32] = []
	let callbacks = TerminalSessionLifecycle.Callbacks(
		onOutput: { outputs.append($0) },
		onExit: { exits.append($0) },
		onStartFailure: { _ in Issue.record("unexpected terminal start failure") }
	)

	lifecycle.startIfNeeded(paneID: paneID, currentDirectoryURL: URL(fileURLWithPath: "/workspace"), columns: 80, rows: 24, callbacks: callbacks)
	lifecycle.startIfNeeded(paneID: paneID, currentDirectoryURL: URL(fileURLWithPath: "/workspace"), columns: 100, rows: 30, callbacks: callbacks)
	#expect(sessions.count == 1)
	#expect(sessions[0].starts == [.init(columns: 80, rows: 24)])
	#expect(lifecycle.processIdentifier(for: paneID) == 100)
	lifecycle.send(Data("input".utf8), to: paneID)
	lifecycle.resize(columns: 90, rows: 28, for: paneID)
	#expect(sessions[0].sent == [Data("input".utf8)])
	#expect(sessions[0].resizes == [.init(columns: 90, rows: 28)])
	sessions[0].emitOutput(Data("first".utf8))
	#expect(await terminalLifecycleEventually { outputs == [Data("first".utf8)] })

	lifecycle.restart(paneID: paneID, currentDirectoryURL: URL(fileURLWithPath: "/workspace"), columns: 120, rows: 40, callbacks: callbacks)
	#expect(sessions.count == 2)
	#expect(sessions[0].terminated)
	#expect(sessions[1].starts == [.init(columns: 120, rows: 40)])
	sessions[0].emitStaleExit(9)
	#expect(await terminalLifecycleEventually { lifecycle.processIdentifier(for: paneID) == 101 })
	#expect(exits.isEmpty)

	sessions[1].emitExit(0)
	#expect(await terminalLifecycleEventually { !lifecycle.hasSession(for: paneID) })
	#expect(exits == [0])
}

@Test @MainActor func terminalSessionLifecycleRemovesFailedSessionsAndTerminatesAll() {
	let failing = TerminalSessionLifecycleFixture(processIdentifier: 200, startError: TerminalSessionLifecycleFixture.Failure.start)
	let running = TerminalSessionLifecycleFixture(processIdentifier: 201)
	var remaining = [failing, running]
	let lifecycle = TerminalSessionLifecycle { _ in
		remaining.removeFirst()
	}
	let failedPaneID = UUID()
	let runningPaneID = UUID()
	var failures = 0
	let callbacks = TerminalSessionLifecycle.Callbacks(
		onOutput: { _ in Issue.record("unexpected terminal output") },
		onExit: { _ in Issue.record("unexpected terminal exit") },
		onStartFailure: { _ in failures += 1 }
	)

	lifecycle.startIfNeeded(paneID: failedPaneID, currentDirectoryURL: URL(fileURLWithPath: "/workspace"), columns: 80, rows: 24, callbacks: callbacks)
	#expect(failures == 1)
	#expect(!lifecycle.hasSession(for: failedPaneID))
	#expect(failing.starts == [.init(columns: 80, rows: 24)])

	lifecycle.startIfNeeded(paneID: runningPaneID, currentDirectoryURL: URL(fileURLWithPath: "/workspace"), columns: 80, rows: 24, callbacks: callbacks)
	lifecycle.terminateAll()
	#expect(running.terminated)
	#expect(!lifecycle.hasSession(for: runningPaneID))
}

@Test @MainActor func terminalSessionLifecycleTerminatesOnlyClosedPaneSession() {
	let first = TerminalSessionLifecycleFixture(processIdentifier: 300)
	let second = TerminalSessionLifecycleFixture(processIdentifier: 301)
	var remaining = [first, second]
	let lifecycle = TerminalSessionLifecycle { _ in remaining.removeFirst() }
	let firstPaneID = UUID()
	let secondPaneID = UUID()
	let callbacks = TerminalSessionLifecycle.Callbacks(
		onOutput: { _ in Issue.record("unexpected terminal output") },
		onExit: { _ in Issue.record("unexpected terminal exit") },
		onStartFailure: { _ in Issue.record("unexpected terminal start failure") }
	)

	lifecycle.startIfNeeded(paneID: firstPaneID, currentDirectoryURL: URL(fileURLWithPath: "/workspace"), columns: 80, rows: 24, callbacks: callbacks)
	lifecycle.startIfNeeded(paneID: secondPaneID, currentDirectoryURL: URL(fileURLWithPath: "/workspace"), columns: 80, rows: 24, callbacks: callbacks)
	lifecycle.terminate(for: firstPaneID)
	#expect(first.terminated)
	#expect(!lifecycle.hasSession(for: firstPaneID))
	#expect(!second.terminated)
	#expect(lifecycle.hasSession(for: secondPaneID))
}

@MainActor private func terminalLifecycleEventually(_ predicate: @escaping @MainActor () -> Bool) async -> Bool {
	for _ in 0 ..< 50 {
		if predicate() { return true }
		try? await Task.sleep(for: .milliseconds(10))
	}
	return predicate()
}

private struct TerminalSessionLifecycleInvocation: Equatable {
	let columns: Int
	let rows: Int
}

private final class TerminalSessionLifecycleFixture: TerminalSessionControlling {
	enum Failure: Error {
		case start
	}

	let processIdentifier: pid_t?
	let startError: Error?
	private(set) var isRunning = false
	private(set) var starts: [TerminalSessionLifecycleInvocation] = []
	private(set) var sent: [Data] = []
	private(set) var resizes: [TerminalSessionLifecycleInvocation] = []
	private(set) var terminated = false
	private var lastOutput: ((Data) -> Void)?
	private var lastExit: ((Int32) -> Void)?
	var onOutput: ((Data) -> Void)? {
		didSet {
			if let onOutput { lastOutput = onOutput }
		}
	}
	var onExit: ((Int32) -> Void)? {
		didSet {
			if let onExit { lastExit = onExit }
		}
	}

	init(processIdentifier: pid_t?, startError: Error? = nil) {
		self.processIdentifier = processIdentifier
		self.startError = startError
	}

	func start(columns: Int, rows: Int) throws {
		starts.append(.init(columns: columns, rows: rows))
		if let startError { throw startError }
		isRunning = true
	}

	func send(_ data: Data) {
		sent.append(data)
	}

	func resize(columns: Int, rows: Int) {
		resizes.append(.init(columns: columns, rows: rows))
	}

	func terminate() {
		terminated = true
		isRunning = false
	}

	func emitOutput(_ data: Data) {
		onOutput?(data)
	}

	func emitExit(_ status: Int32) {
		isRunning = false
		onExit?(status)
	}

	func emitStaleExit(_ status: Int32) {
		lastExit?(status)
	}
}
