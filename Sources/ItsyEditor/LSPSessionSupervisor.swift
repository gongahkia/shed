import Foundation
import ItsyLSP

public struct LSPSessionFailureReason: Equatable, Sendable {
	public var status: Int32
	public var stderrTail: String

	public init(status: Int32, stderrTail: String) {
		self.status = status
		self.stderrTail = stderrTail
	}
}

public enum LSPSessionSupervisorEvent: Equatable, Sendable {
	case diagnosticsUpdated(WorkspaceProblemSnapshot)
	case sessionFailed(reason: LSPSessionFailureReason)
}

public actor LSPSessionSupervisor {
	public static let stderrTailLimit = 4096

	public nonisolated let key: LSPSessionKey
	public nonisolated let events: AsyncStream<LSPSessionSupervisorEvent>

	private let clientEvents: AsyncStream<LSPProcessClientEvent>
	private let diagnostics: LSPDiagnosticsAggregator
	private let continuation: AsyncStream<LSPSessionSupervisorEvent>.Continuation
	private var pumpTask: Task<Void, Never>?
	private var ownedURIs: Set<String> = []
	private var stderrTail = Data()

	public init(key: LSPSessionKey, client: LSPProcessClient) {
		self.init(key: key, events: client.events)
	}

	public init(key: LSPSessionKey, events clientEvents: AsyncStream<LSPProcessClientEvent>) {
		self.key = key
		self.clientEvents = clientEvents
		diagnostics = LSPDiagnosticsAggregator(root: key.workspaceRoot)
		var capturedContinuation: AsyncStream<LSPSessionSupervisorEvent>.Continuation?
		events = AsyncStream { continuation in
			capturedContinuation = continuation
		}
		continuation = capturedContinuation!
	}

	deinit {
		pumpTask?.cancel()
		continuation.finish()
	}

	public func start() {
		guard pumpTask == nil else {
			return
		}
		pumpTask = Task { [weak self, clientEvents] in
			for await event in clientEvents {
				await self?.handle(event)
			}
			await self?.finish()
		}
	}

	public func stop() {
		pumpTask?.cancel()
		pumpTask = nil
		continuation.finish()
	}

	public func recordOwnedURI(_ uri: String) {
		ownedURIs.insert(uri)
	}

	private func handle(_ event: LSPProcessClientEvent) async {
		switch event {
		case let .server(.notification(notification)):
			await handle(notification)
		case let .stderr(data):
			appendStderr(data)
		case let .terminated(status):
			await handleTermination(status)
		case let .failure(message):
			appendStderr(Data(message.utf8))
		case .server(.request):
			break
		}
	}

	private func handle(_ notification: JSONRPCNotificationMessage) async {
		guard notification.method == LSPMethod.textDocumentPublishDiagnostics,
		      let params = try? Self.decode(LSPPublishDiagnosticsParams.self, from: notification.params)
		else {
			return
		}
		ownedURIs.insert(params.uri)
		await diagnostics.ingest(params, source: key.languageID)
		let snapshot = await diagnostics.snapshot()
		continuation.yield(.diagnosticsUpdated(snapshot))
	}

	private func handleTermination(_ status: Int32) async {
		guard status != 0 else {
			return
		}
		for uri in ownedURIs {
			await diagnostics.reset(forURI: uri)
		}
		let snapshot = await diagnostics.snapshot()
		continuation.yield(.diagnosticsUpdated(snapshot))
		continuation.yield(.sessionFailed(reason: LSPSessionFailureReason(
			status: status,
			stderrTail: String(decoding: stderrTail, as: UTF8.self)
		)))
	}

	private func appendStderr(_ data: Data) {
		stderrTail.append(data)
		if stderrTail.count > Self.stderrTailLimit {
			stderrTail = Data(stderrTail.suffix(Self.stderrTailLimit))
		}
	}

	private func finish() {
		pumpTask = nil
		continuation.finish()
	}

	private static func decode<Value: Decodable>(_ type: Value.Type, from value: LSPAny?) throws -> Value {
		let data = try JSONEncoder().encode(value ?? .null)
		return try JSONDecoder().decode(type, from: data)
	}
}
