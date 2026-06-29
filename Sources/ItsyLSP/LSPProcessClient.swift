import Foundation

public enum LSPProcessClientEvent: Equatable, Sendable {
	case server(LSPClientEvent)
	case stderr(Data)
	case terminated(Int32)
	case failure(String)
}

public enum LSPProcessClientError: Error, Equatable, Sendable {
	case alreadyStarted
}

public actor LSPProcessEventRouter {
	private let session: LSPClientSession

	public init(session: LSPClientSession) {
		self.session = session
	}

	public func handle(_ event: LSPProcessTransportEvent) async throws -> [LSPProcessClientEvent] {
		switch event {
		case let .stdout(data):
			return try await session.receive(data).map { .server($0) }
		case let .stderr(data):
			return [.stderr(data)]
		case let .terminated(status):
			return [.terminated(status)]
		}
	}
}

public final class LSPProcessClient: @unchecked Sendable {
	public let session: LSPClientSession
	public let events: AsyncStream<LSPProcessClientEvent>

	private let transport: LSPProcessTransport
	private let router: LSPProcessEventRouter
	private let continuation: AsyncStream<LSPProcessClientEvent>.Continuation
	private let lock = NSLock()
	private var started = false
	private var pumpTask: Task<Void, Never>?

	public convenience init(executableURL: URL, arguments: [String] = [], currentDirectoryURL: URL? = nil, environment: [String: String]? = nil) {
		self.init(transport: LSPProcessTransport(
			executableURL: executableURL,
			arguments: arguments,
			currentDirectoryURL: currentDirectoryURL,
			environment: environment
		))
	}

	public init(transport: LSPProcessTransport) {
		self.transport = transport
		session = LSPClientSession(transport: transport)
		router = LSPProcessEventRouter(session: session)
		var capturedContinuation: AsyncStream<LSPProcessClientEvent>.Continuation?
		events = AsyncStream { continuation in
			capturedContinuation = continuation
		}
		continuation = capturedContinuation!
	}

	deinit {
		pumpTask?.cancel()
		transport.terminate()
		continuation.finish()
	}

	public func start() throws {
		lock.lock()
		guard !started else {
			lock.unlock()
			throw LSPProcessClientError.alreadyStarted
		}
		started = true
		lock.unlock()

		do {
			try transport.start()
		} catch {
			lock.lock()
			started = false
			lock.unlock()
			throw error
		}

		pumpTask = Task { [transport, router, continuation] in
			for await event in transport.events {
				do {
					for clientEvent in try await router.handle(event) {
						continuation.yield(clientEvent)
					}
				} catch {
					continuation.yield(.failure(String(describing: error)))
				}
			}
			continuation.finish()
		}
	}

	@discardableResult
	public func initialize(_ params: LSPInitializeParams) async throws -> LSPAny {
		try await session.initialize(params)
	}

	@discardableResult
	public func sendRequest(method: String, params: LSPAny? = nil) async throws -> JSONRPCResponseMessage {
		try await session.sendRequest(method: method, params: params)
	}

	public func sendNotification(method: String, params: LSPAny? = nil) async throws {
		try await session.sendNotification(method: method, params: params)
	}

	public func shutdown() async throws {
		try await session.shutdown()
	}

	public func terminate() {
		transport.terminate()
	}
}
