import Foundation

public enum LSPProcessTransportError: Error, Equatable, Sendable {
	case alreadyStarted
	case notStarted
	case stopped
}

public enum LSPProcessTransportEvent: Equatable, Sendable {
	case stdout(Data)
	case stderr(Data)
	case terminated(Int32)
}

public final class LSPProcessTransport: LSPClientTransport, @unchecked Sendable {
	public let events: AsyncStream<LSPProcessTransportEvent>

	private let continuation: AsyncStream<LSPProcessTransportEvent>.Continuation
	private let process: Process
	private let stdinPipe = Pipe()
	private let stdoutPipe = Pipe()
	private let stderrPipe = Pipe()
	private let lock = NSLock()
	private var started = false
	private var stopped = false
	private var finished = false

	public init(executableURL: URL, arguments: [String] = [], currentDirectoryURL: URL? = nil, environment: [String: String]? = nil) {
		var capturedContinuation: AsyncStream<LSPProcessTransportEvent>.Continuation?
		events = AsyncStream { continuation in
			capturedContinuation = continuation
		}
		continuation = capturedContinuation!
		process = Process()
		process.executableURL = executableURL
		process.arguments = arguments
		process.currentDirectoryURL = currentDirectoryURL
		process.environment = environment
		process.standardInput = stdinPipe
		process.standardOutput = stdoutPipe
		process.standardError = stderrPipe
	}

	deinit {
		terminate()
		continuation.finish()
	}

	public func start() throws {
		lock.lock()
		guard !started else {
			lock.unlock()
			throw LSPProcessTransportError.alreadyStarted
		}
		started = true
		lock.unlock()

		stdoutPipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
			self?.emit(.stdout, from: handle)
		}
		stderrPipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
			self?.emit(.stderr, from: handle)
		}
		process.terminationHandler = { [weak self] process in
			self?.finish(status: process.terminationStatus)
		}

		do {
			try process.run()
		} catch {
			clearHandlers()
			lock.lock()
			started = false
			stopped = true
			lock.unlock()
			throw error
		}
	}

	public func write(_ data: Data) throws {
		let handle = try inputHandle()
		try handle.write(contentsOf: data)
	}

	public func closeInput() throws {
		let handle = try inputHandle()
		try handle.close()
	}

	public func terminate() {
		lock.lock()
		let shouldTerminate = started && !stopped
		if shouldTerminate {
			stopped = true
		}
		lock.unlock()
		guard shouldTerminate else {
			return
		}
		try? stdinPipe.fileHandleForWriting.close()
		if process.isRunning {
			process.terminate()
		} else {
			finish(status: process.terminationStatus)
		}
	}

	private func inputHandle() throws -> FileHandle {
		lock.lock()
		let isStarted = started
		let isStopped = stopped
		lock.unlock()
		guard isStarted else {
			throw LSPProcessTransportError.notStarted
		}
		guard !isStopped else {
			throw LSPProcessTransportError.stopped
		}
		return stdinPipe.fileHandleForWriting
	}

	private func emit(_ kind: LSPProcessOutputKind, from handle: FileHandle) {
		let data = handle.availableData
		guard !data.isEmpty else {
			return
		}
		switch kind {
		case .stdout:
			continuation.yield(.stdout(data))
		case .stderr:
			continuation.yield(.stderr(data))
		}
	}

	private func finish(status: Int32) {
		lock.lock()
		guard !finished else {
			lock.unlock()
			return
		}
		finished = true
		stopped = true
		lock.unlock()
		clearHandlers()
		continuation.yield(.terminated(status))
		continuation.finish()
	}

	private func clearHandlers() {
		stdoutPipe.fileHandleForReading.readabilityHandler = nil
		stderrPipe.fileHandleForReading.readabilityHandler = nil
		process.terminationHandler = nil
	}
}

private enum LSPProcessOutputKind {
	case stdout
	case stderr
}
