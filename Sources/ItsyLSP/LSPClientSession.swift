import Foundation

public enum LSPClientState: Equatable, Sendable {
	case idle
	case initializing
	case running
	case shuttingDown
	case exited
}

public enum LSPClientEvent: Equatable, Sendable {
	case request(JSONRPCRequestMessage)
	case notification(JSONRPCNotificationMessage)
}

public enum LSPClientError: Error, Equatable, Sendable {
	case invalidState(expected: [LSPClientState], actual: LSPClientState)
	case unexpectedResponseID(JSONRPCID)
	case responseError(JSONRPCError)
}

public protocol LSPClientTransport: Sendable {
	func write(_ data: Data) throws
}

public actor LSPClientSession {
	public private(set) var state: LSPClientState = .idle

	private let transport: any LSPClientTransport
	private var decoder = JSONDecoder()
	private var framer = LSPMessageFramer()
	private var nextRequestID = 1
	private var pending: [JSONRPCID: CheckedContinuation<JSONRPCResponseMessage, Error>] = [:]

	public init(transport: any LSPClientTransport) {
		self.transport = transport
	}

	@discardableResult
	public func initialize(_ params: LSPInitializeParams) async throws -> LSPAny {
		try requireState([.idle])
		state = .initializing
		do {
			let response = try await sendRequestUnchecked(method: LSPMethod.initialize, params: try LSPAny(encoding: params))
			state = .running
			try writeNotificationUnchecked(method: LSPMethod.initialized, params: .object([:]))
			return response.result ?? .null
		} catch {
			state = .idle
			throw error
		}
	}

	@discardableResult
	public func sendRequest(method: String, params: LSPAny? = nil) async throws -> JSONRPCResponseMessage {
		try requireState([.running])
		return try await sendRequestUnchecked(method: method, params: params)
	}

	public func workspaceSymbol(query: String) async throws -> LSPWorkspaceSymbolResult {
		let response = try await sendRequest(
			method: LSPMethod.workspaceSymbol,
			params: try LSPAny(encoding: LSPWorkspaceSymbolParams(query: query))
		)
		return try LSPWorkspaceSymbolResult(result: response.result)
	}

	public func documentSymbol(textDocument: LSPTextDocumentIdentifier) async throws -> LSPDocumentSymbolResult {
		let response = try await sendRequest(
			method: LSPMethod.textDocumentDocumentSymbol,
			params: try LSPAny(encoding: LSPDocumentSymbolParams(textDocument: textDocument))
		)
		return try LSPDocumentSymbolResult(result: response.result)
	}

	public func prepareRename(uri: String, position: LSPPosition) async throws -> LSPPrepareRenameResult {
		let response = try await sendRequest(
			method: LSPMethod.textDocumentPrepareRename,
			params: try LSPAny(encoding: LSPPrepareRenameParams(
				textDocument: LSPTextDocumentIdentifier(uri: uri),
				position: position
			))
		)
		return try LSPPrepareRenameResult(result: response.result)
	}

	public func rename(uri: String, position: LSPPosition, newName: String) async throws -> LSPWorkspaceEdit? {
		let response = try await sendRequest(
			method: LSPMethod.textDocumentRename,
			params: try LSPAny(encoding: LSPRenameParams(
				textDocument: LSPTextDocumentIdentifier(uri: uri),
				position: position,
				newName: newName
			))
		)
		return try LSPWorkspaceEditResult(result: response.result).edit
	}

	public func formatDocument(uri: String, options: LSPFormattingOptions) async throws -> [LSPTextEdit] {
		let response = try await sendRequest(
			method: LSPMethod.textDocumentFormatting,
			params: try LSPAny(encoding: LSPDocumentFormattingParams(
				textDocument: LSPTextDocumentIdentifier(uri: uri),
				options: options
			))
		)
		return try LSPTextEditResult(result: response.result).edits
	}

	public func formatRange(uri: String, range: LSPRange, options: LSPFormattingOptions) async throws -> [LSPTextEdit] {
		let response = try await sendRequest(
			method: LSPMethod.textDocumentRangeFormatting,
			params: try LSPAny(encoding: LSPDocumentRangeFormattingParams(
				textDocument: LSPTextDocumentIdentifier(uri: uri),
				range: range,
				options: options
			))
		)
		return try LSPTextEditResult(result: response.result).edits
	}

	public func codeActions(uri: String, range: LSPRange, context: LSPCodeActionContext) async throws -> LSPCodeActionResponse {
		let response = try await sendRequest(
			method: LSPMethod.textDocumentCodeAction,
			params: try LSPAny(encoding: LSPCodeActionParams(
				textDocument: LSPTextDocumentIdentifier(uri: uri),
				range: range,
				context: context
			))
		)
		return try LSPCodeActionResponse(result: response.result)
	}

	public func resolveCodeAction(_ action: LSPCodeAction) async throws -> LSPCodeAction {
		let response = try await sendRequest(
			method: LSPMethod.codeActionResolve,
			params: try LSPAny(encoding: action)
		)
		let data = try JSONEncoder().encode(response.result ?? .null)
		return try JSONDecoder().decode(LSPCodeAction.self, from: data)
	}

	@discardableResult
	public func executeCommand(_ command: LSPCommand) async throws -> LSPAny {
		let response = try await sendRequest(
			method: LSPMethod.workspaceExecuteCommand,
			params: try LSPAny(encoding: LSPExecuteCommandParams(command: command.command, arguments: command.arguments))
		)
		return response.result ?? .null
	}

	public func semanticTokensFull(uri: String) async throws -> LSPSemanticTokens? {
		let response = try await sendRequest(
			method: LSPMethod.textDocumentSemanticTokensFull,
			params: try LSPAny(encoding: LSPSemanticTokensParams(textDocument: LSPTextDocumentIdentifier(uri: uri)))
		)
		return try LSPSemanticTokensResult(result: response.result).tokens
	}

	public func semanticTokensDelta(uri: String, previousResultId: String) async throws -> LSPSemanticTokensResult {
		let response = try await sendRequest(
			method: LSPMethod.textDocumentSemanticTokensFullDelta,
			params: try LSPAny(encoding: LSPSemanticTokensDeltaParams(
				textDocument: LSPTextDocumentIdentifier(uri: uri),
				previousResultId: previousResultId
			))
		)
		return try LSPSemanticTokensResult(result: response.result)
	}

	public func semanticTokensRange(uri: String, range: LSPRange) async throws -> LSPSemanticTokens? {
		let response = try await sendRequest(
			method: LSPMethod.textDocumentSemanticTokensRange,
			params: try LSPAny(encoding: LSPSemanticTokensRangeParams(
				textDocument: LSPTextDocumentIdentifier(uri: uri),
				range: range
			))
		)
		return try LSPSemanticTokensResult(result: response.result).tokens
	}

	public func inlayHints(uri: String, range: LSPRange) async throws -> [LSPInlayHint] {
		let response = try await sendRequest(
			method: LSPMethod.textDocumentInlayHint,
			params: try LSPAny(encoding: LSPInlayHintParams(
				textDocument: LSPTextDocumentIdentifier(uri: uri),
				range: range
			))
		)
		return try LSPInlayHintResult(result: response.result).hints
	}

	public func resolveInlayHint(_ hint: LSPInlayHint) async throws -> LSPInlayHint {
		let response = try await sendRequest(
			method: LSPMethod.inlayHintResolve,
			params: try LSPAny(encoding: hint)
		)
		let data = try JSONEncoder().encode(response.result ?? .null)
		return try JSONDecoder().decode(LSPInlayHint.self, from: data)
	}

	public func foldingRanges(uri: String) async throws -> [LSPFoldingRange] {
		let response = try await sendRequest(
			method: LSPMethod.textDocumentFoldingRange,
			params: try LSPAny(encoding: LSPFoldingRangeParams(textDocument: LSPTextDocumentIdentifier(uri: uri)))
		)
		return try LSPFoldingRangeResult(result: response.result).ranges
	}

	public func documentHighlights(uri: String, position: LSPPosition) async throws -> [LSPDocumentHighlight] {
		let response = try await sendRequest(
			method: LSPMethod.textDocumentDocumentHighlight,
			params: try LSPAny(encoding: LSPDocumentHighlightParams(
				textDocument: LSPTextDocumentIdentifier(uri: uri),
				position: position
			))
		)
		return try LSPDocumentHighlightResult(result: response.result).highlights
	}

	public func prepareCallHierarchy(uri: String, position: LSPPosition) async throws -> [LSPCallHierarchyItem] {
		let response = try await sendRequest(
			method: LSPMethod.textDocumentPrepareCallHierarchy,
			params: try LSPAny(encoding: LSPCallHierarchyPrepareParams(
				textDocument: LSPTextDocumentIdentifier(uri: uri),
				position: position
			))
		)
		return try LSPCallHierarchyPrepareResult(result: response.result).items
	}

	public func incomingCalls(for item: LSPCallHierarchyItem) async throws -> [LSPCallHierarchyIncomingCall] {
		let response = try await sendRequest(
			method: LSPMethod.callHierarchyIncomingCalls,
			params: try LSPAny(encoding: LSPCallHierarchyCallsParams(item: item))
		)
		return try LSPCallHierarchyIncomingResult(result: response.result).calls
	}

	public func outgoingCalls(for item: LSPCallHierarchyItem) async throws -> [LSPCallHierarchyOutgoingCall] {
		let response = try await sendRequest(
			method: LSPMethod.callHierarchyOutgoingCalls,
			params: try LSPAny(encoding: LSPCallHierarchyCallsParams(item: item))
		)
		return try LSPCallHierarchyOutgoingResult(result: response.result).calls
	}

	public func prepareTypeHierarchy(uri: String, position: LSPPosition) async throws -> [LSPTypeHierarchyItem] {
		let response = try await sendRequest(
			method: LSPMethod.textDocumentPrepareTypeHierarchy,
			params: try LSPAny(encoding: LSPTypeHierarchyPrepareParams(
				textDocument: LSPTextDocumentIdentifier(uri: uri),
				position: position
			))
		)
		return try LSPTypeHierarchyResult(result: response.result).items
	}

	public func supertypes(for item: LSPTypeHierarchyItem) async throws -> [LSPTypeHierarchyItem] {
		let response = try await sendRequest(
			method: LSPMethod.typeHierarchySupertypes,
			params: try LSPAny(encoding: LSPTypeHierarchyParams(item: item))
		)
		return try LSPTypeHierarchyResult(result: response.result).items
	}

	public func subtypes(for item: LSPTypeHierarchyItem) async throws -> [LSPTypeHierarchyItem] {
		let response = try await sendRequest(
			method: LSPMethod.typeHierarchySubtypes,
			params: try LSPAny(encoding: LSPTypeHierarchyParams(item: item))
		)
		return try LSPTypeHierarchyResult(result: response.result).items
	}

	public func sendNotification(method: String, params: LSPAny? = nil) throws {
		try requireState([.running])
		try writeNotificationUnchecked(method: method, params: params)
	}

	public func respond(to id: JSONRPCID, result: LSPAny) throws {
		try requireState([.running, .initializing])
		let message = JSONRPCMessage.response(JSONRPCResponseMessage(id: id, result: result))
		try transport.write(LSPMessageFramer.frame(message: message))
	}

	public func respond(to id: JSONRPCID, error: JSONRPCError) throws {
		try requireState([.running, .initializing])
		let message = JSONRPCMessage.response(JSONRPCResponseMessage(id: id, error: error))
		try transport.write(LSPMessageFramer.frame(message: message))
	}

	public func shutdown() async throws {
		try requireState([.running])
		state = .shuttingDown
		do {
			_ = try await sendRequestUnchecked(method: LSPMethod.shutdown)
			try writeNotificationUnchecked(method: LSPMethod.exit)
			state = .exited
		} catch {
			state = .running
			throw error
		}
	}

	public func receive(_ data: Data) throws -> [LSPClientEvent] {
		let payloads = try framer.append(data)
		var events: [LSPClientEvent] = []
		for payload in payloads {
			let message = try decoder.decode(JSONRPCMessage.self, from: payload)
			switch message {
			case let .request(request):
				events.append(.request(request))
			case let .notification(notification):
				events.append(.notification(notification))
			case let .response(response):
				try route(response)
			}
		}
		return events
	}

	private func sendRequestUnchecked(method: String, params: LSPAny? = nil) async throws -> JSONRPCResponseMessage {
		let id = JSONRPCID.int(nextRequestID)
		nextRequestID += 1
		let message = JSONRPCMessage.request(JSONRPCRequestMessage(id: id, method: method, params: params))
		let frame = try LSPMessageFramer.frame(message: message)
		return try await withCheckedThrowingContinuation { continuation in
			pending[id] = continuation
			do {
				try transport.write(frame)
			} catch {
				pending.removeValue(forKey: id)
				continuation.resume(throwing: error)
			}
		}
	}

	private func writeNotificationUnchecked(method: String, params: LSPAny? = nil) throws {
		try transport.write(LSPMessageFramer.frame(message: .notification(JSONRPCNotificationMessage(method: method, params: params))))
	}

	private func route(_ response: JSONRPCResponseMessage) throws {
		guard let continuation = pending.removeValue(forKey: response.id) else {
			throw LSPClientError.unexpectedResponseID(response.id)
		}
		if let error = response.error {
			continuation.resume(throwing: LSPClientError.responseError(error))
		} else {
			continuation.resume(returning: response)
		}
	}

	private func requireState(_ expected: [LSPClientState]) throws {
		guard !expected.contains(state) else {
			return
		}
		throw LSPClientError.invalidState(expected: expected, actual: state)
	}
}
