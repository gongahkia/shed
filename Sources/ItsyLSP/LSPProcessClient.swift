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

	public var executableURL: URL {
		transport.executableURL
	}

	public var arguments: [String] {
		transport.arguments
	}

	public var processIdentifier: Int32? {
		transport.processIdentifier
	}

	public var startDate: Date? {
		transport.startDate
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

	public func workspaceSymbol(query: String) async throws -> LSPWorkspaceSymbolResult {
		try await session.workspaceSymbol(query: query)
	}

	public func documentSymbol(textDocument: LSPTextDocumentIdentifier) async throws -> LSPDocumentSymbolResult {
		try await session.documentSymbol(textDocument: textDocument)
	}

	public func prepareRename(uri: String, position: LSPPosition) async throws -> LSPPrepareRenameResult {
		try await session.prepareRename(uri: uri, position: position)
	}

	public func rename(uri: String, position: LSPPosition, newName: String) async throws -> LSPWorkspaceEdit? {
		try await session.rename(uri: uri, position: position, newName: newName)
	}

	public func formatDocument(uri: String, options: LSPFormattingOptions) async throws -> [LSPTextEdit] {
		try await session.formatDocument(uri: uri, options: options)
	}

	public func formatRange(uri: String, range: LSPRange, options: LSPFormattingOptions) async throws -> [LSPTextEdit] {
		try await session.formatRange(uri: uri, range: range, options: options)
	}

	public func codeActions(uri: String, range: LSPRange, context: LSPCodeActionContext) async throws -> LSPCodeActionResponse {
		try await session.codeActions(uri: uri, range: range, context: context)
	}

	public func resolveCodeAction(_ action: LSPCodeAction) async throws -> LSPCodeAction {
		try await session.resolveCodeAction(action)
	}

	@discardableResult
	public func executeCommand(_ command: LSPCommand) async throws -> LSPAny {
		try await session.executeCommand(command)
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
