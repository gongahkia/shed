import Darwin
import Foundation
@testable import ItsyApp
import Testing

@Test @MainActor func terminalSessionStressTerminatesEveryOwnedShell() async throws {
	let sessions = (0..<12).map { _ in terminalTestSession() }
	let probes = sessions.map { _ in TerminalSessionProbe() }
	for (session, probe) in zip(sessions, probes) {
		session.onExit = { probe.recordExit($0) }
		try session.start(columns: 80, rows: 24)
	}
	let pids = try sessions.map { try #require($0.processIdentifier) }
	for session in sessions {
		for index in 1...10 {
			session.resize(columns: 40 + index, rows: 12 + index)
		}
		session.terminate()
	}
	for probe in probes {
		#expect(await terminalExit(from: probe) != nil)
	}
	for pid in pids {
		#expect(await terminalEventually { !terminalProcessExists(pid) })
	}
}

@Test @MainActor func terminalSessionTerminationKillsItsProcessGroup() async throws {
	let session = terminalTestSession()
	let probe = TerminalSessionProbe()
	session.onOutput = { probe.appendOutput($0) }
	session.onExit = { probe.recordExit($0) }
	try session.start(columns: 80, rows: 24)
	let shellPID = try #require(session.processIdentifier)
	session.send(Data("sleep 30 & echo CHILD:$!\r".utf8))
	let childPID = try #require(await terminalChildPID(from: probe))

	session.terminate()
	#expect(await terminalExit(from: probe) != nil)
	#expect(await terminalEventually { !terminalProcessExists(shellPID) })
	#expect(await terminalEventually { !terminalProcessExists(childPID) })
}

@MainActor private func terminalTestSession() -> ItsyTerminalSession {
	var environment = ProcessInfo.processInfo.environment
	environment["SHELL"] = "/bin/sh"
	return ItsyTerminalSession(currentDirectoryURL: URL(fileURLWithPath: NSTemporaryDirectory()), environment: environment)
}

private final class TerminalSessionProbe: @unchecked Sendable {
	private let lock = NSLock()
	private var output = Data()
	private var exitStatus: Int32?

	func appendOutput(_ data: Data) {
		lock.lock()
		output.append(data)
		lock.unlock()
	}

	func recordExit(_ status: Int32) {
		lock.lock()
		exitStatus = status
		lock.unlock()
	}

	func snapshot() -> (output: String, exitStatus: Int32?) {
		lock.lock()
		defer { lock.unlock() }
		return (String(decoding: output, as: UTF8.self), exitStatus)
	}
}

private func terminalExit(from probe: TerminalSessionProbe) async -> Int32? {
	for _ in 0..<300 {
		if let status = probe.snapshot().exitStatus {
			return status
		}
		try? await Task.sleep(nanoseconds: 10_000_000)
	}
	return nil
}

private func terminalChildPID(from probe: TerminalSessionProbe) async -> pid_t? {
	for _ in 0..<300 {
		if let pid = terminalChildPID(in: probe.snapshot().output) {
			return pid
		}
		try? await Task.sleep(nanoseconds: 10_000_000)
	}
	return nil
}

private func terminalChildPID(in output: String) -> pid_t? {
	for line in output.split(whereSeparator: \.isNewline) {
		guard let range = line.range(of: "CHILD:") else {
			continue
		}
		let digits = line[range.upperBound...].prefix(while: \.isNumber)
		if let pid = pid_t(digits) {
			return pid
		}
	}
	return nil
}

private func terminalEventually(_ condition: @escaping () -> Bool) async -> Bool {
	for _ in 0..<300 {
		if condition() {
			return true
		}
		try? await Task.sleep(nanoseconds: 10_000_000)
	}
	return condition()
}

private func terminalProcessExists(_ pid: pid_t) -> Bool {
	if Darwin.kill(pid, 0) == 0 {
		return true
	}
	return errno == EPERM
}
