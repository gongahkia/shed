import Darwin
import Foundation

protocol TerminalSessionControlling: AnyObject {
	var isRunning: Bool { get }
	var processIdentifier: pid_t? { get }
	var onOutput: ((Data) -> Void)? { get set }
	var onExit: ((Int32) -> Void)? { get set }
	func start(columns: Int, rows: Int) throws
	func send(_ data: Data)
	func resize(columns: Int, rows: Int)
	func terminate()
}

extension ItsyTerminalSession: TerminalSessionControlling {}

@MainActor final class TerminalSessionLifecycle {
	typealias SessionFactory = (URL) -> any TerminalSessionControlling

	struct Callbacks {
		let onOutput: (Data) -> Void
		let onExit: (Int32) -> Void
		let onStartFailure: (Error) -> Void
	}

	private let sessionFactory: SessionFactory
	private var sessions: [UUID: any TerminalSessionControlling] = [:]

	init(sessionFactory: @escaping SessionFactory) {
		self.sessionFactory = sessionFactory
	}

	func isRunning(for paneID: UUID) -> Bool {
		sessions[paneID]?.isRunning == true
	}

	func processIdentifier(for paneID: UUID) -> pid_t? {
		sessions[paneID]?.processIdentifier
	}

	func hasSession(for paneID: UUID) -> Bool {
		sessions[paneID] != nil
	}

	func send(_ data: Data, to paneID: UUID) {
		sessions[paneID]?.send(data)
	}

	func resize(columns: Int, rows: Int, for paneID: UUID) {
		sessions[paneID]?.resize(columns: columns, rows: rows)
	}

	func startIfNeeded(
		paneID: UUID,
		currentDirectoryURL: URL,
		columns: Int,
		rows: Int,
		callbacks: Callbacks
	) {
		guard !isRunning(for: paneID) else { return }
		let session = sessionFactory(currentDirectoryURL)
		session.onOutput = { [weak self, weak session] data in
			DispatchQueue.main.async {
				guard let self, let session, self.isCurrent(session, for: paneID) else { return }
				callbacks.onOutput(data)
			}
		}
		session.onExit = { [weak self, weak session] status in
			DispatchQueue.main.async {
				guard let self, let session, self.isCurrent(session, for: paneID) else { return }
				self.sessions.removeValue(forKey: paneID)
				callbacks.onExit(status)
			}
		}
		sessions[paneID] = session
		do {
			try session.start(columns: columns, rows: rows)
		} catch {
			guard isCurrent(session, for: paneID) else { return }
			sessions.removeValue(forKey: paneID)
			session.onOutput = nil
			session.onExit = nil
			callbacks.onStartFailure(error)
		}
	}

	func restart(
		paneID: UUID,
		currentDirectoryURL: URL,
		columns: Int,
		rows: Int,
		callbacks: Callbacks
	) {
		terminate(for: paneID)
		startIfNeeded(
			paneID: paneID,
			currentDirectoryURL: currentDirectoryURL,
			columns: columns,
			rows: rows,
			callbacks: callbacks
		)
	}

	func terminate(for paneID: UUID) {
		guard let session = sessions.removeValue(forKey: paneID) else { return }
		session.onOutput = nil
		session.onExit = nil
		session.terminate()
	}

	func terminateAll() {
		let activeSessions = sessions.values
		sessions.removeAll()
		for session in activeSessions {
			session.onOutput = nil
			session.onExit = nil
			session.terminate()
		}
	}

	private func isCurrent(_ session: any TerminalSessionControlling, for paneID: UUID) -> Bool {
		sessions[paneID] === session
	}
}
