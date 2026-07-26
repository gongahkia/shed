import Foundation
import ItsyLSP

public enum LSPHealthState: String, Equatable, Sendable {
	case idle
	case starting
	case ready
	case degraded
	case crashed
	case unavailable
}

public enum LSPSessionOutputKind: String, CaseIterable, Equatable, Sendable {
	case process
	case protocolOutput = "protocol"
}

public struct LSPSessionOutput: Equatable, Sendable {
	public var timestamp: Date
	public var kind: LSPSessionOutputKind
	public var text: String

	public init(timestamp: Date = .init(), kind: LSPSessionOutputKind, text: String) {
		self.timestamp = timestamp
		self.kind = kind
		self.text = text
	}
}

public enum LSPLogRedactor {
	public static func redact(_ text: String, environment: [String: String] = ProcessInfo.processInfo.environment) -> String {
		var redacted = text
		for value in sensitiveValues(in: environment) {
			redacted = redacted.replacingOccurrences(of: value, with: "<redacted>")
		}
		redacted = replacing(
			"(?i)\\b([A-Z][A-Z0-9_]*(?:TOKEN|SECRET|PASSWORD|API[_-]?KEY|AUTH(?:ORIZATION)?|COOKIE|CREDENTIAL)[A-Z0-9_]*)=([^\\s]+)",
			with: "$1=<redacted>",
			in: redacted
		)
		redacted = replacing(
			"(?i)(\\\"(?:token|secret|password|api[_-]?key|authorization|cookie|credential)[^\\\"]*\\\"\\s*:\\s*\\\")[^\\\"]*\\\"",
			with: "$1<redacted>\"",
			in: redacted
		)
		return replacing("(?i)(Bearer\\s+)[^\\s]+", with: "$1<redacted>", in: redacted)
	}

	public struct Stream {
		private let environment: [String: String]
		private let sensitiveValues: [String]
		private var lineBuffer = ""
		private var pending = ""

		public init(environment: [String: String] = ProcessInfo.processInfo.environment) {
			self.environment = environment
			sensitiveValues = LSPLogRedactor.sensitiveValues(in: environment)
		}

		public mutating func append(_ text: String) -> String {
			lineBuffer.append(text)
			guard let lineEnd = lineBuffer.lastIndex(of: "\n") else {
				return ""
			}
			let nextLine = lineBuffer.index(after: lineEnd)
			pending.append(contentsOf: lineBuffer[..<nextLine])
			lineBuffer.removeSubrange(..<nextLine)
			return drain(flushing: false)
		}

		public mutating func finish() -> String {
			pending.append(lineBuffer)
			lineBuffer = ""
			return drain(flushing: true)
		}

		private mutating func drain(flushing: Bool) -> String {
			guard !sensitiveValues.isEmpty else {
				let text = pending
				pending = ""
				return LSPLogRedactor.redact(text, environment: environment)
			}
			var output = ""
			while !pending.isEmpty {
				let completed = sensitiveValues.filter { pending.hasPrefix($0) }
				let partial = sensitiveValues.filter { $0.hasPrefix(pending) }
				if let value = completed.max(by: { $0.count < $1.count }),
				   partial.allSatisfy({ $0.count <= value.count })
				{
					output += "<redacted>"
					pending.removeFirst(value.count)
					continue
				}
				if !partial.isEmpty, !flushing {
					break
				}
				output.append(pending.removeFirst())
			}
			return LSPLogRedactor.redact(output, environment: environment)
		}
	}

	private static func sensitiveValues(in environment: [String: String]) -> [String] {
		environment
			.filter { isSensitive($0.key) && !$0.value.isEmpty }
			.map(\.value)
			.sorted { $0.count == $1.count ? $0 > $1 : $0.count > $1.count }
	}

	private static func isSensitive(_ name: String) -> Bool {
		let name = name.lowercased()
		return ["token", "secret", "password", "api_key", "apikey", "authorization", "cookie", "credential"].contains { name.contains($0) }
	}

	private static func replacing(_ pattern: String, with template: String, in text: String) -> String {
		guard let expression = try? NSRegularExpression(pattern: pattern) else {
			return text
		}
		let range = NSRange(text.startIndex..., in: text)
		return expression.stringByReplacingMatches(in: text, range: range, withTemplate: template)
	}
}

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
	case output(LSPSessionOutput)
	case sessionFailed(reason: LSPSessionFailureReason)
	case workspaceEditRequested(id: JSONRPCID, params: LSPApplyWorkspaceEditParams)
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
	private let environment: [String: String]
	private var outputRedactors: [LSPSessionOutputKind: LSPLogRedactor.Stream]

	public init(key: LSPSessionKey, client: LSPProcessClient, environment: [String: String] = ProcessInfo.processInfo.environment) {
		self.init(key: key, events: client.events, environment: environment)
	}

	public init(
		key: LSPSessionKey,
		events clientEvents: AsyncStream<LSPProcessClientEvent>,
		environment: [String: String] = ProcessInfo.processInfo.environment
	) {
		self.key = key
		self.clientEvents = clientEvents
		self.environment = environment
		outputRedactors = Dictionary(uniqueKeysWithValues: LSPSessionOutputKind.allCases.map {
			($0, LSPLogRedactor.Stream(environment: environment))
		})
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

	public func recordDocumentVersion(_ version: Int, forURI uri: String) async {
		ownedURIs.insert(uri)
		await diagnostics.recordDocumentVersion(version, forURI: uri)
	}

	public func clearDiagnostics(forURI uri: String, removingDocument: Bool = false) async {
		if removingDocument {
			ownedURIs.remove(uri)
			await diagnostics.removeDocument(forURI: uri)
		} else {
			await diagnostics.reset(forURI: uri)
		}
		continuation.yield(.diagnosticsUpdated(await diagnostics.snapshot()))
	}

	private func handle(_ event: LSPProcessClientEvent) async {
		switch event {
		case let .server(.notification(notification)):
			await handle(notification)
		case let .stderr(data):
			recordOutput(data, kind: .process)
		case let .terminated(status):
			await handleTermination(status)
		case let .failure(message):
			recordOutput(Data(message.utf8), kind: .protocolOutput)
		case let .server(.request(request)):
			await handle(request)
		}
	}

	private func handle(_ notification: JSONRPCNotificationMessage) async {
		guard notification.method == LSPMethod.textDocumentPublishDiagnostics,
		      let params = try? Self.decode(LSPPublishDiagnosticsParams.self, from: notification.params)
		else {
			return
		}
		ownedURIs.insert(params.uri)
		guard await diagnostics.ingest(params, source: key.languageID) else {
			return
		}
		let snapshot = await diagnostics.snapshot()
		continuation.yield(.diagnosticsUpdated(snapshot))
	}

	private func handle(_ request: JSONRPCRequestMessage) async {
		guard request.method == LSPMethod.workspaceApplyEdit,
		      let params = try? Self.decode(LSPApplyWorkspaceEditParams.self, from: request.params)
		else {
			return
		}
		continuation.yield(.workspaceEditRequested(id: request.id, params: params))
	}

	private func handleTermination(_ status: Int32) async {
		flushOutput()
		for uri in ownedURIs {
			await diagnostics.removeDocument(forURI: uri)
		}
		ownedURIs.removeAll()
		let snapshot = await diagnostics.snapshot()
		continuation.yield(.diagnosticsUpdated(snapshot))
		continuation.yield(.sessionFailed(reason: LSPSessionFailureReason(
			status: status,
			stderrTail: String(decoding: stderrTail, as: UTF8.self)
		)))
	}

	private func recordOutput(_ data: Data, kind: LSPSessionOutputKind) {
		guard var redactor = outputRedactors[kind] else {
			return
		}
		let text = redactor.append(String(decoding: data, as: UTF8.self))
		outputRedactors[kind] = redactor
		emitOutput(text, kind: kind)
	}

	private func flushOutput() {
		for kind in LSPSessionOutputKind.allCases {
			guard var redactor = outputRedactors[kind] else {
				continue
			}
			let text = redactor.finish()
			outputRedactors[kind] = redactor
			emitOutput(text, kind: kind)
		}
	}

	private func emitOutput(_ text: String, kind: LSPSessionOutputKind) {
		guard !text.isEmpty else {
			return
		}
		let redactedData = Data(text.utf8)
		stderrTail.append(redactedData)
		if stderrTail.count > Self.stderrTailLimit {
			stderrTail = Data(stderrTail.suffix(Self.stderrTailLimit))
		}
		continuation.yield(.output(LSPSessionOutput(kind: kind, text: text)))
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
