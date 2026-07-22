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

public protocol LSPProcessClientTransport: LSPClientTransport {
	var events: AsyncStream<LSPProcessTransportEvent> { get }
	var executableURL: URL { get }
	var arguments: [String] { get }
	var processIdentifier: Int32? { get }
	var startDate: Date? { get }
	func start() throws
	func terminate()
}

extension LSPProcessTransport: LSPProcessClientTransport {}

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
	private static let clientsLock = NSLock()
	private static let liveClients = NSHashTable<LSPProcessClient>.weakObjects()

	public let session: LSPClientSession
	public let events: AsyncStream<LSPProcessClientEvent>

	private let transport: any LSPProcessClientTransport
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

	public init(transport: any LSPProcessClientTransport) {
		self.transport = transport
		session = LSPClientSession(transport: transport)
		router = LSPProcessEventRouter(session: session)
		var capturedContinuation: AsyncStream<LSPProcessClientEvent>.Continuation?
		events = AsyncStream { continuation in
			capturedContinuation = continuation
		}
		continuation = capturedContinuation!
		Self.clientsLock.lock()
		Self.liveClients.add(self)
		Self.clientsLock.unlock()
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
		Self.clientsLock.lock()
		Self.liveClients.remove(self)
		Self.clientsLock.unlock()
		pumpTask?.cancel()
		transport.terminate()
		continuation.finish()
	}

	public static func terminateAll() {
		clientsLock.lock()
		let clients = liveClients.allObjects
		clientsLock.unlock()
		for client in clients {
			client.terminate()
		}
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

	public func definition(uri: String, position: LSPPosition) async throws -> LSPDefinitionResult {
		try await session.definition(uri: uri, position: position)
	}

	public func declaration(uri: String, position: LSPPosition) async throws -> LSPDefinitionResult {
		try await session.declaration(uri: uri, position: position)
	}

	public func typeDefinition(uri: String, position: LSPPosition) async throws -> LSPDefinitionResult {
		try await session.typeDefinition(uri: uri, position: position)
	}

	public func implementation(uri: String, position: LSPPosition) async throws -> LSPDefinitionResult {
		try await session.implementation(uri: uri, position: position)
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

	public func semanticTokensFull(uri: String) async throws -> LSPSemanticTokens? {
		try await session.semanticTokensFull(uri: uri)
	}

	public func semanticTokensDelta(uri: String, previousResultId: String) async throws -> LSPSemanticTokensResult {
		try await session.semanticTokensDelta(uri: uri, previousResultId: previousResultId)
	}

	public func semanticTokensRange(uri: String, range: LSPRange) async throws -> LSPSemanticTokens? {
		try await session.semanticTokensRange(uri: uri, range: range)
	}

	public func inlayHints(uri: String, range: LSPRange) async throws -> [LSPInlayHint] {
		try await session.inlayHints(uri: uri, range: range)
	}

	public func resolveInlayHint(_ hint: LSPInlayHint) async throws -> LSPInlayHint {
		try await session.resolveInlayHint(hint)
	}

	public func foldingRanges(uri: String) async throws -> [LSPFoldingRange] {
		try await session.foldingRanges(uri: uri)
	}

	public func documentHighlights(uri: String, position: LSPPosition) async throws -> [LSPDocumentHighlight] {
		try await session.documentHighlights(uri: uri, position: position)
	}

	public func prepareCallHierarchy(uri: String, position: LSPPosition) async throws -> [LSPCallHierarchyItem] {
		try await session.prepareCallHierarchy(uri: uri, position: position)
	}

	public func incomingCalls(for item: LSPCallHierarchyItem) async throws -> [LSPCallHierarchyIncomingCall] {
		try await session.incomingCalls(for: item)
	}

	public func outgoingCalls(for item: LSPCallHierarchyItem) async throws -> [LSPCallHierarchyOutgoingCall] {
		try await session.outgoingCalls(for: item)
	}

	public func prepareTypeHierarchy(uri: String, position: LSPPosition) async throws -> [LSPTypeHierarchyItem] {
		try await session.prepareTypeHierarchy(uri: uri, position: position)
	}

	public func supertypes(for item: LSPTypeHierarchyItem) async throws -> [LSPTypeHierarchyItem] {
		try await session.supertypes(for: item)
	}

	public func subtypes(for item: LSPTypeHierarchyItem) async throws -> [LSPTypeHierarchyItem] {
		try await session.subtypes(for: item)
	}

	public func sendNotification(method: String, params: LSPAny? = nil) async throws {
		try await session.sendNotification(method: method, params: params)
	}

	public func shutdown() async throws {
		try await session.shutdown()
	}

	public func terminate() {
		Task {
			await session.cancelPendingRequests()
		}
		transport.terminate()
	}
}
